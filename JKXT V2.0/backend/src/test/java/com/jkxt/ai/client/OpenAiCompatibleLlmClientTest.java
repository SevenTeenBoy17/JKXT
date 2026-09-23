package com.jkxt.ai.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.ai.config.LlmProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Black-box contract tests for an OpenAI-compatible chat-completions endpoint.
 *
 * <p>The server in this test is bound only to the loopback interface. Tokens and payloads are
 * synthetic, so this suite never uses an external endpoint or a real credential.</p>
 */
class OpenAiCompatibleLlmClientTest {

  private static final String TEST_TOKEN = "unit-test-bearer-token-7f39";
  private static final String TEST_MODEL = "qwen-plus";
  private static final int LOOPBACK_TIMEOUT_MS = 5_000;
  private static final ObjectMapper JSON = new ObjectMapper();

  @ParameterizedTest(name = "{0} -> {2}")
  @MethodSource("endpointCases")
  void normalizesRootV1AndFullCompletionUrls(String label, String configuredSuffix, String expectedPath)
      throws Exception {
    try (StubServer server = new StubServer()) {
      server.enqueue(200, successfulBody("{\"accepted\":true}"));
      LlmProperties properties = remoteProperties(server.url(configuredSuffix), 0);

      LlmClient.ChatResponse response = client(properties).chat(request(null));

      assertThat(response.status()).isEqualTo("succeeded");
      assertThat(server.requests()).hasSize(1);
      assertThat(server.requests().getFirst().path()).isEqualTo(expectedPath);
    }
  }

  private static Stream<Arguments> endpointCases() {
    return Stream.of(
        Arguments.of("root", "", "/v1/chat/completions"),
        Arguments.of("version root", "/v1", "/v1/chat/completions"),
        Arguments.of("full completion URL", "/v1/chat/completions", "/v1/chat/completions")
    );
  }

  @Test
  void usesConfiguredModelWhenRequestModelIsBlank() throws Exception {
    try (StubServer server = new StubServer()) {
      server.enqueue(200, successfulBody("{\"accepted\":true}"));

      LlmClient.ChatResponse response = client(remoteProperties(server.url("/v1"), 0))
          .chat(request("   "));

      assertThat(response.status()).isEqualTo("succeeded");
      JsonNode body = JSON.readTree(server.requests().getFirst().body());
      assertThat(body.path("model").asText()).isEqualTo(TEST_MODEL);
    }
  }

  @Test
  void sendsBearerAuthorizationWithoutLeakingCredentialIntoFailure() {
    try (StubServer server = new StubServer()) {
      server.enqueue(500, "{\"error\":{\"message\":\"synthetic upstream failure\"}}");

      LlmClient.ChatResponse response = client(remoteProperties(server.url("/v1"), 0))
          .chat(request(null));

      RecordedRequest recorded = server.requests().getFirst();
      String authorization = recorded.authorization();
      boolean bearerHeaderPresent = authorization != null && authorization.startsWith("Bearer ");
      assertThat(bearerHeaderPresent).isTrue();
      String sentToken = bearerHeaderPresent ? authorization.substring("Bearer ".length()) : "";
      assertThat(sha256(sentToken)).isEqualTo(sha256(TEST_TOKEN));

      // Boolean assertions deliberately avoid rendering a credential into assertion diagnostics.
      assertThat(containsToken(response.errorMessage())).isFalse();
      assertThat(containsToken(response.toString())).isFalse();
    }
  }

  @Test
  void doesNotRetryUnauthorizedResponses() {
    try (StubServer server = new StubServer()) {
      server.enqueue(401, "{\"error\":{\"message\":\"bad synthetic credential\"}}");
      LlmProperties properties = remoteProperties(server.url("/v1"), 3);

      LlmClient.ChatResponse response = client(properties).chat(request(null));

      assertThat(response.status()).isEqualTo("failed");
      assertThat(server.requests()).hasSize(1);
      assertThat(containsToken(response.errorMessage())).isFalse();
    }
  }

