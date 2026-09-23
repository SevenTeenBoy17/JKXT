package com.jkxt.ai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonParser;
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
import com.jkxt.ai.api.AiDtos.ModelStatus;
import com.jkxt.ai.api.AiDtos.RuleDto;
import com.jkxt.ai.client.LlmClient;
import com.jkxt.ai.client.LlmClient.ChatRequest;
import com.jkxt.ai.client.LlmClient.ChatResponse;
import com.jkxt.ai.config.LlmProperties;
import com.jkxt.ai.config.AiModelCatalog;
import com.jkxt.ai.domain.AiCallLog;
import com.jkxt.ai.repo.AiCallLogRepository;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiException;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class AiPolicyService {

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
  };
  private static final String PROMPT_VERSION = "p8-policy-v2";
  private static final Set<String> POLICY_DSL_FIELDS = Set.of(
      "version", "solverAuthority", "hard", "soft", "explanations",
      "repairSuggestions", "confidence", "needsReview"
  );
  private static final Set<String> POLICY_RULE_FIELDS = Set.of(
      "id", "type", "enabled", "severity", "params"
  );
  private static final Set<String> REFINE_FIELDS = Set.of("refinedText", "suggestions");

  private final LlmClient llmClient;
  private final LlmProperties properties;
  private final PiiMasker piiMasker;
  private final PolicyDslValidator validator;
  private final AiCallLogRepository callLogRepository;
  private final AiCallAuditService callAuditService;
  private final ObjectMapper objectMapper;

  public AiPolicyService(
      LlmClient llmClient,
      LlmProperties properties,
      PiiMasker piiMasker,
      PolicyDslValidator validator,
      AiCallLogRepository callLogRepository,
      AiCallAuditService callAuditService,
      ObjectMapper objectMapper
  ) {
    this.llmClient = llmClient;
    this.properties = properties;
    this.piiMasker = piiMasker;
    this.validator = validator;
    this.callLogRepository = callLogRepository;
    this.callAuditService = callAuditService;
    this.objectMapper = objectMapper;
  }

  public NlPolicyParseResponse parse(NlPolicyParseRequest request) {
    if (request.taskId() != null && !callLogRepository.taskExistsInOrganization(TenantContext.getRequired(), request.taskId())) {
      throw new ApiException(HttpStatus.NOT_FOUND, "EXAM_TASK_NOT_FOUND", "考试任务不存在");
    }
    String rawText = requiredText(request.text());
    rejectUnsafePolicyInput(rawText);
    String maskedText = piiMasker.maskText(rawText);
    Map<String, Object> maskedInput = maskedInput(maskedText, request.policyIds());
    ChatResponse llm = normalizeResponse(llmClient.chat(new ChatRequest(
        "nl2dsl",
        request.provider(),
        request.model(),
        parseSystemPrompt(),
        parseUserPrompt(maskedText, request.policyIds()),
        maskedInput
    )), request.provider(), request.model());
    requireAcceptanceResponse(llm, "nl2dsl", request.taskId(), maskedInput, rawText);
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
          List.of("AI 服务暂不可用，已返回确定性的安全策略草案，供求解器侧复核。"),
          null
      );
    }

    PolicyDsl dsl = null;
    List<String> errors = new ArrayList<>();
    try {
      JsonNode root = readExactJsonObject(llm.content());
      errors.addAll(validatePolicyJsonShape(root));
      dsl = objectMapper.treeToValue(root, PolicyDsl.class);
      errors.addAll(validator.validate(dsl));
    } catch (Exception exception) {
      errors = List.of("LLM response is not a valid PolicyDsl JSON object.");
    }
    if (!errors.isEmpty()) {
      logCall(
          "schema_invalid",
          "nl2dsl",
          request.taskId(),
          llm,
          maskedInput,
          // Do not store an invalid raw model response. It may contain sensitive text or a credential
          // copied from an adversarial prompt. The bounded shape/hash summary is sufficient for audit.
          invalidResponseEvidence(llm.content(), errors),
          rawText,
          String.join("; ", errors)
      );
      if (properties.isAcceptanceMode() || !properties.isAllowTestMocks()) {
        throw new ApiException(
            HttpStatus.BAD_GATEWAY,
            "LLM_RESPONSE_SCHEMA_INVALID",
            "真实大模型响应未通过策略结构校验"
        );
      }
      return new NlPolicyParseResponse(
          fallbackDsl,
          maskedText,
          llm.provider(),
          llm.model(),
          "fallback",
          true,
          "OR_TOOLS",
          errors,
          null
      );
    }

    if (!llm.fallback()) {
      List<String> selectionErrors = validator.validateSelection(dsl, request.policyIds());
      if (!selectionErrors.isEmpty()) {
        logCall("failed", "nl2dsl", request.taskId(), llm, maskedInput,
            invalidResponseEvidence(llm.content(), selectionErrors), rawText, String.join("; ", selectionErrors));
        throw new ApiException(HttpStatus.BAD_GATEWAY, "LLM_POLICY_SELECTION_MISMATCH",
            "模型策略与勾选项目不一致，未生成可执行策略；请核对文字和勾选项目后重新解析。");
      }
    }

    // No real LLM wired (provider=mock / disabled / no credential → fallback=true): the parsed DSL is a
    // canned stub that ignored the user's text. Return the deterministic SAFE draft instead and say so
    // honestly — otherwise a "请关闭同年级回避" request silently became a HARD same-grade rule and could flip
    // a feasible single-grade schedule to INFEASIBLE. The safe draft keeps 同年级回避=SOFT (== one-click
    // SchedulePolicy.defaults(), never infeasible-inducing); only homeroom avoidance stays HARD.
    if (llm.fallback()) {
      logCall("fallback", "nl2dsl", request.taskId(), llm, maskedInput, Map.of("dsl", fallbackDsl), rawText, null);
      List<String> warnings = new ArrayList<>();
      warnings.add("AI 服务暂未接入，已返回确定性的安全策略草案（班主任不监本班=硬约束，同年级回避=软偏好），"
          + "未对自然语言做语义解析；请人工复核策略与求解器冲突集后再采纳。");
      warnings.addAll(enforcementWarnings(fallbackDsl));
      return new NlPolicyParseResponse(fallbackDsl, maskedText, llm.provider(), llm.model(), "fallback", true, "OR_TOOLS", warnings, null);
    }
    UUID policyId = logCall("succeeded", "nl2dsl", request.taskId(), llm, maskedInput, Map.of("dsl", dsl), rawText, null);
    return new NlPolicyParseResponse(dsl, maskedText, llm.provider(), llm.model(), "succeeded", llm.fallback(), "OR_TOOLS", enforcementWarnings(dsl), policyId);
  }

  /**
   * 如实标注 DSL 中"生成了但求解器并不执行"的策略。当前求解器读取班主任回避、同年级回避、
   * 任教学科回避，并始终执行工作量均衡；其余规则仅作参考，不能让用户误以为已纳入排考约束。
   */
  private List<String> enforcementWarnings(PolicyDsl dsl) {
    java.util.Set<String> enforced = java.util.Set.of(
        "teacher_avoid_class", "same_grade_avoid", "subject_avoid", "workload_balance");
    java.util.LinkedHashSet<String> advisory = new java.util.LinkedHashSet<>();
    for (List<RuleDto> group : List.of(
        dsl.hard() == null ? List.<RuleDto>of() : dsl.hard(),
        dsl.soft() == null ? List.<RuleDto>of() : dsl.soft())) {
      for (RuleDto r : group) {
        if (r != null && Boolean.TRUE.equals(r.enabled()) && r.type() != null && !enforced.contains(r.type())) {
          advisory.add(r.type());
        }
      }
    }
    if (advisory.isEmpty()) {
      return List.of();
    }
    return List.of("以下策略仅作参考、暂未纳入自动排考约束：" + String.join("、", advisory)
        + "。工作量均衡始终优化；回避规则仅按已确认的启用项目执行。");
  }

  public NlPolicyRefineResponse refine(NlPolicyRefineRequest request) {
    String rawText = requiredText(request.text());
    rejectUnsafePolicyInput(rawText);
    String maskedText = piiMasker.maskText(rawText);
    Map<String, Object> maskedInput = maskedInput(maskedText, request.policyIds());
    ChatResponse llm = normalizeResponse(llmClient.chat(new ChatRequest(
        "refine",
        request.provider(),
        request.model(),
        refineSystemPrompt(),
        refineUserPrompt(maskedText, request.policyIds()),
        maskedInput
    )), request.provider(), request.model());
    requireAcceptanceResponse(llm, "refine", null, maskedInput, rawText);
    String refinedText = "请将策略拆成硬约束、软约束和人工复核项，并确认最终排考仍由 OR-Tools 求解器生成。";
    List<String> suggestions = new ArrayList<>(List.of(
        "硬约束用于不可违反的规则。",
        "软约束用于公平性和偏好优化。",
        "含个人信息的描述需要脱敏后再提交。"
    ));
    String status = "fallback";
    if ("succeeded".equals(llm.status()) && llm.content() != null && !llm.content().isBlank()) {
      try {
        JsonNode json = readExactJsonObject(llm.content());
        validateRefineJsonShape(json);
        refinedText = piiMasker.maskText(json.path("refinedText").asText());
        suggestions = readStringList(json.path("suggestions"));
        status = llm.fallback() ? "fallback" : "succeeded";
      } catch (Exception exception) {
        List<String> errors = List.of("LLM response is not a valid refine JSON object.");
        logCall(
            "schema_invalid",
            "refine",
            null,
            llm,
            maskedInput,
            invalidResponseEvidence(llm.content(), errors),
            rawText,
            errors.getFirst()
        );
        if (properties.isAcceptanceMode() || !properties.isAllowTestMocks()) {
          throw new ApiException(
              HttpStatus.BAD_GATEWAY,
              "LLM_RESPONSE_SCHEMA_INVALID",
              "真实大模型响应未通过优化建议结构校验"
          );
        }
        return new NlPolicyRefineResponse(
            refinedText,
            suggestions,
            llm.provider(),
            llm.model(),
            "fallback",
            true
        );
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
        .mapToInt(call -> nonNegativeLatency(call.getLatencyMs()))
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
        .map(call -> new RecentAiCall(call.getId(), call.getProvider(), call.getModel(), call.getPurpose(), call.getStatus(), nonNegativeLatency(call.getLatencyMs()), call.getCreatedAt()))
        .toList();
    return new ProviderStatusResponse(providerStatuses(), usage, recent, modelStatuses(monthly));
  }

  private List<ProviderStatus> providerStatuses() {
    boolean credential = properties.hasCredential();
    String active = properties.getProvider();
    String host = endpointHost(properties.getBaseUrl());
    List<ProviderStatus> statuses = new ArrayList<>();
    if (properties.isAllowTestMocks() && !properties.isAcceptanceMode()) {
      statuses.add(new ProviderStatus("mock", "测试专用模拟引擎", "test_only", activeProvider(active, "mock"), true, "local", List.of(properties.getModel())));
    }
    boolean configured = properties.isEnabled() && credential && !properties.getBaseUrl().isBlank();
    statuses.add(new ProviderStatus("aliyun", "阿里云百炼 · 北京", configured ? "not_tested" : "not_configured",
        activeProvider(active, "aliyun"), configured, host, AiModelCatalog.policyModelIds()));
    return statuses;
  }

  private List<ModelStatus> modelStatuses(List<AiCallLog> recentCalls) {
    boolean configured = properties.isEnabled() && properties.hasCredential() && !properties.getBaseUrl().isBlank();
    Map<String, AiCallLog> latestByModel = new LinkedHashMap<>();
    for (AiCallLog call : recentCalls) {
      if (properties.getProvider().equals(call.getProvider())) latestByModel.putIfAbsent(call.getModel(), call);
    }
    return AiModelCatalog.models().stream().map(model -> {
      AiCallLog latest = latestByModel.get(model.id());
      boolean genuine = latest != null && "succeeded".equals(latest.getStatus())
          && !Boolean.TRUE.equals(latest.getRequestMeta().get("fallback"));
      String status = !configured ? "not_configured" : latest == null ? "not_tested"
          : genuine ? "verified" : "failed";
      return new ModelStatus(model.id(), model.capability(), model.policyCompatible(), model.policyCompatible() ? null
          : "该模型已收录目录；尚未完成监考策略 DSL 专项验证，不用于自动解析。专用视觉、翻译等能力须使用对应入口。", model.jsonMode(), model.thinking(),
          status, configured, genuine ? latest.getCreatedAt() : null,
          latest == null || genuine ? null : piiMasker.maskText(latest.getErrorMessage()));
    }).toList();
  }

  private UUID logCall(
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
    log.setLatencyMs(nonNegativeLatency(llm.latencyMs()));
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
    log.setErrorMessage(piiMasker.maskText(truncate(errorMessage)));
    callAuditService.save(log);
    return log.getId();
  }

  private PolicyDsl fallbackDsl(String maskedText, List<String> policyIds) {
    List<String> ids = policyIds == null ? List.of() : policyIds;
    List<RuleDto> hard = new ArrayList<>();
    List<RuleDto> soft = new ArrayList<>();
    if (ids.isEmpty() || ids.contains("homeroom_avoid")) {
      hard.add(rule("H_HOMEROOM_AVOID", "teacher_avoid_class", "hard", Map.of("scope", "homeroom")));
    }
    if (ids.isEmpty() || ids.contains("same_grade_avoid")) {
      // 同年级回避默认 SOFT(软偏好)，与一键流 SchedulePolicy.defaults() 一致：尽量回避但绝不致无解。
      // 旧实现这里写 hard，会让确定性草案把可行的单年级排考翻转为 INFEASIBLE（与用户意图相反）。
      soft.add(rule("S_SAME_GRADE_AVOID", "same_grade_avoid", "soft", Map.of("scope", "grade")));
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
        List.of("已在脱敏后生成确定性的兜底策略草案；最终监考分配仍以求解器为准。"),
        List.of("请先运行 CP-SAT 求解并检查冲突集（conflictSet），再决定是否采纳分配结果。"),
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
        You are a strict JSON policy parser for a school exam invigilation scheduler. Translate
        policy intent only; OR-Tools remains the sole assignment authority.

        Return exactly one JSON object and no markdown, prose, wrapper, or alternative keys. Every
        top-level field below is required, even when an array is empty:
        {"version":"1.0","solverAuthority":"OR_TOOLS","hard":[],"soft":[],"explanations":["Chinese explanation"],"repairSuggestions":[],"confidence":0.0,"needsReview":true}

        Each hard/soft item contains exactly id, type, enabled, severity, params. id is non-empty
        and unique; enabled is boolean; params is an object. hard items use severity "hard" and may
        only use teacher_avoid_class or same_grade_avoid. soft items use severity "soft" or
        "optimize". teacher_avoid_class must be hard. subject_avoid and workload_balance must be soft. confidence is a number from 0
        through 1. explanations must contain at least one Chinese string; repairSuggestions is an
        array; needsReview is boolean.

        Allowed rule types only: teacher_avoid_class, same_grade_avoid, gender_mix, subject_avoid,
        workload_balance, cross_grade_prefer, availability_avoid, capacity_guard, rest_gap,
        manual_note.

        Selected policy id mapping: homeroom_avoid -> teacher_avoid_class; same_grade_avoid ->
        same_grade_avoid; subject_off -> subject_avoid; workload_balance -> workload_balance;
        gender_mix -> gender_mix; cross_grade -> cross_grade_prefer. same_grade_avoid is soft unless
        the user explicitly demands it as a hard rule.

        Selected policy ids are the authoritative set of optional rules. Include each selected
        mapped type exactly once and enabled=true. Never enable an unselected rule, invent default
        homeroom or grade avoidance, or duplicate a rule across groups. An omitted optional avoidance
        rule is OFF, never an implicit default. For workload_balance-only input, hard MUST be empty
        and soft MUST contain only workload_balance (plus explicitly requested advisory manual_note
        if needed). Explanations must describe the actual enabled rules. If prose conflicts with
        selection, require human review rather than silently changing the selected rules. Workload
        balancing is always optimized by the solver, with no promise of a fixed workload-difference bound.

        Never output "rules", "dsl", "assignments", or unknown top-level fields. Never invent
        teacher assignments. Never weaken leave, active-status, duty-window, room-capacity,
        minimum-staffing, time-conflict, qualification, or other solver hard constraints. When the
        input says to preserve all hard constraints, explain that these immutable constraints remain
        enforced by OR-Tools; do not invent DSL rule types for them.

        A named persona or strategy is a soft-priority profile. Add one manual_note soft rule whose
        params include strategyProfile, priorityWeights with numeric fairness, care, stability,
        efficiency, reserve values from 0 through 5, and requiresHumanReview=true. Make named
        profiles materially different in those weights and Chinese explanations. These weights are
        advisory unless represented by a supported enforced rule and never relax hard constraints.
        """;
  }

  private String parseUserPrompt(String maskedText, List<String> policyIds) {
    return """
        Selected policy ids: %s
        Masked user policy:
        %s
        """.formatted(policyIds == null ? List.of() : policyIds, maskedText);
  }

  private String refineSystemPrompt() {
    return """
        Refine the user's masked invigilation policy into concise Chinese text. Return exactly one
        JSON object with exactly two fields: {"refinedText":"...","suggestions":["..."]}.
        refinedText must be non-blank Chinese text and every suggestions item must be non-blank
        Chinese text. Do not return markdown, wrappers, assignments, or extra fields. OR-Tools
        remains the sole assignment authority and immutable school hard constraints cannot be
        weakened.
        """;
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
    if (content == null) {
      throw new IllegalArgumentException("No JSON object found");
    }
    String normalized = content.trim();
    int start = normalized.indexOf('{');
    int end = normalized.lastIndexOf('}');
    if (start < 0 || end < start) {
      throw new IllegalArgumentException("No JSON object found");
    }
    if (properties.isAcceptanceMode() && (start != 0 || end != normalized.length() - 1)) {
      throw new IllegalArgumentException("Acceptance response must contain exactly one JSON object");
    }
    return normalized.substring(start, end + 1);
  }

  private JsonNode readExactJsonObject(String content) throws Exception {
    String json = extractJsonObject(content);
    try (JsonParser parser = objectMapper.createParser(json)) {
      JsonNode root = objectMapper.readTree(parser);
      if (root == null || parser.nextToken() != null) {
        throw new IllegalArgumentException("Response must contain exactly one JSON value");
      }
      return root;
    }
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

  private List<String> validatePolicyJsonShape(JsonNode root) {
    List<String> errors = new ArrayList<>();
    if (root == null || !root.isObject()) {
      return List.of("PolicyDsl root must be a JSON object.");
    }
    Set<String> actualFields = jsonFieldNames(root);
    if (!POLICY_DSL_FIELDS.equals(actualFields)) {
      errors.add("PolicyDsl top-level fields must be exactly: "
          + String.join(", ", new java.util.TreeSet<>(POLICY_DSL_FIELDS)) + ".");
    }
    validateRuleArrayShape("hard", root.path("hard"), errors);
    validateRuleArrayShape("soft", root.path("soft"), errors);
    return errors;
  }

  private void validateRuleArrayShape(String group, JsonNode node, List<String> errors) {
    if (!node.isArray()) {
      return;
    }
    for (int index = 0; index < node.size(); index++) {
      JsonNode rule = node.path(index);
      if (!rule.isObject() || !POLICY_RULE_FIELDS.equals(jsonFieldNames(rule))) {
        errors.add(group + "[" + index + "] fields must be exactly: "
            + String.join(", ", new java.util.TreeSet<>(POLICY_RULE_FIELDS)) + ".");
      }
    }
  }

  private void validateRefineJsonShape(JsonNode root) {
    if (root == null || !root.isObject() || !REFINE_FIELDS.equals(jsonFieldNames(root))) {
      throw new IllegalArgumentException("Unexpected refine JSON shape");
    }
    if (!root.path("refinedText").isTextual() || root.path("refinedText").asText().isBlank()) {
      throw new IllegalArgumentException("refinedText must be non-blank text");
    }
    JsonNode suggestions = root.path("suggestions");
    if (!suggestions.isArray()) {
      throw new IllegalArgumentException("suggestions must be an array");
    }
    for (JsonNode item : suggestions) {
      if (!item.isTextual() || item.asText().isBlank()) {
        throw new IllegalArgumentException("suggestions must contain non-blank text only");
      }
    }
  }

  private Set<String> jsonFieldNames(JsonNode node) {
    Set<String> names = new java.util.LinkedHashSet<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private Map<String, Object> invalidResponseEvidence(String content, List<String> errors) {
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("validationErrors", errors.stream()
        .limit(20)
        .map(value -> piiMasker.maskText(truncate(value)))
        .toList());
    evidence.put("responseSha256", sha256(content));
    evidence.put("responseLength", content == null ? 0 : content.length());
    try {
      JsonNode root = readExactJsonObject(content);
      if (root.isObject()) {
        evidence.put("topLevelFields", jsonFieldNames(root).stream()
            .limit(32)
            .map(value -> piiMasker.maskText(truncate(value)))
            .sorted()
            .toList());
      }
    } catch (Exception ignored) {
      evidence.put("topLevelFields", List.of());
    }
    return evidence;
  }

  private Map<String, Object> failedResponseEvidence(ChatResponse response) {
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("upstreamStatus", response.status());
    evidence.put("fallback", response.fallback());
    evidence.put("errorCode", piiMasker.maskText(truncate(response.errorMessage())));
    evidence.put("responseSha256", sha256(response.content()));
    evidence.put("responseLength", response.content() == null ? 0 : response.content().length());
    return evidence;
  }

  private ChatResponse normalizeResponse(ChatResponse response, String requestedProvider, String requestedModel) {
    if (response != null) {
      return response;
    }
    return new ChatResponse(
        nonBlank(requestedProvider, properties.getProvider()),
        nonBlank(requestedModel, properties.getModel()),
        "",
        0,
        Map.of(),
        BigDecimal.ZERO,
        "failed",
        !properties.isAcceptanceMode(),
        "LLM_RESPONSE_NULL"
    );
  }

  /**
   * Operational and acceptance calls both require real upstream output. Only explicitly opted-in
   * test fixtures may exercise local draft behavior; configuration failure is never AI success.
   */
  private void requireAcceptanceResponse(
      ChatResponse response,
      String purpose,
      UUID taskId,
      Map<String, Object> maskedInput,
      String rawText
  ) {
    if (!properties.isAcceptanceMode() && properties.isAllowTestMocks()) {
      return;
    }
    boolean genuineSuccess = "succeeded".equals(response.status())
        && !response.fallback()
        && response.content() != null
        && !response.content().isBlank();
    if (!genuineSuccess) {
      logCall(
          "failed",
          purpose,
          taskId,
          response,
          maskedInput,
          failedResponseEvidence(response),
          rawText,
          response.errorMessage()
      );
      String clientCode = response.errorMessage() != null && response.errorMessage().matches("LLM_[A-Z0-9_]{1,80}")
          ? response.errorMessage() : "LLM_UPSTREAM_FAILED";
      HttpStatus status = switch (clientCode) {
        case "LLM_NOT_CONFIGURED", "LLM_ENDPOINT_INVALID" -> HttpStatus.SERVICE_UNAVAILABLE;
        case "LLM_MODEL_NOT_ALLOWED", "LLM_MODEL_CAPABILITY_MISMATCH", "LLM_REQUEST_TOO_LARGE", "LLM_REQUEST_INVALID" -> HttpStatus.UNPROCESSABLE_ENTITY;
        case "LLM_PROVIDER_NOT_ALLOWED" -> HttpStatus.BAD_REQUEST;
        default -> HttpStatus.BAD_GATEWAY;
      };
      throw new ApiException(
          properties.isAcceptanceMode() ? HttpStatus.BAD_GATEWAY : status,
          properties.isAcceptanceMode() ? "LLM_ACCEPTANCE_FAILED" : clientCode,
          "大模型服务未完成真实调用，请检查配置或稍后重试；未返回模拟结果"
      );
    }
  }

  private String nonBlank(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  /**
   * Rejects requests whose semantics cannot safely be delegated to a probabilistic model. This
   * gate intentionally runs before masking, prompt construction, and {@link LlmClient#chat} so a
   * sensitive-data request or an instruction to relax school safeguards never leaves the service.
   *
   * <p>The rules combine independent semantic signals rather than matching fixture ids or complete
   * sentences. Explicitly protective phrases are removed before looking for an unsafe positive
   * action, which keeps requests such as "病假教师绝不能排" on the normal parsing path.</p>
   */
  private void rejectUnsafePolicyInput(String rawText) {
    PolicyInputViolation violation = classifyPolicyInput(rawText);
    if (violation != null) {
      throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, violation.code(), violation.message());
    }
  }

  private PolicyInputViolation classifyPolicyInput(String rawText) {
    String compact = compactPolicyText(rawText);
    if (isPolicyOverrideInjection(compact)) {
      return new PolicyInputViolation(
          "POLICY_OVERRIDE_INJECTION",
          "请求试图绕过学校规则或既有约束，已在调用大模型前拒绝"
      );
    }
    if (usesUnsupportedSensitiveData(compact)) {
      return new PolicyInputViolation(
          "UNSUPPORTED_OR_SENSITIVE_INPUT",
          "监考策略不得读取或使用实时生物特征、医疗诊断等敏感数据"
      );
    }
    if (relaxesHardConstraint(compact)) {
      return new PolicyInputViolation(
          "HARD_CONSTRAINT_RELAXATION_FORBIDDEN",
          "请假可用性、最低监考人数等硬约束不得通过自然语言策略放宽"
      );
    }
    if (containsContradictoryRequirements(compact)) {
      return new PolicyInputViolation(
          "CONTRADICTORY_REQUIREMENTS",
          "策略同时要求全员任务相等且仅由少数人承担，两个目标无法同时成立"
      );
    }
    if (containsAmbiguousSoftGoal(compact)) {
      return new PolicyInputViolation(
          "AMBIGUOUS_SOFT_GOAL",
          "软目标过于模糊，请补充可衡量的对象、优先级或上限后重试"
      );
    }
    return null;
  }

  private boolean isPolicyOverrideInjection(String compact) {
    String positiveDirective = removePhrases(
        compact,
        "不要忽略", "不得忽略", "不能忽略", "禁止忽略", "严禁绕过",
        "donotignore", "mustnotignore", "neverignore", "donotbypass", "mustnotbypass"
    );
    boolean bypassAction = containsAny(
        positiveDirective,
        "忽略", "无视", "绕过", "跳过", "覆盖", "override", "bypass", "ignore", "disregard", "forget"
    );
    boolean protectedAuthority = containsAny(
        positiveDirective,
        "学校规则", "校规", "考务制度", "考务约束", "之前所有", "前面规则", "先前规则",
        "系统提示", "系统指令", "安全规则", "全部约束", "所有约束", "schoolrule", "schoolpolicy",
        "previousinstruction", "priorinstruction", "systemprompt", "guardrail", "allconstraint"
    );
    return bypassAction && protectedAuthority;
  }

  private boolean usesUnsupportedSensitiveData(String compact) {
    String positiveDirective = removePhrases(
        compact,
        "不得读取", "禁止读取", "不要读取", "无需读取", "不应读取", "严禁读取",
        "不得采集", "禁止采集", "不要采集", "不采集", "不得使用", "禁止使用", "不使用",
        "donotread", "mustnotread", "shouldnotread", "neverread", "withoutreading",
        "donotcollect", "mustnotcollect", "nevercollect", "donotuse", "mustnotuse", "mustnotbeused"
    );
    boolean sensitiveSource = containsAny(
        positiveDirective,
        "实时心率", "心率", "血压", "医疗诊断", "病历", "诊疗记录", "健康档案", "生物特征",
        "heartrate", "bloodpressure", "medicaldiagnosis", "medicalrecord", "healthrecord", "biometricdata"
    );
    boolean activeAccess = containsAny(
        positiveDirective,
        "直接读取", "自动读取", "读取", "获取", "采集", "接入", "上传", "抓取", "监测", "使用",
        "read", "access", "collect", "fetch", "upload", "monitor", "use"
    );
    boolean dataDrivenDecision = containsAny(positiveDirective, "决定监考", "决定排班", "自动决定", "basedon")
        || (containsAny(positiveDirective, "根据", "依据") && containsAny(positiveDirective, "决定", "安排", "排班"));
    return sensitiveSource && (activeAccess || dataDrivenDecision);
  }

  private boolean relaxesHardConstraint(String compact) {
    String positiveAssignment = removePhrases(
        compact,
        "绝不能安排", "绝不能排", "不得安排", "禁止安排", "不可安排", "不能安排", "不应安排",
        "不得排", "禁止排", "不可排", "不能排", "不安排", "禁排", "排除",
        "mustnotassign", "neverassign", "donotassign", "shouldnotassign", "exclude"
    );
    boolean unavailablePerson = containsAny(
        positiveAssignment,
        "请假", "休假", "病假", "生病", "不可用", "停职", "离职", "已离岗", "onleave", "sickleave",
        "unavailable", "suspended", "departed"
    );
    boolean assignAnyway = containsAny(
        positiveAssignment,
        "可以给", "允许给", "仍然排", "照样排", "强制排", "安排监考", "排一场", "纳入排班",
        "assign", "allow", "includeonroster"
    );

    String positiveRelaxation = removePhrases(
        compact,
        "不得减少", "禁止减少", "不能减少", "不可减少", "不得降低", "禁止降低", "不能降低",
        "mustnotreduce", "donotreduce", "neverreduce", "mustnotrelax", "donotrelax"
    );
    boolean staffingOrHardRule = containsAny(
        positiveRelaxation,
        "监考人数", "最低人数", "人员配额", "每个考场", "硬约束", "硬规则", "安全规则", "可用性约束",
        "proctorcount", "invigilatorcount", "minimumstaff", "staffinglevel", "hardconstraint", "mandatoryrule"
    );
    boolean relaxationAction = containsAny(
        positiveRelaxation,
        "减少", "降低", "缩减", "少配", "放宽", "取消", "违反", "reduce", "lower", "cut", "relax", "violate"
    );
    return (unavailablePerson && assignAnyway) || (staffingOrHardRule && relaxationAction);
  }

  private boolean containsContradictoryRequirements(String compact) {
    boolean everyone = containsAny(
        compact,
        "每人", "每位", "人人", "所有老师", "所有教师", "全员", "everyone", "everyteacher", "allteachers"
    );
    boolean exactlyEqual = containsAny(
        compact,
        "完全相等", "完全相同", "一样多", "均等", "相同次数", "次数相同",
        "exactlyequal", "equalnumber", "sameamount"
    );
    boolean smallSubset = containsAny(
        compact,
        "少数人", "少数老师", "少数教师", "少量老师", "少量教师", "小部分", "onlyafew", "fewteachers", "limitedsubset"
    );
    boolean exclusiveOrAbsolute = containsAny(
        compact,
        "只让", "仅让", "只安排", "仅安排", "两个要求都必须", "两项必须", "同时满足", "一起满足",
        "only", "solely", "bothmust", "simultaneously"
    );
    return everyone && exactlyEqual && smallSubset && exclusiveOrAbsolute;
  }

  private boolean containsAmbiguousSoftGoal(String compact) {
    boolean group = containsAny(compact, "大家", "所有人", "每个人", "全体", "everyone", "everybody", "allstaff");
    boolean comfort = containsAny(
        compact,
        "舒服", "舒适", "轻松", "照顾", "关照", "好一点", "合理一点", "comfortable", "easyforeveryone", "takecare"
    );
    boolean vagueDegree = containsAny(compact, "尽量", "一下", "一点", "一些", "更舒服", "tryto", "somehow", "asmuchaspossible");
    boolean measurableTarget = compact.chars().anyMatch(Character::isDigit)
        || containsAny(
            compact,
            "最多", "最少", "不超过", "至少", "每天", "每场", "次数", "小时", "分钟", "间隔", "权重",
            "班主任", "同年级", "学科", "候补", "请假", "病假", "不可用", "max", "min", "perday",
            "persession", "hours", "minutes", "weight", "homeroom", "subject", "reserve"
        );
    return group && comfort && vagueDegree && !measurableTarget;
  }

  private String compactPolicyText(String text) {
    return text.toLowerCase(Locale.ROOT)
        .replaceAll("[\\s，。；：、！？,.!?;:'\\\"“”‘’（）()\\[\\]{}]+", "");
  }

  private boolean containsAny(String text, String... signals) {
    for (String signal : signals) {
      if (text.contains(signal)) {
        return true;
      }
    }
    return false;
  }

  private String removePhrases(String text, String... phrases) {
    String result = text;
    for (String phrase : phrases) {
      result = result.replace(phrase, "");
    }
    return result;
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

  private int nonNegativeLatency(Integer latencyMs) {
    return latencyMs == null ? 0 : Math.max(0, latencyMs);
  }

  private record PolicyInputViolation(String code, String message) {
  }
}
