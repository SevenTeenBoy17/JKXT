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