  @ParameterizedTest(name = "HTTP {0} is retryable")
  @MethodSource("retryableStatuses")
  void retriesRateLimitAndServerFailuresWithinConfiguredBound(int retryableStatus) {
    try (StubServer server = new StubServer()) {
      server.enqueue(retryableStatus, "{\"error\":{\"message\":\"retry later\"}}");
      server.enqueue(200, successfulBody("{\"accepted\":true}"));

      LlmClient.ChatResponse response = client(remoteProperties(server.url("/v1"), 2))
          .chat(request(null));

      assertThat(response.status()).isEqualTo("succeeded");
      assertThat(server.requests()).hasSize(2);
    }
  }

  @Test
  void usesConfiguredFallbackEndpointAfterRetryablePrimaryFailure() {
    try (StubServer server = new StubServer()) {
      server.enqueue(503, "{\"error\":{\"message\":\"retry on same-origin fallback\"}}");
      server.enqueue(200, successfulBody("{\"accepted\":true}"));
      LlmProperties properties = remoteProperties(server.url("/v1"), 1);
      properties.setFallbackBaseUrl(server.url("/alternate/v1"));

      LlmClient.ChatResponse response = client(properties).chat(request(null));

      assertThat(response.status()).isEqualTo("succeeded");
      assertThat(server.requests()).hasSize(2);
      assertThat(server.requests().stream().map(RecordedRequest::path)).containsExactly(
          "/v1/chat/completions", "/alternate/v1/chat/completions");
    }
  }

  @Test
  void revisitsPrimaryAfterBothConfiguredEndpointsFailTransiently() {
    try (StubServer server = new StubServer()) {
      server.enqueue(503, "{\"error\":{\"message\":\"primary transient\"}}");
      server.enqueue(503, "{\"error\":{\"message\":\"fallback transient\"}}");
      server.enqueue(200, successfulBody("{\"accepted\":true}"));
      LlmProperties properties = remoteProperties(server.url("/v1"), 2);
      properties.setFallbackBaseUrl(server.url("/alternate/v1"));

      LlmClient.ChatResponse response = client(properties).chat(request(null));

      assertThat(response.status()).isEqualTo("succeeded");
      assertThat(server.requests()).hasSize(3);
      assertThat(server.requests().stream().map(RecordedRequest::path)).containsExactly(
          "/v1/chat/completions", "/alternate/v1/chat/completions", "/v1/chat/completions");
    }
  }

  @Test
  void retriesTransientIoDisconnectWithinConfiguredBound() {
    try (StubServer server = new StubServer()) {
      server.enqueueDisconnect();
      server.enqueue(200, successfulBody("{\"accepted\":true}"));

      LlmClient.ChatResponse response = client(remoteProperties(server.url("/v1"), 1))
          .chat(request(null));

      assertThat(response.status()).isEqualTo("succeeded");
      assertThat(server.requests()).hasSize(2);
    }
  }

  private static Stream<Arguments> retryableStatuses() {
    return Stream.of(Arguments.of(429), Arguments.of(500), Arguments.of(503));
  }

  @Test
  void stopsRetryingAtConfiguredBound() {
    try (StubServer server = new StubServer()) {
      server.enqueue(503, "{\"error\":{\"message\":\"still unavailable\"}}");

      LlmClient.ChatResponse response = client(remoteProperties(server.url("/v1"), 2))
          .chat(request(null));

      assertThat(response.status()).isEqualTo("failed");
      assertThat(server.requests()).hasSize(3);
    }
  }

  @Test
  void classifiesMalformedJsonAsInvalidResponse() {
    try (StubServer server = new StubServer()) {
      server.enqueue(200, "this is not json");

      LlmClient.ChatResponse response = client(remoteProperties(server.url("/v1"), 0))
          .chat(request(null));

      assertThat(response.status()).isEqualTo("failed");
      assertThat(response.content()).isBlank();
      assertThat(response.errorMessage()).contains("LLM_RESPONSE_INVALID_JSON");
    }
  }

