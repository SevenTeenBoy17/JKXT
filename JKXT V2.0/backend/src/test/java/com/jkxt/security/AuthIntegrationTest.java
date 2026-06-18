package com.jkxt.security;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

  @Test
  void markOnboardedPersistsServerSideAcrossSessions() throws Exception {
    // Force a known "not yet onboarded" state so we exercise the false -> true transition.
    jdbcTemplate.update("update app_user set onboarded = false where username = 'jwc.demo'");

    CaptchaResponse captcha = captcha();
    MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "username": "jwc.demo",
                  "password": "Demo@2025",
                  "captchaId": "%s",
                  "captcha": "%s"
                }
                """.formatted(captcha.captchaId(), captcha.debugAnswer())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.onboarded").value(false))
        .andReturn();
    Cookie session = login.getResponse().getCookie("JKXT_SESSION");

    // Marking onboarded returns the refreshed profile with onboarded=true.
    mockMvc.perform(post("/api/v1/auth/me/onboarded").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.onboarded").value(true))
        .andExpect(jsonPath("$.user.username").value("jwc.demo"));

    // Persisted: a fresh /me reflects it (so re-login on any device will not re-trigger onboarding).
    mockMvc.perform(get("/api/v1/auth/me").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.onboarded").value(true));

    // Unauthenticated callers are rejected, like every other authenticated surface.
    mockMvc.perform(post("/api/v1/auth/me/onboarded"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
  }

  @Test
  void loginMeAndLogoutUseHttpOnlyCookieSession() throws Exception {
    CaptchaResponse captcha = captcha();

    MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "username": "jwc.demo",
                  "password": "Demo@2025",
                  "captchaId": "%s",
                  "captcha": "%s",
                  "role": "super"
                }
                """.formatted(captcha.captchaId(), captcha.debugAnswer())))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
        .andExpect(jsonPath("$.user.username").value("jwc.demo"))
        .andExpect(jsonPath("$.user.role").value("ADMIN"))
        .andExpect(jsonPath("$.org.slug").value("demo"))
        .andExpect(jsonPath("$.term.yearLabel").value("2025-2026"))
        .andExpect(jsonPath("$.onboarded").value(true))
        .andExpect(jsonPath("$.permissions[0]").exists())
        .andReturn();

    Cookie session = login.getResponse().getCookie("JKXT_SESSION");

    mockMvc.perform(get("/api/v1/auth/me").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.username").value("jwc.demo"))
        .andExpect(jsonPath("$.org.slug").value("demo"))
        .andExpect(jsonPath("$.permissions[0]").exists());

    mockMvc.perform(post("/api/v1/auth/logout").cookie(session))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")))
        .andExpect(jsonPath("$.ok").value(true));

    mockMvc.perform(get("/api/v1/auth/me").cookie(session))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
  }

  @Test
  void invalidCaptchaAndUnauthenticatedBusinessAccessReturnStableErrorCodes() throws Exception {
    mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "username": "jwc.demo",
                  "password": "Demo@2025",
                  "captchaId": "missing",
                  "captcha": "WRONG"
                }
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("AUTH_CAPTCHA_FAILED"));

    mockMvc.perform(get("/api/v1/exam-tasks"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
  }

  @Test
  void concurrentSameAccountLoginAllSucceed() throws Exception {
    // P1-6 regression: two+ concurrent successful logins for the SAME account used to collide on the
    // UserAccount @Version (userRepository.save on the success path) -> ObjectOptimisticLockingFailureException
    // -> 500/409 for one of them. The success path now persists bookkeeping via a direct JDBC UPDATE,
    // so every concurrent login must return 200.
    int n = 6;

    // Captcha is single-use, so mint one per request up front (outside the parallel section).
    java.util.List<CaptchaResponse> captchas = new java.util.ArrayList<>();
    for (int i = 0; i < n; i++) {
      captchas.add(captcha());
    }

    java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(n);
    try {
      java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(n);
      java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
      java.util.List<java.util.concurrent.Future<Integer>> futures = new java.util.ArrayList<>();

      for (int i = 0; i < n; i++) {
        CaptchaResponse captcha = captchas.get(i);
        futures.add(pool.submit(() -> {
          ready.countDown();
          go.await(); // release all threads at once to maximise the race
          return mockMvc.perform(post("/api/v1/auth/login")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("""
                      {
                        "username": "jwc.demo",
                        "password": "Demo@2025",
                        "captchaId": "%s",
                        "captcha": "%s"
                      }
                      """.formatted(captcha.captchaId(), captcha.debugAnswer())))
              .andReturn()
              .getResponse()
              .getStatus();
        }));
      }

      ready.await();
      go.countDown();

      for (java.util.concurrent.Future<Integer> future : futures) {
        org.junit.jupiter.api.Assertions.assertEquals(
            200, future.get(), "every concurrent same-account login must return 200 (no 500/409 optimistic-lock failure)");
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private CaptchaResponse captcha() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/captcha"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.captchaId").exists())
        .andExpect(jsonPath("$.imageBase64").exists())
        .andExpect(jsonPath("$.debugAnswer").exists())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), CaptchaResponse.class);
  }
}
