package com.jkxt.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * P1-1 regression: business write endpoints must enforce role authorization, not just authentication.
 * A read-only VIEWER may read but must be denied writes; an ADMIN passes the security gate (and only
 * then hits request-body validation). Previously every authenticated user could write because only
 * /admin/** was role-gated.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BusinessRbacAcceptanceTest {

  private static final String VIEWER_USERNAME = "acc.test.viewer";
  private static final String VIEWER_PASSWORD = "Viewer@2025";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("delete from app_user where username = ?", VIEWER_USERNAME);
  }

  @Test
  void viewerIsDeniedBusinessWriteWhileAdminPassesSecurityGate() throws Exception {
    // Seed a read-only VIEWER in the same org as the demo admin.
    UUID orgId = jdbcTemplate.queryForObject(
        "select org_id from app_user where username = 'jwc.demo'", UUID.class);
    jdbcTemplate.update("""
        insert into app_user
          (id, org_id, username, display_name, email, phone, password_hash, role, status,
           must_change_password, onboarded)
        values (?, ?, ?, ?, ?, ?, ?, 'VIEWER', 'active', false, true)
        """,
        UUID.randomUUID(), orgId, VIEWER_USERNAME, "Acceptance Viewer",
        "acc.viewer@test.local", "13900000000", passwordEncoder.encode(VIEWER_PASSWORD));

    // VIEWER write attempt -> 403 AUTH_FORBIDDEN (security denies before body validation runs).
    Cookie viewerSession = login(VIEWER_USERNAME, VIEWER_PASSWORD);
    mockMvc.perform(post("/api/v1/teachers")
            .cookie(viewerSession)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

    // ADMIN (jwc.demo) passes the role gate; an empty body then fails @Valid -> 400, NOT 403.
    Cookie adminSession = login("jwc.demo", "Demo@2025");
    mockMvc.perform(post("/api/v1/teachers")
            .cookie(adminSession)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest());
  }

  private Cookie login(String username, String password) throws Exception {
    CaptchaResponse captcha = captcha();
    MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "username": "%s",
                  "password": "%s",
                  "captchaId": "%s",
                  "captcha": "%s"
                }
                """.formatted(username, password, captcha.captchaId(), captcha.debugAnswer())))
        .andExpect(status().isOk())
        .andReturn();
    return login.getResponse().getCookie("JKXT_SESSION");
  }

  private CaptchaResponse captcha() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/captcha"))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), CaptchaResponse.class);
  }
}
