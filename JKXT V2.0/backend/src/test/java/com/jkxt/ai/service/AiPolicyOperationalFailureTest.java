package com.jkxt.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.ai.api.AiDtos.NlPolicyRefineRequest;
import com.jkxt.ai.api.AiDtos.NlPolicyParseRequest;
import com.jkxt.ai.client.LlmClient;
import com.jkxt.ai.config.LlmProperties;
import com.jkxt.ai.domain.AiCallLog;
import com.jkxt.ai.repo.AiCallLogRepository;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AiPolicyOperationalFailureTest {
  @AfterEach void clearTenant() { TenantContext.clear(); }

  @Test
  void normalRefineRejectsCannedSuccessAndPersistsFailedAudit() {
    TenantContext.set(UUID.randomUUID());
    LlmClient client = mock(LlmClient.class);
    AiCallAuditService audit = mock(AiCallAuditService.class);
    when(client.chat(any())).thenReturn(new LlmClient.ChatResponse("aliyun", "qwen-plus",
        "{\"refinedText\":\"canned\",\"suggestions\":[]}", 0, Map.of(), BigDecimal.ZERO, "succeeded", true, null));
    AiPolicyService service = new AiPolicyService(client, new LlmProperties(), new PiiMasker(),
        new PolicyDslValidator(), mock(AiCallLogRepository.class), audit, new ObjectMapper());
    assertThatThrownBy(() -> service.refine(new NlPolicyRefineRequest("请保持监考工作量均衡", null, null, null)))
        .isInstanceOf(ApiException.class);
    ArgumentCaptor<AiCallLog> log = ArgumentCaptor.forClass(AiCallLog.class);
    verify(audit).save(log.capture());
    assertThat(log.getValue().getStatus()).isEqualTo("failed");
  }

  @Test
  void workloadOnlySelectionRejectsInventedEnabledHomeroomRuleBeforeIssuingPolicyId() {
    TenantContext.set(UUID.randomUUID());
    LlmClient client = mock(LlmClient.class);
    AiCallAuditService audit = mock(AiCallAuditService.class);
    when(client.chat(any())).thenReturn(new LlmClient.ChatResponse("aliyun", "qwen-plus", """
        {"version":"1.0","solverAuthority":"OR_TOOLS",
         "hard":[{"id":"H1","type":"teacher_avoid_class","enabled":true,"severity":"hard","params":{}}],
         "soft":[{"id":"S1","type":"workload_balance","enabled":true,"severity":"soft","params":{}}],
         "explanations":["仅优化工作量均衡"],"repairSuggestions":[],"confidence":0.9,"needsReview":false}
        """, 1, Map.of(), BigDecimal.ZERO, "succeeded", false, null));
    AiPolicyService service = new AiPolicyService(client, new LlmProperties(), new PiiMasker(),
        new PolicyDslValidator(), mock(AiCallLogRepository.class), audit, new ObjectMapper());
    assertThatThrownBy(() -> service.parse(new NlPolicyParseRequest("请仅启用工作量均衡软约束，不启用班主任回避", List.of("workload_balance"), null, null, null)))
        .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("LLM_POLICY_SELECTION_MISMATCH"));
    ArgumentCaptor<AiCallLog> log = ArgumentCaptor.forClass(AiCallLog.class);
    verify(audit).save(log.capture());
    assertThat(log.getValue().getStatus()).isEqualTo("failed");
  }
}