  @Test
  void classifiesEmptyChoicesAsInvalidResponse() {
    try (StubServer server = new StubServer()) {
      server.enqueue(200, "{\"choices\":[],\"usage\":{}}");

      LlmClient.ChatResponse response = client(remoteProperties(server.url("/v1"), 0))
          .chat(request(null));

      assertThat(response.status()).isEqualTo("failed");
      assertThat(response.content()).isBlank();
      assertThat(response.errorMessage()).contains("LLM_RESPONSE_EMPTY_CHOICES");
    }
  }

  @ParameterizedTest(name = "strict acceptance rejects {0}")
  @MethodSource("localFallbackCases")
  void strictAcceptanceModeRejectsMockDisabledAndMissingCredential(
      String label,
      boolean enabled,
      String provider,
      String apiKey
  ) {
    try (StubServer server = new StubServer()) {
      LlmProperties properties = acceptanceProperties(
          server.url("/v1"), enabled, provider, apiKey, 0);

      LlmClient.ChatResponse response = client(properties).chat(request(null));

      assertThat(response.status()).isEqualTo("failed");
      assertThat(response.fallback()).isFalse();
      assertThat(response.errorMessage()).contains("LLM_ACCEPTANCE_REQUIRES_REAL_PROVIDER");
      assertThat(server.requests()).isEmpty();
    }
  }

  private static Stream<Arguments> localFallbackCases() {
    return Stream.of(
        Arguments.of("mock provider", true, "mock", TEST_TOKEN),
        Arguments.of("disabled client", false, "llm-token", TEST_TOKEN),
        Arguments.of("missing credential", true, "llm-token", "")
    );
  }

  @Test
  void strictAcceptanceModeDoesNotConvertUpstreamFailureIntoFallbackSuccess() {
    try (StubServer server = new StubServer()) {
      server.enqueue(503, "{\"error\":{\"message\":\"unavailable\"}}");
      LlmProperties properties = acceptanceProperties(
          server.url("/v1"), true, "llm-token", TEST_TOKEN, 0);

      LlmClient.ChatResponse response = client(properties).chat(request(null));

      assertThat(response.status()).isEqualTo("failed");
      assertThat(response.fallback()).isFalse();
      assertThat(response.errorMessage()).contains("LLM_UPSTREAM_HTTP_503");
      assertThat(server.requests()).hasSize(1);
    }
  }

  @Test
  void ordinaryModeAlsoRejectsDisabledAndMissingCredentialInsteadOfCannedSuccess() {
    LlmProperties properties = new LlmProperties();
    LlmClient.ChatResponse response = client(properties).chat(request(null));
    assertThat(response.status()).isEqualTo("failed");
    assertThat(response.fallback()).isFalse();
    assertThat(response.content()).isBlank();
  }

  @Test
  void rejectsDifferentOriginFallbackBeforeSendingAnyCredential() {
    try (StubServer primary = new StubServer(); StubServer unrelated = new StubServer()) {
      primary.enqueue(503, "{}");
      unrelated.enqueue(200, successfulBody("{}"));
      LlmProperties properties = remoteProperties(primary.url("/v1"), 1);
      properties.setFallbackBaseUrl(unrelated.url("/v1"));
      LlmClient.ChatResponse response = client(properties).chat(request(null));
      assertThat(response.status()).isEqualTo("failed");
      assertThat(response.errorMessage()).isEqualTo("LLM_ENDPOINT_INVALID");
      assertThat(primary.requests()).isEmpty();
      assertThat(unrelated.requests()).isEmpty();
    }
  }

  @Test
  void rejectsTruncatedGenerationEvenWhenContentIsNonBlank() {
    try (StubServer server = new StubServer()) {
      server.enqueue(200, "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}");
      LlmClient.ChatResponse response = client(remoteProperties(server.url("/v1"), 0)).chat(request(null));
      assertThat(response.status()).isEqualTo("failed");
      assertThat(response.errorMessage()).isEqualTo("LLM_RESPONSE_INCOMPLETE");
    }
  }

  @Test
  void missingOrNullFinishReasonCannotProvePolicyCompletion() {
    for (String finishField : List.of("", "\"finish_reason\":null,")) {
      try (StubServer server = new StubServer()) {
        server.enqueue(200, "{\"choices\":[{" + finishField + "\"message\":{\"content\":\"{}\"}}]}");
        LlmClient.ChatResponse response = client(remoteProperties(server.url("/v1"), 0)).chat(request(null));
        assertThat(response.status()).isEqualTo("failed");
        assertThat(response.errorMessage()).isEqualTo("LLM_RESPONSE_INCOMPLETE");
      }
    }
  }

