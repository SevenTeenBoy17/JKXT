package com.jkxt.examops.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ExamPlanDtos {

  private ExamPlanDtos() {
  }

  public record CreateExamPlanRequest(
      String planNo,
      @NotBlank String name,
      UUID termId,
      JsonNode settings,
      String initialRevisionTitle
  ) {
  }

  public record CreateRevisionRequest(
      UUID predecessorId,
      String title,
      @NotBlank String changeReason,
      JsonNode configuration
  ) {
  }

  public record DecisionRequest(String reason) {
  }

  public record ExamPlanView(
      UUID id,
      String planNo,
      String name,
      UUID termId,
      String status,
      UUID activeRevisionId,
      UUID legacyExamTaskId,
      JsonNode settings,
      long version,
      Instant createdAt,
      Instant updatedAt
  ) {
  }

  public record PlanRevisionView(
      UUID id,
      UUID planId,
      int revisionNo,
      UUID predecessorId,
      String status,
      String sourceType,
      String title,
      String changeReason,
      JsonNode configuration,
      JsonNode validationSummary,
      long revisionCursor,
      Instant submittedAt,
      Instant approvedAt,
      Instant publishedAt,
      Instant createdAt,
      Instant updatedAt
  ) {
  }

  public record ValidationIssue(
      String code,
      String severity,
      String resourceType,
      String resourceId,
      String message,
      String suggestion
  ) {
  }

  public record PlanValidationResult(
      String code,
      boolean valid,
      String status,
      int hardViolationCount,
      List<ValidationIssue> issues,
      Map<String, Object> metrics,
      PlanRevisionView revision
  ) {
  }
}
