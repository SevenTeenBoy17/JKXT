package com.jkxt.ai.api;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AiDtos {

  private AiDtos() {
  }

  public record NlPolicyParseRequest(
      @NotBlank String text,
      List<String> policyIds,
      UUID taskId,
      String provider,
      String model
  ) {
  }

  public record NlPolicyRefineRequest(
      @NotBlank String text,
      List<String> policyIds,
      String provider,
      String model
  ) {
  }

  public record RuleDto(
      String id,
      String type,
      Boolean enabled,
      String severity,
      Map<String, Object> params
  ) {
  }

  public record PolicyDsl(
      String version,
      String solverAuthority,
      List<RuleDto> hard,
      List<RuleDto> soft,
      List<String> explanations,
      List<String> repairSuggestions,
      Double confidence,
      Boolean needsReview
  ) {
  }

  public record NlPolicyParseResponse(
      PolicyDsl dsl,
      String refinedText,
      String provider,
      String model,
      String status,
      boolean fallback,
      String solverAuthority,
      List<String> warnings
  ) {
  }

  public record NlPolicyRefineResponse(
      String refinedText,
      List<String> suggestions,
      String provider,
      String model,
      String status,
      boolean fallback
  ) {
  }

  public record ProviderStatusResponse(
      List<ProviderStatus> providers,
      ProviderUsage usage,
      List<RecentAiCall> recentCalls
  ) {
  }

  public record ProviderStatus(
      String id,
      String name,
      String status,
      boolean active,
      boolean configured,
      String endpointHost,
      List<String> models
  ) {
  }

  public record ProviderUsage(
      int monthCallCount,
      int monthlyQuota,
      int remainingQuota,
      int successCount,
      int failureCount,
      long averageLatencyMs,
      long totalTokens,
      double costCents
  ) {
  }

  public record RecentAiCall(
      UUID id,
      String provider,
      String model,
      String purpose,
      String status,
      Integer latencyMs,
      Instant createdAt
  ) {
  }
}
