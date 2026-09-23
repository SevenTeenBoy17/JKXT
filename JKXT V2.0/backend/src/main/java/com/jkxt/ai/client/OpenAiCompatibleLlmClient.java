package com.jkxt.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.ai.config.LlmProperties;
import com.jkxt.ai.config.AiModelCatalog;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.ByteBuffer;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import org.springframework.stereotype.Service;

@Service
public class OpenAiCompatibleLlmClient implements LlmClient {

  private final LlmProperties properties;
  private final ObjectMapper objectMapper;
  private final com.jkxt.demo.DemoSideEffectPolicy demoPolicy;

  public OpenAiCompatibleLlmClient(LlmProperties properties, ObjectMapper objectMapper, com.jkxt.demo.DemoSideEffectPolicy demoPolicy) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.demoPolicy = java.util.Objects.requireNonNull(demoPolicy);
  }

  @Override
  public ChatResponse chat(ChatRequest request) {
    String provider = nonBlank(request.provider(), properties.getProvider());
    String model = nonBlank(request.model(), properties.getModel());
    boolean testProvider = "mock".equalsIgnoreCase(provider) || "invalid-schema".equalsIgnoreCase(provider);
    if (testProvider && properties.isAllowTestMocks() && !properties.isAcceptanceMode()) {
      return mockResponse(provider, model, request.operation(), "invalid-schema".equalsIgnoreCase(provider));
    }
    if (testProvider || !properties.isEnabled() || !properties.hasCredential()) {
      return failedResponse(provider, model, 0, false, properties.isAcceptanceMode()
          ? "LLM_ACCEPTANCE_REQUIRES_REAL_PROVIDER" : "LLM_NOT_CONFIGURED");
    }
    if (!provider.equals(properties.getProvider())) {
      return failedResponse(provider, model, 0, false, "LLM_PROVIDER_NOT_ALLOWED");
    }
    AiModelCatalog.Model definition = AiModelCatalog.find(model);
    if (definition == null || !definition.policyCompatible()) {
      return failedResponse(provider, model, 0, false,
          definition == null ? "LLM_MODEL_NOT_ALLOWED" : "LLM_MODEL_CAPABILITY_MISMATCH");
    }
    int promptLength = (request.systemPrompt() == null ? 0 : request.systemPrompt().length())
        + (request.userPrompt() == null ? 0 : request.userPrompt().length());
    if (promptLength > Math.clamp(properties.getMaxPromptChars(), 1000, 40000)) {
      return failedResponse(provider, model, 0, false, "LLM_REQUEST_TOO_LARGE");
    }
    if (request.systemPrompt() == null || request.userPrompt() == null) {
      return failedResponse(provider, model, 0, false, "LLM_REQUEST_INVALID");
    }
    return remoteResponse(provider, model, request);
  }

  private ChatResponse remoteResponse(String provider, String model, ChatRequest request) {
    demoPolicy.requireAllowed("paid-ai");
    List<String> modelRoute = modelRoute(request, model);
    int modelIndex = 0;
    List<URI> endpoints;
    try {
      endpoints = configuredEndpoints();
    } catch (RuntimeException exception) {
      return failedResponse(provider, model, 0, remoteFailureUsesFallback(), "LLM_ENDPOINT_INVALID");
    }
    HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(Math.clamp(properties.getTimeoutMs(), 1000, 180000)))
        .build();
    try {
      int attempts = Math.clamp(properties.getMaxRetries(), 0, 2) + 1;
      for (int i = 0; i < attempts; i++) {
        // Rotate across every configured gateway instead of pinning all remaining retries to the
        // last endpoint. A brief fault on both gateways can then recover within the same bounded
        // request (for two endpoints and maxRetries=2: primary -> fallback -> primary).
        URI endpoint = endpoints.get(i % endpoints.size());
        long startNanos = System.nanoTime();
        try {
          Map<String, Object> body = new LinkedHashMap<>();
          body.put("model", model);
          AiModelCatalog.Model definition = AiModelCatalog.find(model);
          // Avoid a universal response_format/temperature contract: specialized model families do
          // not all accept those parameters. JSON is always validated after the real response.
          if (definition.jsonMode()) body.put("response_format", Map.of("type", "json_object"));
          if ("disable".equals(definition.thinking())) body.put("enable_thinking", false);
          body.put("max_tokens", Math.clamp(properties.getMaxOutputTokens(), 256, 16384));
          body.put("stream", false);
          body.put("messages", List.of(
              Map.of("role", "system", "content", request.systemPrompt()),
              Map.of("role", "user", "content", request.userPrompt())
          ));
          HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
              .timeout(Duration.ofMillis(Math.clamp(properties.getTimeoutMs(), 1000, 180000)))
              .header("Content-Type", "application/json")
              .header("Authorization", "Bearer " + properties.getApiKey())
              .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
              .build();
          HttpResponse<byte[]> response = client.send(httpRequest, ignored ->
              new LimitedBodySubscriber(Math.clamp(properties.getMaxResponseBytes(), 16384, 1048576)));
          int latency = elapsedMs(startNanos);
          if (response.statusCode() < 200 || response.statusCode() >= 300) {
            int status = response.statusCode();
            String errorCode = "LLM_UPSTREAM_HTTP_" + status;
            if (isRetryableStatus(status) && i + 1 < attempts) {
              modelIndex = (modelIndex + 1) % modelRoute.size();
              model = modelRoute.get(modelIndex);
              boundedBackoff(i);
              continue;
            }
            return failedResponse(provider, model, latency, remoteFailureUsesFallback(), errorCode);
          }
          JsonNode root;
          try {
            root = objectMapper.readTree(response.body());
          } catch (Exception exception) {
            if (i + 1 < attempts) {
              boundedBackoff(i);
              continue;
            }
            return failedResponse(
                provider,
                model,
                latency,
                remoteFailureUsesFallback(),
                "LLM_RESPONSE_INVALID_JSON"
            );
          }
          JsonNode choices = root.path("choices");
          if (!choices.isArray() || choices.isEmpty()) {
            if (i + 1 < attempts) {
              boundedBackoff(i);
              continue;
            }
            return failedResponse(
                provider,
                model,
                latency,
                remoteFailureUsesFallback(),
                "LLM_RESPONSE_EMPTY_CHOICES"
            );
          }
          String finishReason = choices.path(0).path("finish_reason").asText("");
          if (!"stop".equals(finishReason)) {
            return failedResponse(provider, model, latency, false, "LLM_RESPONSE_INCOMPLETE");
          }
          String content = choices.path(0).path("message").path("content").asText("");
          if (content.isBlank()) {
            if (i + 1 < attempts) {
              boundedBackoff(i);
              continue;
            }
            return failedResponse(
                provider,
                model,
                latency,
                remoteFailureUsesFallback(),
                "LLM_RESPONSE_EMPTY_CHOICES"
            );
          }
          Map<String, Object> usage = usage(root.path("usage"));
          return new ChatResponse(provider, model, content, latency, usage, BigDecimal.ZERO, "succeeded", false, null);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          return failedResponse(
              provider,
              model,
              elapsedMs(startNanos),
              remoteFailureUsesFallback(),
              "LLM_REQUEST_INTERRUPTED"
          );
        } catch (Exception exception) {
          // Do not surface exception messages: depending on the HTTP implementation they can contain
          // endpoint details, and upstream response bodies can contain user-provided material.
          if (isResponseTooLarge(exception)) {
            return failedResponse(provider, model, elapsedMs(startNanos), false, "LLM_RESPONSE_TOO_LARGE");
          }
          if (i + 1 < attempts) {
            try {
              boundedBackoff(i);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              return failedResponse(
                  provider,
                  model,
                  elapsedMs(startNanos),
                  remoteFailureUsesFallback(),
                  "LLM_REQUEST_INTERRUPTED"
              );
            }
            continue;
          }
          return failedResponse(
              provider,
              model,
              elapsedMs(startNanos),
              remoteFailureUsesFallback(),
              "LLM_UPSTREAM_IO_FAILURE"
          );
        }
      }
    } finally {
      // Java 21's graceful close can wait for an upstream keep-alive timeout after a 5xx response.
      // This client is per call, so terminate it immediately once the bounded request loop finishes.
      client.shutdownNow();
    }
    return failedResponse(
        provider,
        model,
        0,
        remoteFailureUsesFallback(),
        "LLM_UPSTREAM_RETRY_EXHAUSTED"
    );
  }

  private ChatResponse failedResponse(
      String provider,
      String model,
      int latencyMs,
      boolean fallback,
      String errorCode
  ) {
    return new ChatResponse(
        provider,
        model,
        "",
        latencyMs,
        Map.of(),
        BigDecimal.ZERO,
        "failed",
        fallback,
        errorCode
    );
  }

  private boolean remoteFailureUsesFallback() {
    return false;
  }

  private boolean isRetryableStatus(int status) {
    return status == 429 || status >= 500 && status <= 599;
  }

  private List<String> modelRoute(ChatRequest request, String initialModel) {
    // An explicitly selected model is an exact-call contract, including verification runs.
    if (request.model() != null && !request.model().isBlank()) return List.of(initialModel);
    java.util.LinkedHashSet<String> route = new java.util.LinkedHashSet<>();
    route.add(initialModel);
    for (String candidate : properties.getFallbackModels()) {
      AiModelCatalog.Model model = AiModelCatalog.find(candidate);
      if (model != null && model.policyCompatible()) route.add(candidate);
    }
    return List.copyOf(route);
  }

  private void boundedBackoff(int retryIndex) throws InterruptedException {
    int exponent = Math.max(0, Math.min(retryIndex, 4));
    long delayMs = Math.min(250L, 25L << exponent);
    Thread.sleep(delayMs);
  }

  private ChatResponse mockResponse(String provider, String model, String operation, boolean invalidSchema) {
    long startNanos = System.nanoTime();
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
              "AI 仅解析了排考策略意图，最终监考分配仍由 OR-Tools 求解器生成。",
              "硬约束须在排考运行被采纳前完成校验。"
            ],
            "repairSuggestions": [
              "若无可行解，请优先放宽软性的工作量均衡，再考虑调整硬性的回避规则。"
            ],
            "confidence": 0.78,
            "needsReview": true
          }
          """;
    }
    int latency = elapsedMs(startNanos);
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
    if (baseUrl.isBlank()) throw new IllegalArgumentException("Endpoint is not configured");
    if (baseUrl.endsWith("/chat/completions")) {
      return baseUrl;
    }
    URI parsed = URI.create(baseUrl);
    String path = parsed.getPath();
    if (path == null || path.isBlank() || "/".equals(path)) {
      return baseUrl + "/v1/chat/completions";
    }
    return baseUrl + "/chat/completions";
  }

  private List<URI> configuredEndpoints() {
    List<URI> endpoints = new java.util.ArrayList<>();
    URI primary = URI.create(chatEndpoint(properties.getBaseUrl()));
    validateEndpoint(primary);
    endpoints.add(primary);
    String fallbackBaseUrl = properties.getFallbackBaseUrl();
    if (fallbackBaseUrl != null && !fallbackBaseUrl.isBlank()) {
      URI fallback = URI.create(chatEndpoint(fallbackBaseUrl));
      validateEndpoint(fallback);
      if (!sameOrigin(primary, fallback)) {
        throw new IllegalArgumentException("Fallback must share the primary credential origin");
      }
      if (!fallback.equals(primary)) {
        endpoints.add(fallback);
      }
    }
    return endpoints;
  }

  private void validateEndpoint(URI uri) {
    String host = uri.getHost();
    boolean loopback = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)
        || "[::1]".equals(host) || "::1".equals(host);
    boolean secureScheme = "https".equalsIgnoreCase(uri.getScheme())
        || loopback && "http".equalsIgnoreCase(uri.getScheme());
    if (!secureScheme || host == null || uri.getUserInfo() != null || uri.getQuery() != null
        || uri.getFragment() != null || uri.getPort() == 0) {
      throw new IllegalArgumentException("Untrusted endpoint");
    }
  }

  private boolean sameOrigin(URI first, URI second) {
    return first.getScheme().equalsIgnoreCase(second.getScheme())
        && first.getHost().equalsIgnoreCase(second.getHost())
        && effectivePort(first) == effectivePort(second);
  }

  private int effectivePort(URI uri) {
    return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  private boolean isResponseTooLarge(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof ResponseTooLargeException) return true;
    }
    return false;
  }

  private static final class ResponseTooLargeException extends IOException {}

  private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
    private final int maximum;
    private int received;
    private Flow.Subscription subscription;
    private boolean terminated;

    private LimitedBodySubscriber(int maximum) { this.maximum = maximum; }
    @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
    @Override public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
    @Override public void onNext(List<ByteBuffer> items) {
      if (terminated) return;
      long count = items.stream().mapToLong(ByteBuffer::remaining).sum();
      if (received + count > maximum) {
        terminated = true;
        subscription.cancel();
        delegate.onError(new ResponseTooLargeException());
      } else {
        received += (int) count;
        delegate.onNext(items);
      }
    }
    @Override public void onError(Throwable error) { if (!terminated) { terminated = true; delegate.onError(error); } }
    @Override public void onComplete() { if (!terminated) { terminated = true; delegate.onComplete(); } }
  }

  private String nonBlank(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private int elapsedMs(long startNanos) {
    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
    return (int) Math.max(0, Math.min(Integer.MAX_VALUE, elapsedMs));
  }
}
