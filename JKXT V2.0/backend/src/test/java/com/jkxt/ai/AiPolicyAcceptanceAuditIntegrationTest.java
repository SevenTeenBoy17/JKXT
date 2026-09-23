package com.jkxt.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.ai.client.LlmClient;
import com.jkxt.ai.client.LlmClient.ChatRequest;
import com.jkxt.ai.client.LlmClient.ChatResponse;
import com.jkxt.ai.domain.AiCallLog;
import com.jkxt.ai.repo.AiCallLogRepository;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "jkxt.llm.enabled=true",
    "jkxt.llm.provider=llm-token",
    "jkxt.llm.model=claude-sonnet-4-6",
    "jkxt.llm.api-key=test-credential",
    "jkxt.llm.acceptance-mode=true"
})
class AiPolicyAcceptanceAuditIntegrationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private AiCallLogRepository callLogRepository;

  @MockBean
  private LlmClient llmClient;

  @Test
  void schemaInvalidParseFailsClosedAndPersistsSanitizedAudit() throws Exception {
    LoginSession login = login();
    UUID taskId = createTask(login.cookie());
    String provider = "aip-" + UUID.randomUUID();
    String fakeCredential = "sk-" + "testCredentialDoNotStore1234567890";
    when(llmClient.chat(any())).thenReturn(response(provider, "{\"rules\":[]}", "succeeded", false, null));

    mockMvc.perform(post("/api/v1/nl-policy/parse")
            .cookie(login.cookie())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "text", "按关怀型HR策略排监考，保持全部硬约束。临时凭据 " + fakeCredential,
                "policyIds", List.of("homeroom_avoid", "workload_balance"),
                "taskId", taskId,
                "provider", provider
            ))))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("LLM_RESPONSE_SCHEMA_INVALID"));

    List<AiCallLog> logs = callLogRepository.findByOrgIdAndExamTaskId(login.orgId(), taskId);
    assertThat(logs).hasSize(1);
    AiCallLog log = logs.getFirst();
    assertThat(log.getStatus()).isEqualTo("schema_invalid");
    assertThat(log.getPromptVersion()).isEqualTo("p8-policy-v2");
    assertThat(log.getMaskedOutput())
        .containsKeys("validationErrors", "responseSha256", "responseLength", "topLevelFields")
        .doesNotContainKeys("raw", "dsl");
    assertThat(log.getMaskedOutput().get("topLevelFields")).isEqualTo(List.of("rules"));
    assertThat(objectMapper.writeValueAsString(List.of(
        log.getMaskedInput(), log.getMaskedOutput(), log.getRequestMeta(), log.getResponseMeta(), log.getErrorMessage()
    )))
        .doesNotContain(fakeCredential)
        .contains("sk-***");

    ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
    verify(llmClient).chat(requestCaptor.capture());
    String systemPrompt = requestCaptor.getValue().systemPrompt();
    assertThat(systemPrompt)
        .contains("\"version\"", "\"solverAuthority\"", "\"hard\"", "\"soft\"",
            "\"explanations\"", "\"repairSuggestions\"", "\"confidence\"", "\"needsReview\""
        )
        .contains("Never output \"rules\", \"dsl\", \"assignments\"")
        .contains("subject_avoid and workload_balance must be soft")
        .contains("priorityWeights");
  }

  @Test
  void schemaInvalidRefineFailsClosedAndPersistsAudit() throws Exception {
    LoginSession login = login();
    String provider = "air-" + UUID.randomUUID();
    when(llmClient.chat(any())).thenReturn(response(
        provider,
        "{\"refinedText\":123,\"suggestions\":[]}",
        "succeeded",
        false,
        null
    ));

    mockMvc.perform(post("/api/v1/nl-policy/refine")
            .cookie(login.cookie())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "text", "班主任回避本班并平衡工作量。",
                "policyIds", List.of("homeroom_avoid", "workload_balance"),
                "provider", provider
            ))))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("LLM_RESPONSE_SCHEMA_INVALID"));

    List<AiCallLog> logs = callLogRepository
        .findTop10ByOrgIdAndProviderOrderByCreatedAtDesc(login.orgId(), provider);
    assertThat(logs).hasSize(1);
    assertThat(logs.getFirst().getPurpose()).isEqualTo("refine");
    assertThat(logs.getFirst().getStatus()).isEqualTo("schema_invalid");
    assertThat(logs.getFirst().getMaskedOutput()).doesNotContainKeys("raw", "refinedText");
  }

  @Test
  void acceptanceModeRejectsProseWrappedPolicyJson() throws Exception {
    LoginSession login = login();
    UUID taskId = createTask(login.cookie());
    String provider = "aiw-" + UUID.randomUUID();
    String validPolicyJson = """
        {
          "version":"1.0",
          "solverAuthority":"OR_TOOLS",
          "hard":[],
          "soft":[],
          "explanations":["最终分配由 OR-Tools 生成。"],
          "repairSuggestions":[],
          "confidence":0.9,
          "needsReview":false
        }
        """;
    when(llmClient.chat(any())).thenReturn(response(
        provider,
        "Here is the requested policy:\n" + validPolicyJson,
        "succeeded",
        false,
        null
    ));

    mockMvc.perform(post("/api/v1/nl-policy/parse")
            .cookie(login.cookie())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "text", "保持全部硬约束并平衡工作量。",
                "policyIds", List.of("workload_balance"),
                "taskId", taskId,
                "provider", provider
            ))))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("LLM_RESPONSE_SCHEMA_INVALID"));

    List<AiCallLog> logs = callLogRepository.findByOrgIdAndExamTaskId(login.orgId(), taskId);
    assertThat(logs).hasSize(1);
    assertThat(logs.getFirst().getStatus()).isEqualTo("schema_invalid");
    assertThat(logs.getFirst().getMaskedOutput()).doesNotContainKeys("raw", "dsl");
  }

  @Test
  void acceptanceModeRejectsConcatenatedPolicyJsonValues() throws Exception {
    LoginSession login = login();
    UUID taskId = createTask(login.cookie());
    String provider = "aic-" + UUID.randomUUID();
    String validPolicyJson = """
        {
          "version":"1.0",
          "solverAuthority":"OR_TOOLS",
          "hard":[],
          "soft":[],
          "explanations":["最终分配由 OR-Tools 生成。"],
          "repairSuggestions":[],
          "confidence":0.9,
          "needsReview":false
        }
        """;
    when(llmClient.chat(any())).thenReturn(response(
        provider,
        validPolicyJson + validPolicyJson,
        "succeeded",
        false,
        null
    ));

    mockMvc.perform(post("/api/v1/nl-policy/parse")
            .cookie(login.cookie())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "text", "保持全部硬约束并平衡工作量。",
                "policyIds", List.of("workload_balance"),
                "taskId", taskId,
                "provider", provider
            ))))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("LLM_RESPONSE_SCHEMA_INVALID"));

    List<AiCallLog> logs = callLogRepository.findByOrgIdAndExamTaskId(login.orgId(), taskId);
    assertThat(logs).hasSize(1);
    assertThat(logs.getFirst().getStatus()).isEqualTo("schema_invalid");
    assertThat(logs.getFirst().getMaskedOutput()).doesNotContainKeys("raw", "dsl");
  }

  @Test
  void upstreamAcceptanceFailurePersistsFailedAuditBeforeReturning502() throws Exception {
    LoginSession login = login();
    UUID taskId = createTask(login.cookie());
    String provider = "aiu-" + UUID.randomUUID();
    when(llmClient.chat(any())).thenReturn(response(
        provider,
        "",
        "failed",
        false,
        "LLM_UPSTREAM_HTTP_503"
    ));

    mockMvc.perform(post("/api/v1/nl-policy/parse")
            .cookie(login.cookie())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "text", "保持全部硬约束并平衡工作量。",
                "policyIds", List.of("workload_balance"),
                "taskId", taskId,
                "provider", provider
            ))))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("LLM_ACCEPTANCE_FAILED"));

    List<AiCallLog> logs = callLogRepository.findByOrgIdAndExamTaskId(login.orgId(), taskId);
    assertThat(logs).hasSize(1);
    assertThat(logs.getFirst().getStatus()).isEqualTo("failed");
    assertThat(logs.getFirst().getMaskedOutput())
        .containsEntry("errorCode", "LLM_UPSTREAM_HTTP_503")
        .doesNotContainKey("raw");
  }

  @Test
  void selectionMismatchFailsBeforeIssuingExecutablePolicyAndPersistsFailureAudit() throws Exception {
    LoginSession login = login();
    UUID taskId = createTask(login.cookie());
    String provider = "ais-" + UUID.randomUUID();
    when(llmClient.chat(any())).thenReturn(response(provider, """
        {"version":"1.0","solverAuthority":"OR_TOOLS",
         "hard":[{"id":"H1","type":"teacher_avoid_class","enabled":true,"severity":"hard","params":{}}],
         "soft":[{"id":"S1","type":"workload_balance","enabled":true,"severity":"soft","params":{}}],
         "explanations":["仅优化工作量均衡"],"repairSuggestions":[],"confidence":0.9,"needsReview":false}
        """, "succeeded", false, null));
    mockMvc.perform(post("/api/v1/nl-policy/parse").cookie(login.cookie())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("text", "只启用工作量均衡，不启用班主任回避",
                "policyIds", List.of("workload_balance"), "taskId", taskId, "provider", provider))))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("LLM_POLICY_SELECTION_MISMATCH"))
        .andExpect(jsonPath("$.policyId").doesNotExist());
    List<AiCallLog> logs = callLogRepository.findByOrgIdAndExamTaskId(login.orgId(), taskId);
    assertThat(logs).hasSize(1);
    assertThat(logs.getFirst().getStatus()).isEqualTo("failed");
    assertThat(logs.getFirst().getMaskedOutput()).containsKey("validationErrors").doesNotContainKey("dsl");
  }

  private ChatResponse response(
      String provider,
      String content,
      String status,
      boolean fallback,
      String errorMessage
  ) {
    return new ChatResponse(
        provider,
        "claude-sonnet-4-6",
        content,
        25,
        Map.of("totalTokens", 12),
        BigDecimal.ZERO,
        status,
        fallback,
        errorMessage
    );
  }

  private UUID createTask(Cookie session) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/exam-tasks")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "name", "Acceptance audit " + UUID.randomUUID(),
                "source", "ai_policy"
            ))))
        .andExpect(status().isOk())
        .andReturn();
    return UUID.fromString(objectMapper.readTree(
        result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("id").asText());
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
    return objectMapper.readValue(
        result.getResponse().getContentAsString(StandardCharsets.UTF_8),
        CaptchaResponse.class
    );
  }

  private record LoginSession(Cookie cookie, UUID orgId) {
  }
}
