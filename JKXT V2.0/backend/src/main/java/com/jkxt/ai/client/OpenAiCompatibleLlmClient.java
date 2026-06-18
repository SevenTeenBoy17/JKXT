package com.jkxt.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.ai.config.LlmProperties;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class OpenAiCompatibleLlmClient implements LlmClient {

  private final LlmProperties properties;
  private final ObjectMapper objectMapper;

  public OpenAiCompatibleLlmClient(LlmProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  @Override
  public ChatResponse chat(ChatRequest request) {
    String provider = nonBlank(request.provider(), properties.getProvider());
    String model = nonBlank(request.model(), properties.getModel());
    if ("invalid-schema".equalsIgnoreCase(provider)) {
      return mockResponse(provider, model, request.operation(), true);
    }
    if ("mock".equalsIgnoreCase(provider) || !properties.isEnabled() || !properties.hasCredential()) {
      return mockResponse(provider, model, request.operation(), false);
    }
    return remoteResponse(provider, model, request);
  }

  private ChatResponse remoteResponse(String provider, String model, ChatRequest request) {
    HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(Math.max(properties.getTimeoutMs(), 1000)))
        .build();
    URI endpoint = URI.create(chatEndpoint(properties.getBaseUrl()));
    int attempts = Math.max(1, properties.getMaxRetries() + 1);
    Exception lastError = null;
    for (int i = 0; i < attempts; i++) {
      Instant start = Instant.now();
      try {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0.1);
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", List.of(
            Map.of("role", "system", "content", request.systemPrompt()),
            Map.of("role", "user", "content", request.userPrompt())
        ));
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofMillis(Math.max(properties.getTimeoutMs(), 1000)))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + properties.getApiKey())
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build();
        HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        int latency = (int) Duration.between(start, Instant.now()).toMillis();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
          lastError = new IllegalStateException("LLM endpoint returned HTTP " + response.statusCode());
          continue;
        }
        JsonNode root = objectMapper.readTree(response.body());
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        Map<String, Object> usage = usage(root.path("usage"));
        return new ChatResponse(provider, model, content, latency, usage, BigDecimal.ZERO, "succeeded", false, null);
      } catch (Exception exception) {
        lastError = exception;
      }
    }
    return new ChatResponse(
        provider,
        model,
        "",
        0,
        Map.of(),
        BigDecimal.ZERO,
        "failed",
        true,
        lastError == null ? "LLM request failed" : lastError.getMessage()
    );
  }

  private ChatResponse mockResponse(String provider, String model, String operation, boolean invalidSchema) {
    Instant start = Instant.now();
    String content;
    if (invalidSchema) {
      content = "{\"version\":\"1.0\",\"solverAuthority\":\"LLM\",\"hard\":\"bad\"}";
    } else if ("refine".equals(operation)) {
      content = """
          {
            "refinedText": "请优先保持班主任回避本班、同年级回避和工作量均衡；临时会议或设备要求作为人工复核项进入求解前检查。",
            "suggestions": [
              "把硬约束和软约束分开描述。",
              "涉及教师姓名、手机号、证件号等信息时先脱敏再提交。",
              "最终排考结果由 OR-Tools 求解器和人工确认决定。"
            ]
          }
          """;
    } else {
      content = """
          {
            "version": "1.0",
            "solverAuthority": "OR_TOOLS",
            "hard": [
              {"id": "H_HOMEROOM_AVOID", "type": "teacher_avoid_class", "enabled": true, "severity": "hard", "params": {"scope": "homeroom"}},
              {"id": "H_SAME_GRADE_AVOID", "type": "same_grade_avoid", "enabled": true, "severity": "hard", "params": {"scope": "grade"}}
            ],
            "soft": [
              {"id": "S_WORKLOAD_BALANCE", "type": "workload_balance", "enabled": true, "severity": "soft", "params": {"maxDelta": 2}},
              {"id": "S_MANUAL_NOTE_REVIEW", "type": "manual_note", "enabled": true, "severity": "soft", "params": {"requiresHumanReview": true}}
            ],
            "explanations": [
              "LLM only parsed policy intent; assignments are still produced by OR-Tools.",
              "Hard constraints must be checked before schedule_run can be accepted."
            ],
            "repairSuggestions": [
              "If infeasible, relax soft workload balance before changing hard avoidance rules."
            ],
            "confidence": 0.78,
            "needsReview": true
          }
          """;
    }
    int latency = (int) Duration.between(start, Instant.now()).toMillis();
    // A mock/short-circuit response is never a genuine call to the named provider (LLM disabled or
    // no credential). fallback MUST be true regardless of the requested provider name, otherwise the
    // caller/UI would mistake canned output for a real "<provider> succeeded" result (P1-7).
    return new ChatResponse(
        provider,
        model,
        content,
        latency,
        Map.of("promptTokens", 120, "completionTokens", 180, "totalTokens", 300),
        BigDecimal.ZERO,
        "succeeded",
        true,
        null
    );
  }

  private Map<String, Object> usage(JsonNode usageNode) {
    Map<String, Object> usage = new LinkedHashMap<>();
    usage.put("promptTokens", usageNode.path("prompt_tokens").asInt(0));
    usage.put("completionTokens", usageNode.path("completion_tokens").asInt(0));
    usage.put("totalTokens", usageNode.path("total_tokens").asInt(0));
    return usage;
  }

  private String chatEndpoint(String rawBaseUrl) {
    String baseUrl = nonBlank(rawBaseUrl, LlmProperties.DEFAULT_BASE_URL).replaceAll("/+$", "");
    if (baseUrl.endsWith("/chat/completions")) {
      return baseUrl;
    }
    try {
      if (URI.create(baseUrl).getPath() == null || URI.create(baseUrl).getPath().isBlank()) {
        return baseUrl + "/v1/chat/completions";
      }
    } catch (Exception ignored) {
      return baseUrl + "/chat/completions";
    }
    return baseUrl + "/chat/completions";
  }

  private String nonBlank(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }
}
