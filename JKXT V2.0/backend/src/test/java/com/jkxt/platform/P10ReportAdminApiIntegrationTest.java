package com.jkxt.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.audit.repo.AuditLogRepository;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class P10ReportAdminApiIntegrationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private AuditLogRepository auditLogRepository;

  @Test
  void tenantsReportsTimelineAndAllowanceUseBackendData() throws Exception {
    LoginSession login = login();

    mockMvc.perform(get("/api/v1/admin/tenants").cookie(login.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].uuid").exists())
        .andExpect(jsonPath("$[0].slug").exists())
        .andExpect(jsonPath("$[0].abbr").exists())
        .andExpect(jsonPath("$[0].color").exists())
        .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(7)));

    String firstHistory = mockMvc.perform(get("/api/v1/history/teachers").cookie(login.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].contributionPct").exists())
        .andExpect(jsonPath("$[0].allowance").exists())
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
    String secondHistory = mockMvc.perform(get("/api/v1/history/teachers").cookie(login.cookie()))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
    assertThat(secondHistory).isEqualTo(firstHistory);

    mockMvc.perform(get("/api/v1/stats/supervise").param("dim", "subject").cookie(login.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0].count").exists())
        .andExpect(jsonPath("$.rows[0].allowance").exists());

    mockMvc.perform(get("/api/v1/timeline/schedule").cookie(login.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currentDate").exists())
        .andExpect(jsonPath("$.days[0].isoDate").exists())
        .andExpect(jsonPath("$.exams[0].examDate").exists());

    mockMvc.perform(get("/api/v1/teachers").cookie(login.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].allowance").exists());
  }

  @Test
  void highRiskSecurityMutationWritesAuditLog() throws Exception {
    LoginSession login = login();
    String ip = "198.51.100.77";

    mockMvc.perform(post("/api/v1/admin/security/ip-ban")
            .cookie(login.cookie())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "ip": "%s",
                  "reason": "p10 integration test"
                }
                """.formatted(ip)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ip").value(ip))
        .andExpect(jsonPath("$.ruleType").value("ban"));

    assertThat(auditLogRepository.findByOrgId(login.orgId()))
        .anySatisfy(log -> {
          assertThat(log.getAction()).isEqualTo("ADMIN_SECURITY_IP_BAN");
          assertThat(log.getSeverity()).isEqualTo("high");
          assertThat(log.getPayload()).containsEntry("ip", ip);
        });

    mockMvc.perform(get("/api/v1/admin/audit-logs").cookie(login.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].action").exists());
  }

  private LoginSession login() throws Exception {
    CaptchaResponse captcha = captcha();
    MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
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
    Cookie session = result.getResponse().getCookie("JKXT_SESSION");
    assertThat(session).isNotNull();
    JsonNode body = json(result);
    return new LoginSession(session, UUID.fromString(body.path("org").path("id").asText()));
  }

  private CaptchaResponse captcha() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/captcha"))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(StandardCharsets.UTF_8), CaptchaResponse.class);
  }

  private JsonNode json(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private record LoginSession(Cookie cookie, UUID orgId) {
  }
}