  @Test
  void rejectsOversizedPromptsBeforeNetwork() {
    try (StubServer server = new StubServer()) {
      LlmClient.ChatResponse response = client(remoteProperties(server.url("/v1"), 0)).chat(
          new LlmClient.ChatRequest("nl2dsl", "", TEST_MODEL, "JSON", "a".repeat(40_001), Map.of()));
      assertThat(response.status()).isEqualTo("failed");
      assertThat(response.errorMessage()).isEqualTo("LLM_REQUEST_TOO_LARGE");
      assertThat(server.requests()).isEmpty();
    }
  }

  @Test
  void rejectsPlaintextNonLoopbackEndpoint() {
    LlmProperties properties = remoteProperties("http://192.0.2.1/v1", 0);
    LlmClient.ChatResponse response = client(properties).chat(request(null));
    assertThat(response.status()).isEqualTo("failed");
    assertThat(response.errorMessage()).isEqualTo("LLM_ENDPOINT_INVALID");
  }

  @Test
  void defaultRouteUsesConfiguredModelFallbackOnRetryableHttpOnly() throws Exception {
    try (StubServer server = new StubServer()) {
      server.enqueue(503, "{}");
      server.enqueue(200, successfulBody("{\"accepted\":true}"));
      LlmClient.ChatResponse response = client(remoteProperties(server.url("/v1"), 1)).chat(request(null));
      assertThat(response.status()).isEqualTo("succeeded");
      assertThat(response.model()).isEqualTo("qwen-flash");
      assertThat(JSON.readTree(server.requests().getFirst().body()).path("model").asText()).isEqualTo("qwen-plus");
      assertThat(JSON.readTree(server.requests().getLast().body()).path("model").asText()).isEqualTo("qwen-flash");
    }
  }

  @Test
  void explicitModelSelectionIsNeverSilentlyReplaced() throws Exception {
    try (StubServer server = new StubServer()) {
      server.enqueue(503, "{}");
      server.enqueue(200, successfulBody("{}"));
      LlmClient.ChatResponse response = client(remoteProperties(server.url("/v1"), 1)).chat(request("qwen-plus"));
      assertThat(response.model()).isEqualTo("qwen-plus");
      assertThat(JSON.readTree(server.requests().getLast().body()).path("model").asText()).isEqualTo("qwen-plus");
    }
  }

  @Test
  void responseBodyLimitStopsOversizedProviderOutput() {
    try (StubServer server = new StubServer()) {
      server.enqueue(200, "x".repeat(1_048_577));
      LlmClient.ChatResponse response = client(remoteProperties(server.url("/v1"), 0)).chat(request(null));
      assertThat(response.errorMessage()).isEqualTo("LLM_RESPONSE_TOO_LARGE");
      assertThat(response.status()).isEqualTo("failed");
    }
  }

  @Test
  void specializedOrUnvettedModelsAreRejectedBeforeNetwork() {
    try (StubServer server = new StubServer()) {
      LlmClient.ChatResponse response = client(remoteProperties(server.url("/v1"), 0)).chat(request("qwen-vl-ocr"));
      assertThat(response.errorMessage()).isEqualTo("LLM_MODEL_CAPABILITY_MISMATCH");
      assertThat(server.requests()).isEmpty();
    }
  }

  private OpenAiCompatibleLlmClient client(LlmProperties properties) {
    return new OpenAiCompatibleLlmClient(properties, JSON);
  }

  private LlmClient.ChatRequest request(String model) {
    return new LlmClient.ChatRequest(
        "nl2dsl",
        "",
        model,
        "Return one small JSON object.",
        "Synthetic test input only.",
        Map.of("kind", "synthetic")
    );
  }

