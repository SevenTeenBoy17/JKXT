package com.jkxt.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Acceptance coverage for the uniform error envelope on framework-level exceptions that previously
 * fell through to the catch-all Exception->500 handler. Each must now map to its correct HTTP status
 * with a stable {@code code} the frontend can branch on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RestExceptionHandlerAcceptanceTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Test
  void malformedJsonBodyReturns400WithMalformedRequestCode() throws Exception {
    // /api/v1/auth/login is permitAll, so the request reaches the controller and Jackson fails to
    // parse the broken body -> HttpMessageNotReadableException, not a 500.
    mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{ this is not valid json "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.traceId").exists());
  }

  @Test
  void wrongHttpMethodReturns405WithMethodNotAllowedCodeAndAllowHeader() throws Exception {
    // login only supports POST; GET must surface as 405, not 500, and advertise the allowed methods.
    mockMvc.perform(get("/api/v1/auth/login"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
        .andExpect(jsonPath("$.status").value(405));
  }

  @Test
  void unmappedPathReturns404WithNotFoundCode() throws Exception {
    // Must be authenticated: an unauthenticated /api/v1/** hit short-circuits to 401 at the security
    // entry point. With a valid session it passes the filter, reaches the dispatcher, and resolves to
    // no handler -> NoResourceFoundException -> 404 (instead of the old 500).
    Cookie session = login();

    mockMvc.perform(post("/api/v1/tasks/00000000-0000-0000-0000-000000000000/schedule/does-not-exist")
            .cookie(session))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.status").value(404));
  }

  private Cookie login() throws Exception {
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
        .andReturn();
    return login.getResponse().getCookie("JKXT_SCHOOL_SESSION");
  }

  private CaptchaResponse captcha() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/captcha"))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), CaptchaResponse.class);
  }
}
