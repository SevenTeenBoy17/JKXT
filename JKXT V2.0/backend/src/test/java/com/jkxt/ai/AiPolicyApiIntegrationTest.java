package com.jkxt.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.ai.domain.AiCallLog;
import com.jkxt.ai.repo.AiCallLogRepository;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "jkxt.llm.enabled=false",
    "jkxt.llm.provider=mock",
    "jkxt.llm.monthly-quota=50"
})
class AiPolicyApiIntegrationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private AiCallLogRepository callLogRepository;

  @Test
  void mockProviderParsesRefinesMasksInputAndReportsUsage() throws Exception {
    LoginSession login = login();

    mockMvc.perform(post("/api/v1/nl-policy/parse")
            .cookie(login.cookie())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "text", "王小明老师周一上午有会议, 手机 13812345678, 请避开并保持工作量均衡",
                "policyIds", List.of("homeroom_avoid", "workload_balance"),
                "provider", "mock"
            ))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dsl.solverAuthority").value("OR_TOOLS"))
        .andExpect(jsonPath("$.dsl.hard[0].type").value("teacher_avoid_class"))
        .andExpect(jsonPath("$.status").value("succeeded"));

    mockMvc.perform(post("/api/v1/nl-policy/refine")
            .cookie(login.cookie())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "text", "王小明老师周一上午有会议, 请避开",
                "policyIds", List.of("homeroom_avoid"),
                "provider", "mock"
            ))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.refinedText").exists())
        .andExpect(jsonPath("$.suggestions[0]").exists());

    mockMvc.perform(get("/api/v1/ai/providers").cookie(login.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.providers[0].id").value("mock"))
        .andExpect(jsonPath("$.providers[1].id").value("llm-token"))
        .andExpect(jsonPath("$.providers[1].endpointHost").value("api.llm-token.cn"))
        .andExpect(jsonPath("$.usage.monthCallCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
        .andExpect(jsonPath("$.usage.remainingQuota").exists());

    List<AiCallLog> logs = callLogRepository.findTop5ByOrgIdOrderByCreatedAtDesc(login.orgId());
    assertThat(logs).isNotEmpty();
    AiCallLog parseLog = logs.stream()
        .filter(log -> "nl2dsl".equals(log.getPurpose()))
        .findFirst()
        .orElseThrow();
    assertThat(parseLog.getMaskedInput().get("text").toString())
        .contains("王**老师")
        .doesNotContain("小明")
        .doesNotContain("13812345678");
    assertThat(parseLog.getInputHash()).isNotBlank();
    assertThat(parseLog.getMaskedOutput()).containsKey("dsl");
  }

  @Test
  void invalidSchemaFallsBackAndIsNotLoggedAsSuccessfulDsl() throws Exception {
    LoginSession login = login();

    mockMvc.perform(post("/api/v1/nl-policy/parse")
            .cookie(login.cookie())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "text", "班主任回避本班, 同年级回避",
                "policyIds", List.of("homeroom_avoid", "same_grade_avoid"),
                "provider", "invalid-schema"
            ))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("fallback"))
        .andExpect(jsonPath("$.fallback").value(true))
        .andExpect(jsonPath("$.dsl.solverAuthority").value("OR_TOOLS"))
        .andExpect(jsonPath("$.warnings[0]").exists());

    List<AiCallLog> logs = callLogRepository.findTop10ByOrgIdAndProviderOrderByCreatedAtDesc(login.orgId(), "invalid-schema");
    AiCallLog invalidSchemaLog = logs.stream()
        .filter(log -> "schema_invalid".equals(log.getStatus()))
        .findFirst()
        .orElseThrow();
    assertThat(invalidSchemaLog.getMaskedOutput()).containsKey("validationErrors");
    assertThat(invalidSchemaLog.getMaskedOutput()).doesNotContainKey("dsl");
    assertThat(logs.stream()
        .filter(log -> "succeeded".equals(log.getStatus()))
        .count()).isZero();
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
    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    return new LoginSession(session, UUID.fromString(body.path("org").path("id").asText()));
  }

  private CaptchaResponse captcha() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/captcha"))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(StandardCharsets.UTF_8), CaptchaResponse.class);
  }

  private record LoginSession(Cookie cookie, UUID orgId) {
  }
}