  private static LlmProperties remoteProperties(String baseUrl, int maxRetries) {
    LlmProperties properties = new LlmProperties();
    properties.setEnabled(true);
    properties.setProvider("aliyun");
    properties.setBaseUrl(baseUrl);
    properties.setApiKey(TEST_TOKEN);
    properties.setModel(TEST_MODEL);
    // Keep this a bounded local timeout while leaving enough headroom for a cold, resource-limited
    // CI container to schedule the loopback HttpServer handler.
    properties.setTimeoutMs(LOOPBACK_TIMEOUT_MS);
    properties.setMaxRetries(maxRetries);
    return properties;
  }

  /**
   * Binds the proposed public property name without compiling against a not-yet-existing accessor.
   * Keeping the deployment spelling in this test also proves Spring's relaxed binding reaches both
   * {@link LlmProperties} and {@link OpenAiCompatibleLlmClient} acceptance semantics.
   */
  private static LlmProperties acceptanceProperties(
      String baseUrl,
      boolean enabled,
      String provider,
      String apiKey,
      int maxRetries
  ) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("jkxt.llm.acceptance-mode", true);
    values.put("jkxt.llm.enabled", enabled);
    values.put("jkxt.llm.provider", provider);
    values.put("jkxt.llm.base-url", baseUrl);
    values.put("jkxt.llm.api-key", apiKey);
    values.put("jkxt.llm.model", TEST_MODEL);
    values.put("jkxt.llm.timeout-ms", LOOPBACK_TIMEOUT_MS);
    values.put("jkxt.llm.max-retries", maxRetries);
    return new Binder(new MapConfigurationPropertySource(values))
        .bind("jkxt.llm", Bindable.of(LlmProperties.class))
        .orElseThrow(() -> new IllegalStateException("Unable to bind LLM acceptance properties"));
  }

  private static String successfulBody(String content) {
    try {
      return JSON.writeValueAsString(Map.of(
          "choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", content))),
          "usage", Map.of(
              "prompt_tokens", 3,
              "completion_tokens", 5,
              "total_tokens", 8
          )
      ));
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to build synthetic response", exception);
    }
  }

  private static boolean containsToken(String value) {
    return value != null && value.contains(TEST_TOKEN);
  }

  private static String sha256(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(digest.length * 2);
      for (byte item : digest) {
        result.append(String.format("%02x", item));
      }
      return result.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the JDK", exception);
    }
  }

  private record StubResponse(int status, String body) {
  }

  private record RecordedRequest(String path, String authorization, String body) {
  }

  private static final class StubServer implements AutoCloseable {

    private final HttpServer server;
    private final Queue<StubResponse> queuedResponses = new ConcurrentLinkedQueue<>();
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private volatile StubResponse lastResponse = new StubResponse(
        500, "{\"error\":{\"message\":\"no synthetic response configured\"}}");

    private StubServer() {
      try {
        server = HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      } catch (IOException exception) {
        throw new IllegalStateException("Unable to start loopback test server", exception);
      }
      server.createContext("/", this::handle);
      server.start();
    }

    private void enqueue(int status, String body) {
      queuedResponses.add(new StubResponse(status, body));
    }

    private void enqueueDisconnect() {
      queuedResponses.add(new StubResponse(0, ""));
    }

    private String url(String suffix) {
      return "http://127.0.0.1:" + server.getAddress().getPort() + suffix;
    }

    private List<RecordedRequest> requests() {
      return new ArrayList<>(requests);
    }

    private void handle(HttpExchange exchange) throws IOException {
      byte[] requestBody = exchange.getRequestBody().readAllBytes();
      requests.add(new RecordedRequest(
          exchange.getRequestURI().getPath(),
          exchange.getRequestHeaders().getFirst("Authorization"),
          new String(requestBody, StandardCharsets.UTF_8)
      ));

      StubResponse configured = queuedResponses.poll();
      if (configured != null) {
        lastResponse = configured;
      }
      if (lastResponse.status() == 0) {
        exchange.close();
        return;
      }
      byte[] responseBody = lastResponse.body().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
      exchange.sendResponseHeaders(lastResponse.status(), responseBody.length);
      try (exchange; var output = exchange.getResponseBody()) {
        output.write(responseBody);
      }
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
