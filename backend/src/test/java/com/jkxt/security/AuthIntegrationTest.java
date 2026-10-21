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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
    Cookie session = login.getResponse().getCookie("JKXT_SCHOOL_SESSION");

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

    Cookie session = login.getResponse().getCookie("JKXT_SCHOOL_SESSION");

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

  @Test
  void changePasswordVerifiesCurrentRejectsWeakPersistsAndIsSelfService() throws Exception {
    // 迭代5 修复固化：POST /auth/me/change-password 须验证当前密码、拒绝弱密码、修改后持久(旧密码登录失败、
    // 新密码登录成功)、且任意登录用户可改自己密码、未登录被拒。捕获原 hash 并在 finally 还原，避免影响其它用例。
    String originalHash = jdbcTemplate.queryForObject(
        "select password_hash from app_user where username = 'jwc.demo'", String.class);
    try {
      Cookie session = loginResult("jwc.demo", "Demo@2025").getResponse().getCookie("JKXT_SCHOOL_SESSION");

      // 当前密码错误 -> 400
      mockMvc.perform(post("/api/v1/auth/me/change-password").cookie(session)
              .contentType(MediaType.APPLICATION_JSON).content(changeBody("WRONGpwd!!", "NewDemo@2026")))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("AUTH_CURRENT_PASSWORD_INVALID"));

      // 新密码过弱(<8) -> 400
      mockMvc.perform(post("/api/v1/auth/me/change-password").cookie(session)
              .contentType(MediaType.APPLICATION_JSON).content(changeBody("Demo@2025", "short")))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("PASSWORD_TOO_WEAK"));

      // 合法修改 -> 200
      mockMvc.perform(post("/api/v1/auth/me/change-password").cookie(session)
              .contentType(MediaType.APPLICATION_JSON).content(changeBody("Demo@2025", "NewDemo@2026")))
          .andExpect(status().isOk());

      // 旧密码登录失败、新密码登录成功(持久生效)
      org.junit.jupiter.api.Assertions.assertEquals(401, loginResult("jwc.demo", "Demo@2025").getResponse().getStatus());
      org.junit.jupiter.api.Assertions.assertEquals(200, loginResult("jwc.demo", "NewDemo@2026").getResponse().getStatus());

      // 未登录调用 -> 401
      mockMvc.perform(post("/api/v1/auth/me/change-password")
              .contentType(MediaType.APPLICATION_JSON).content(changeBody("x", "yyyyyyyy")))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
    } finally {
      jdbcTemplate.update("update app_user set password_hash = ? where username = 'jwc.demo'", originalHash);
    }
  }

  @Test
  void changePasswordClearsMustChangePasswordFlag() throws Exception {
    // 迭代9 F3(终审)：must_change_password 若改密后不复位,用户改了密码仍被反复要求改密(功能死循环)。
    // 固化 AuthService.changePassword 会置 must_change_password=false。finally 还原口令与标志。
    String originalHash = jdbcTemplate.queryForObject(
        "select password_hash from app_user where username = 'jwc.demo'", String.class);
    try {
      jdbcTemplate.update("update app_user set must_change_password = true where username = 'jwc.demo'");
      Cookie session = loginResult("jwc.demo", "Demo@2025").getResponse().getCookie("JKXT_SCHOOL_SESSION");
      mockMvc.perform(post("/api/v1/auth/me/change-password").cookie(session)
              .contentType(MediaType.APPLICATION_JSON).content(changeBody("Demo@2025", "NewDemo@2026")))
          .andExpect(status().isOk());
      Boolean stillMustChange = jdbcTemplate.queryForObject(
          "select must_change_password from app_user where username = 'jwc.demo'", Boolean.class);
      org.junit.jupiter.api.Assertions.assertEquals(Boolean.FALSE, stillMustChange,
          "改密后 must_change_password 必须复位为 false,否则构成强制改密死循环");
    } finally {
      jdbcTemplate.update(
          "update app_user set password_hash = ?, must_change_password = false where username = 'jwc.demo'",
          originalHash);
    }
  }

  @Test
  void changePasswordRevokesOtherSessionsButKeepsCurrent() throws Exception {
    // 迭代6 安全加固：改密码后，该用户在其它设备的会话(旧 cookie)立即失效；改密码所用的当前设备保持登录。
    String originalHash = jdbcTemplate.queryForObject(
        "select password_hash from app_user where username = 'jwc.demo'", String.class);
    try {
      Cookie sessionA = loginResult("jwc.demo", "Demo@2025").getResponse().getCookie("JKXT_SCHOOL_SESSION");
      Cookie sessionB = loginResult("jwc.demo", "Demo@2025").getResponse().getCookie("JKXT_SCHOOL_SESSION");

      // 两个会话起初都有效
      mockMvc.perform(get("/api/v1/auth/me").cookie(sessionA)).andExpect(status().isOk());
      mockMvc.perform(get("/api/v1/auth/me").cookie(sessionB)).andExpect(status().isOk());

      // 用 A 改密码
      mockMvc.perform(post("/api/v1/auth/me/change-password").cookie(sessionA)
              .contentType(MediaType.APPLICATION_JSON).content(changeBody("Demo@2025", "NewDemo@2026")))
          .andExpect(status().isOk());

      // A(当前设备)保持登录；B(其它设备)被撤销 -> 401
      mockMvc.perform(get("/api/v1/auth/me").cookie(sessionA)).andExpect(status().isOk());
      mockMvc.perform(get("/api/v1/auth/me").cookie(sessionB))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
    } finally {
      jdbcTemplate.update("update app_user set password_hash = ? where username = 'jwc.demo'", originalHash);
    }
  }

  private MvcResult loginResult(String username, String password) throws Exception {
    CaptchaResponse captcha = captcha();
    return mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(java.util.Map.of(
                "username", username, "password", password,
                "captchaId", captcha.captchaId(), "captcha", captcha.debugAnswer()))))
        .andReturn();
  }

  private String changeBody(String current, String next) {
    return "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}";
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
