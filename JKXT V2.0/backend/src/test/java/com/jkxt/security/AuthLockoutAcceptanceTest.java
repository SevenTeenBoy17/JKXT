package com.jkxt.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
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
 * P0 regression: brute-force account lockout. The failed-login path persists failed_attempts + a
 * AUTH_LOGIN_FAILED audit and THEN throws AuthException; without {@code noRollbackFor=AuthException}
 * on the @Transactional login(), the default rollback-on-RuntimeException discarded those writes, so
 * the counter never accumulated and the account never locked. This proves the counter accumulates and
 * the 5th wrong password locks the account (423 AUTH_LOCKED).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthLockoutAcceptanceTest {

  private static final String USERNAME = "recheck.lock.test";
  private static final String PASSWORD = "Locktest@2025";

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
    jdbcTemplate.update("delete from app_user where username = ?", USERNAME);
  }

  @Test
  void fiveWrongPasswordsLockTheAccount() throws Exception {
    UUID orgId = jdbcTemplate.queryForObject(
        "select org_id from app_user where username = 'jwc.demo'", UUID.class);
    jdbcTemplate.update("""
        insert into app_user
          (id, org_id, username, display_name, email, phone, password_hash, role, status,
           must_change_password, onboarded, failed_attempts)
        values (?, ?, ?, ?, ?, ?, ?, 'ADMIN', 'active', false, true, 0)
        """,
        UUID.randomUUID(), orgId, USERNAME, "Lockout Test",
        "recheck.lock@test.local", "13900000099", passwordEncoder.encode(PASSWORD));

    // Attempts 1-4: wrong password -> 401 AUTH_INVALID_CREDENTIALS, counter accumulates across requests.
    for (int i = 1; i <= 4; i++) {
      attemptLogin("definitely-wrong")
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    // 5th wrong attempt crosses the threshold -> account locked (423 AUTH_LOCKED).
    attemptLogin("definitely-wrong")
        .andExpect(status().isLocked())
        .andExpect(jsonPath("$.code").value("AUTH_LOCKED"));

    // Even the CORRECT password is now rejected while locked.
    attemptLogin(PASSWORD)
        .andExpect(status().isLocked())
        .andExpect(jsonPath("$.code").value("AUTH_LOCKED"));

    // The failed-attempt bookkeeping was actually persisted (proving noRollbackFor committed it).
    Integer failed = jdbcTemplate.queryForObject(
        "select failed_attempts from app_user where username = ?", Integer.class, USERNAME);
    assertThat(failed).isGreaterThanOrEqualTo(5);
    Long locked = jdbcTemplate.queryForObject(
        "select count(*) from app_user where username = ? and locked_until is not null", Long.class, USERNAME);
    assertThat(locked).isEqualTo(1L);
  }

  private org.springframework.test.web.servlet.ResultActions attemptLogin(String password) throws Exception {
    CaptchaResponse captcha = captcha();
    return mockMvc.perform(post("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
            {
              "username": "%s",
              "password": "%s",
              "captchaId": "%s",
              "captcha": "%s"
            }
            """.formatted(USERNAME, password, captcha.captchaId(), captcha.debugAnswer())));
  }

  private CaptchaResponse captcha() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/captcha"))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), CaptchaResponse.class);
  }
}
