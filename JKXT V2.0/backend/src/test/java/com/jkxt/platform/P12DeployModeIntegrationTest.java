package com.jkxt.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
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

@SpringBootTest(properties = {
    "jkxt.deploy.mode=private",
    "jkxt.deploy.fixed-org-id=00000000-0000-0000-0000-000000000001"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class P12DeployModeIntegrationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Test
  void privateModeScopesPlatformTenantAndAccountListsToFixedOrg() throws Exception {
    Cookie session = login();

    JsonNode tenants = json(mockMvc.perform(get("/api/v1/admin/tenants").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].slug").value("demo"))
        .andReturn());
    assertThat(tenants.get(0).get("uuid").asText()).isEqualTo("00000000-0000-0000-0000-000000000001");

    JsonNode accounts = json(mockMvc.perform(get("/api/v1/admin/accounts").cookie(session))
        .andExpect(status().isOk())
        .andReturn());
    for (JsonNode account : accounts) {
      assertThat(account.path("tenant").asText()).isEqualTo("DEMO");
    }
  }

  @Test
  void privateModeRejectsCreatingOrTargetingOtherTenants() throws Exception {
    Cookie session = login();

    mockMvc.perform(post("/api/v1/admin/tenants")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "name": "Second Private Tenant",
                  "slug": "second-private"
                }
                """))
        .andExpect(status().isForbidden());

    mockMvc.perform(post("/api/v1/admin/accounts")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "tenantId": "00000000-0000-0000-0000-000000010002",
                  "email": "wrong-tenant@jkxt.local",
                  "name": "Wrong Tenant Admin"
                }
                """))
        .andExpect(status().isForbidden());

    mockMvc.perform(post("/api/v1/admin/accounts/00000000-0000-0000-0000-000000012002/disable")
            .cookie(session))
        .andExpect(status().isForbidden());
  }

  @Test
  void privateModeRejectsLoginForNonFixedOrgEvenWithValidCredentials() throws Exception {
    seedOtherTenantLogin();
    CaptchaResponse captcha = captcha();

    mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "username": "p12.other",
                  "password": "Demo@2025",
                  "captchaId": "%s",
                  "captcha": "%s"
                }
                """.formatted(captcha.captchaId(), captcha.debugAnswer())))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TENANT_VIOLATION"));
  }

  private Cookie login() throws Exception {
    CaptchaResponse captcha = captcha();
    MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "username": "ops.demo",
                  "password": "Demo@2025",
                  "captchaId": "%s",
                  "captcha": "%s"
                }
                """.formatted(captcha.captchaId(), captcha.debugAnswer())))
        .andExpect(status().isOk())
        .andReturn();
    Cookie session = result.getResponse().getCookie("JKXT_SESSION");
    assertThat(session).isNotNull();
    return session;
  }

  private CaptchaResponse captcha() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/captcha"))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(StandardCharsets.UTF_8), CaptchaResponse.class);
  }

  private void seedOtherTenantLogin() {
    jdbcTemplate.update("""
        insert into app_user (
          id, org_id, username, display_name, email, phone, password_hash, role, status,
          must_change_password, onboarded, failed_attempts, login_count
        ) values (
          '00000000-0000-0000-0000-000000012912',
          '00000000-0000-0000-0000-000000010002',
          'p12.other',
          'P12 Other Tenant',
          'p12.other@nky.local',
          '13800009912',
          ?,
          'ADMIN',
          'active',
          false,
          true,
          0,
          0
        )
        on conflict (org_id, username) do update set
          password_hash = excluded.password_hash,
          status = excluded.status,
          failed_attempts = 0,
          locked_until = null,
          updated_at = now()
        """, passwordEncoder.encode("Demo@2025"));
  }

  private JsonNode json(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
