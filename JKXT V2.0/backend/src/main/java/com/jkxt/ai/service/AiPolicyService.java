package com.jkxt.ai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.ai.api.AiDtos.NlPolicyParseRequest;
import com.jkxt.ai.api.AiDtos.NlPolicyParseResponse;
import com.jkxt.ai.api.AiDtos.NlPolicyRefineRequest;
import com.jkxt.ai.api.AiDtos.NlPolicyRefineResponse;
import com.jkxt.ai.api.AiDtos.PolicyDsl;
import com.jkxt.ai.api.AiDtos.ProviderStatus;
import com.jkxt.ai.api.AiDtos.ProviderStatusResponse;
import com.jkxt.ai.api.AiDtos.ProviderUsage;
import com.jkxt.ai.api.AiDtos.RecentAiCall;
import com.jkxt.ai.api.AiDtos.RuleDto;
import com.jkxt.ai.client.LlmClient;
import com.jkxt.ai.client.LlmClient.ChatRequest;
import com.jkxt.ai.client.LlmClient.ChatResponse;
import com.jkxt.ai.config.LlmProperties;
import com.jkxt.ai.domain.AiCallLog;
import com.jkxt.ai.repo.AiCallLogRepository;
import com.jkxt.common.tenant.TenantContext;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class AiPolicyService {

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
  };
  private static final String PROMPT_VERSION = "p8-policy-v1";
  private static final List<String> MODELS = List.of(
      "deepseek-v4-pro",
      "MiniMax-M2.7",
      "kimi-k2.6",
      "glm-5.1",
      "mimo-v2.5-pro",
      "gpt-5.4",
      "claude-opus-4-7",
      "claude-haiku-4-5-20251001"
  );

  private final LlmClient llmClient;
  private final LlmProperties properties;
  private final PiiMasker piiMasker;
  private final PolicyDslValidator validator;
  private final AiCallLogRepository callLogRepository;
  private final ObjectMapper objectMapper;

  public AiPolicyService(
      LlmClient llmClient,
      LlmProperties properties,
      PiiMasker piiMasker,
      PolicyDslValidator validator,
      AiCallLogRepository callLogRepository,
      ObjectMapper objectMapper
  ) {
    this.llmClient = llmClient;
    this.properties = properties;
    this.piiMasker = piiMasker;
    this.validator = validator;
    this.callLogRepository = callLogRepository;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public NlPolicyParseResponse parse(NlPolicyParseRequest request) {
    String rawText = requiredText(request.text());
    String maskedText = piiMasker.maskText(rawText);
    Map<String, Object> maskedInput = maskedInput(maskedText, request.policyIds());
    ChatResponse llm = llmClient.chat(new ChatRequest(
        "nl2dsl",
        request.provider(),
        request.model(),
        parseSystemPrompt(),
        parseUserPrompt(maskedText, request.policyIds()),
        maskedInput
    ));
    PolicyDsl fallbackDsl = fallbackDsl(maskedText, request.policyIds());
    if (!"succeeded".equals(llm.status()) || llm.content() == null || llm.content().isBlank()) {
      logCall("fallback", "nl2dsl", request.taskId(), llm, maskedInput, Map.of("dsl", fallbackDsl), rawText, llm.errorMessage());
      return new NlPolicyParseResponse(
          fallbackDsl,
          maskedText,
          llm.provider(),
          llm.model(),
          "fallback",
          true,
          "OR_TOOLS",
          List.of("LLM provider unavailable; returned deterministic safe DSL for solver-side review.")
      );
    }

    PolicyDsl dsl = null;
    List<String> errors;
    try {
      dsl = objectMapper.readValue(extractJsonObject(llm.content()), PolicyDsl.class);
      errors = validator.validate(dsl);
    } catch (Exception exception) {
      errors = List.of("LLM response is not valid JSON DSL: " + exception.getMessage());
    }
    if (!errors.isEmpty()) {
      logCall(
          "schema_invalid",
          "nl2dsl",
          request.taskId(),
          llm,
          maskedInput,
          Map.of("validationErrors", errors, "raw", piiMasker.maskText(llm.content())),
          rawText,
          String.join("; ", errors)
      );
      return new NlPolicyParseResponse(
          fallbackDsl,
          maskedText,
          llm.provider(),
          llm.model(),
          "fallback",
          true,
          "OR_TOOLS",
          errors
      );
    }

    logCall("succeeded", "nl2dsl", request.taskId(), llm, maskedInput, Map.of("dsl", dsl), rawText, null);
    return new NlPolicyParseResponse(dsl, maskedText, llm.provider(), llm.model(), "succeeded", llm.fallback(), "OR_TOOLS", List.of());
  }

  @Transactional
  public NlPolicyRefineResponse refine(NlPolicyRefineRequest request) {
    String rawText = requiredText(request.text());
    String maskedText = piiMasker.maskText(rawText);
    Map<String, Object> maskedInput = maskedInput(maskedText, request.policyIds());
    ChatResponse llm = llmClient.chat(new ChatRequest(
        "refine",
        request.provider(),
        request.model(),
        refineSystemPrompt(),
        refineUserPrompt(maskedText, request.policyIds()),
        maskedInput
    ));
    String refinedText = "请将策略拆成硬约束、软约束和人工复核项，并确认最终排考仍由 OR-Tools 求解器生成。";
    List<String> suggestions = new ArrayList<>(List.of(
        "硬约束用于不可违反的规则。",
        "软约束用于公平性和偏好优化。",
        "含个人信息的描述需要脱敏后再提交。"
    ));
    String status = "fallback";
    if ("succeeded".equals(llm.status()) && llm.content() != null && !llm.content().isBlank()) {
      try {
        JsonNode json = objectMapper.readTree(extractJsonObject(llm.content()));
        refinedText = piiMasker.maskText(json.path("refinedText").asText(refinedText));
        suggestions = readStringList(json.path("suggestions"));
        status = "succeeded";
      } catch (Exception exception) {
        status = "fallback";
      }
    }
    logCall(status, "refine", null, llm, maskedInput, Map.of("refinedText", refinedText, "suggestions", suggestions), rawText, null);
    return new NlPolicyRefineResponse(refinedText, suggestions, llm.provider(), llm.model(), status, !"succeeded".equals(status) || llm.fallback());
  }

  @Transactional(readOnly = true)
  public ProviderStatusResponse providers() {
    UUID orgId = TenantContext.getRequired();
    Instant monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
    List<AiCallLog> monthly = callLogRepository.findByOrgIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(orgId, monthStart);
    int success = (int) monthly.stream().filter(call -> "succeeded".equals(call.getStatus())).count();
    int failed = monthly.size() - success;
    long avgLatency = (long) monthly.stream()
        .filter(call -> call.getLatencyMs() != null)
        .mapToInt(AiCallLog::getLatencyMs)
        .average()
        .orElse(0);
    long totalTokens = monthly.stream()
        .mapToLong(call -> number(call.getTokenUsage().get("totalTokens")))
        .sum();
    double costCents = monthly.stream()
        .map(AiCallLog::getCostCents)
        .filter(value -> value != null)
        .map(BigDecimal::doubleValue)
        .reduce(0.0, Double::sum);
    ProviderUsage usage = new ProviderUsage(
        monthly.size(),
        properties.getMonthlyQuota(),
        Math.max(0, properties.getMonthlyQuota() - monthly.size()),
        success,
        failed,
        avgLatency,
        totalTokens,
        costCents
    );
    List<RecentAiCall> recent = callLogRepository.findTop5ByOrgIdOrderByCreatedAtDesc(orgId).stream()
        .map(call -> new RecentAiCall(call.getId(), call.getProvider(), call.getModel(), call.getPurpose(), call.getStatus(), call.getLatencyMs(), call.getCreatedAt()))
        .toList();
    return new ProviderStatusResponse(providerStatuses(), usage, recent);
  }

  private List<ProviderStatus> providerStatuses() {
    boolean credential = properties.hasCredential();
    String active = properties.getProvider();
    String host = endpointHost(properties.getBaseUrl());
    List<ProviderStatus> statuses = new ArrayList<>();
    statuses.add(new ProviderStatus("mock", "Mock Provider", "available", activeProvider(active, "mock"), true, "local", MODELS));
    statuses.add(new ProviderStatus("llm-token", "LLM Token Gateway", properties.isEnabled() && credential ? "available" : "not_configured", activeProvider(active, "llm-token", "gpt-agent"), credential, host, MODELS));
    statuses.add(new ProviderStatus("deepseek", "DeepSeek compatible", properties.isEnabled() && credential ? "available" : "not_configured", activeProvider(active, "deepseek"), credential, host, MODELS));
    statuses.add(new ProviderStatus("qwen", "Qwen compatible", properties.isEnabled() && credential ? "available" : "not_configured", activeProvider(active, "qwen"), credential, host, MODELS));
    statuses.add(new ProviderStatus("doubao", "Doubao compatible", properties.isEnabled() && credential ? "available" : "not_configured", activeProvider(active, "doubao"), credential, host, MODELS));
    statuses.add(new ProviderStatus("openai", "GPT compatible", properties.isEnabled() && credential ? "available" : "not_configured", activeProvider(active, "openai"), credential, host, MODELS));
    return statuses;
  }

  private void logCall(
      String status,
      String purpose,
      UUID taskId,
      ChatResponse llm,
      Map<String, Object> maskedInput,
      Map<String, Object> maskedOutput,
      String rawText,
      String errorMessage
  ) {
    AiCallLog log = new AiCallLog();
    log.setOrgId(TenantContext.getRequired());
    log.setExamTaskId(taskId);
    log.setProvider(llm.provider());
    log.setModel(llm.model());
    log.setPurpose(purpose);
    log.setPromptVersion(PROMPT_VERSION);
    log.setInputHash(sha256(rawText));
    log.setStatus(status);
    log.setLatencyMs(llm.latencyMs());
    log.setTokenUsage(llm.tokenUsage());
    log.setCostCents(llm.costCents());
    log.setMaskedInput(maskedInput);
    log.setMaskedOutput(maskedOutput);
    log.setRequestMeta(Map.of(
        "operation", purpose,
        "fallback", llm.fallback(),
        "solverAuthority", "OR_TOOLS"
    ));
    log.setResponseMeta(maskedOutput);
    log.setErrorMessage(truncate(errorMessage));
    callLogRepository.save(log);
  }

  private PolicyDsl fallbackDsl(String maskedText, List<String> policyIds) {
    List<String> ids = policyIds == null ? List.of() : policyIds;
    List<RuleDto> hard = new ArrayList<>();
    List<RuleDto> soft = new ArrayList<>();
    if (ids.isEmpty() || ids.contains("homeroom_avoid")) {
      hard.add(rule("H_HOMEROOM_AVOID", "teacher_avoid_class", "hard", Map.of("scope", "homeroom")));
    }
    if (ids.isEmpty() || ids.contains("same_grade_avoid")) {
      hard.add(rule("H_SAME_GRADE_AVOID", "same_grade_avoid", "hard", Map.of("scope", "grade")));
    }
    if (ids.contains("gender_mix")) {
      soft.add(rule("S_GENDER_MIX", "gender_mix", "soft", Map.of("target", "mixed")));
    }
    if (ids.contains("subject_off")) {
      soft.add(rule("S_SUBJECT_AVOID", "subject_avoid", "soft", Map.of("scope", "sameSubject")));
    }
    if (ids.isEmpty() || ids.contains("workload_balance")) {
      soft.add(rule("S_WORKLOAD_BALANCE", "workload_balance", "soft", Map.of("maxDelta", 2)));
    }
    if (ids.contains("cross_grade")) {
      soft.add(rule("S_CROSS_GRADE", "cross_grade_prefer", "soft", Map.of("prefer", true)));
    }
    if (!maskedText.isBlank()) {
      soft.add(rule("S_NL_MANUAL_REVIEW", "manual_note", "soft", Map.of("note", maskedText, "requiresHumanReview", true)));
    }
    return new PolicyDsl(
        "1.0",
        "OR_TOOLS",
        hard,
        soft,
        List.of("Deterministic fallback DSL generated after masking; solver remains authoritative."),
        List.of("Run CP-SAT and inspect conflictSet before accepting assignments."),
        0.55,
        true
    );
  }

  private RuleDto rule(String id, String type, String severity, Map<String, Object> params) {
    return new RuleDto(id, type, true, severity, params);
  }

  private Map<String, Object> maskedInput(String maskedText, List<String> policyIds) {
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("text", maskedText);
    input.put("policyIds", policyIds == null ? List.of() : policyIds);
    input.put("piiMasked", true);
    return input;
  }

  private String parseSystemPrompt() {
    return """
        You are a policy parser for a school exam invigilation scheduler.
        Convert masked natural language into JSON only. The final assignments are not decided by you.
        Return solverAuthority exactly OR_TOOLS. Use only allowed rule types.
        """;
  }

  private String parseUserPrompt(String maskedText, List<String> policyIds) {
    return """
        Allowed rule types: teacher_avoid_class, same_grade_avoid, gender_mix, subject_avoid,
        workload_balance, cross_grade_prefer, availability_avoid, capacity_guard, rest_gap, manual_note.
        Selected policy ids: %s
        Masked user policy:
        %s
        """.formatted(policyIds == null ? List.of() : policyIds, maskedText);
  }

  private String refineSystemPrompt() {
    return "Refine the user's masked invigilation policy into concise Chinese text and return JSON only.";
  }

  private String refineUserPrompt(String maskedText, List<String> policyIds) {
    return """
        Return {"refinedText":"...","suggestions":["..."]}.
        Selected policy ids: %s
        Masked user policy:
        %s
        """.formatted(policyIds == null ? List.of() : policyIds, maskedText);
  }

  private String extractJsonObject(String content) {
    int start = content.indexOf('{');
    int end = content.lastIndexOf('}');
    if (start < 0 || end < start) {
      throw new IllegalArgumentException("No JSON object found");
    }
    return content.substring(start, end + 1);
  }

  private List<String> readStringList(JsonNode node) {
    if (!node.isArray()) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    node.forEach(item -> {
      if (!item.asText("").isBlank()) {
        values.add(piiMasker.maskText(item.asText()));
      }
    });
    return values;
  }

  private String requiredText(String text) {
    if (text == null || text.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Policy text is required");
    }
    return text.trim();
  }

  private String sha256(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      return "";
    }
  }

  private String endpointHost(String baseUrl) {
    try {
      String effectiveBaseUrl = baseUrl == null || baseUrl.isBlank() ? LlmProperties.DEFAULT_BASE_URL : baseUrl;
      String host = URI.create(effectiveBaseUrl).getHost();
      return host == null || host.isBlank() ? "custom" : host;
    } catch (Exception exception) {
      return "custom";
    }
  }

  private boolean activeProvider(String active, String... aliases) {
    if (active == null) {
      return false;
    }
    for (String alias : aliases) {
      if (alias.equalsIgnoreCase(active)) {
        return true;
      }
    }
    return false;
  }

  private long number(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String text) {
      try {
        return Long.parseLong(text);
      } catch (NumberFormatException ignored) {
        return 0;
      }
    }
    return 0;
  }

  private String truncate(String value) {
    if (value == null) {
      return null;
    }
    return value.length() <= 500 ? value : value.substring(0, 500);
  }
}
