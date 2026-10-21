package com.jkxt.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * PB-5 (round 9) acceptance: with an authenticated cookie session, every unsafe request - JSON create,
 * edit, multipart upload, password change and logout - is refused (403 CSRF_TOKEN_INVALID) unless it
 * echoes the XSRF-TOKEN cookie in the X-XSRF-TOKEN header. Uses its own MockMvc so the test-suite
 * default CSRF post-processor ({@code support.CsrfMockMvcTestDefaults}) is NOT applied.
 */
@SpringBootTest
@ActiveProfiles("test")
class CsrfProtectionIntegrationTest {

  @Autowired
  private WebApplicationContext context;

  @Autowired
  private ObjectMapper objectMapper;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void loginIsExemptAndIssuesAReadableCsrfCookieSeparateFromTheHttpOnlySession() throws Exception {
    MvcResult login = login();
    Cookie session = login.getResponse().getCookie("JKXT_SCHOOL_SESSION");
    Cookie xsrf = login.getResponse().getCookie("XSRF-TOKEN");
    assertThat(session).isNotNull();
    assertThat(session.isHttpOnly()).isTrue();
    assertThat(xsrf).as("CSRF carrier cookie is rendered eagerly").isNotNull();
    assertThat(xsrf.isHttpOnly()).as("SPA must be able to read the CSRF carrier").isFalse();
    assertThat(xsrf.getPath()).isEqualTo("/");
    assertThat(xsrf.getValue()).isNotBlank().isNotEqualTo(session.getValue());
  }

  @Test
  void unsafeRequestsWithoutTokenAreRejectedAndWithTokenPass() throws Exception {
    MvcResult login = login();
    Cookie session = login.getResponse().getCookie("JKXT_SCHOOL_SESSION");
    Cookie xsrf = login.getResponse().getCookie("XSRF-TOKEN");

    // create (JSON)
    mockMvc.perform(post("/api/v1/teachers").cookie(session)
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    // edit (JSON)
    mockMvc.perform(put("/api/v1/teachers/" + UUID.randomUUID()).cookie(session)
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    // multipart upload
    MockMultipartFile file = new MockMultipartFile("file", "t.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[] {1, 2, 3});
    mockMvc.perform(multipart("/api/v1/import/teachers/validate").file(file).cookie(session))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    // change password
    mockMvc.perform(post("/api/v1/auth/me/change-password").cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"Demo@2025\",\"newPassword\":\"Another@2026x\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    // forged / mismatching token
    mockMvc.perform(post("/api/v1/auth/logout").cookie(session, xsrf).header("X-XSRF-TOKEN", "forged-token"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    // header without the matching cookie (cross-site attacker cannot read/set the cookie)
    mockMvc.perform(post("/api/v1/auth/logout").cookie(session).header("X-XSRF-TOKEN", xsrf.getValue()))
        .andExpect(status().isForbidden());
    // logout without token
    mockMvc.perform(post("/api/v1/auth/logout").cookie(session))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));

    // none of the rejected requests touched the session
    mockMvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isOk());

    // multipart with token reaches the controller (not a CSRF 403)
    int multipartStatus = mockMvc.perform(multipart("/api/v1/import/teachers/validate").file(file)
            .cookie(session, xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
        .andReturn().getResponse().getStatus();
    assertThat(multipartStatus).isNotEqualTo(403);

    // logout with the matching token succeeds and invalidates the session
    int logoutStatus = mockMvc.perform(post("/api/v1/auth/logout").cookie(session, xsrf)
            .header("X-XSRF-TOKEN", xsrf.getValue()))
        .andReturn().getResponse().getStatus();
    assertThat(logoutStatus).isBetween(200, 299);
    mockMvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isUnauthorized());
  }

  private MvcResult login() throws Exception {
    MvcResult captchaResult = mockMvc.perform(post("/api/v1/auth/captcha"))
        .andExpect(status().isOk())
        .andReturn();
    CaptchaResponse captcha = objectMapper.readValue(
        captchaResult.getResponse().getContentAsString(), CaptchaResponse.class);
    return mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"username":"jwc.demo","password":"Demo@2025","captchaId":"%s","captcha":"%s"}
                """.formatted(captcha.captchaId(), captcha.debugAnswer())))
        .andExpect(status().isOk())
        .andReturn();
  }
}
