package com.jkxt.examops.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class OperationsDtos {

  private OperationsDtos() {
  }

  public record ReadinessResponse(
      Instant generatedAt,
      boolean ready,
      int blockingIssueCount,
      Map<String, Integer> totals,
      List<ReadinessIssue> issues
  ) {
  }

  public record ReadinessIssue(String code, String message, int count) {
  }

  public record ApprovalRequestView(
      UUID id,
      UUID planId,
      String planName,
      UUID revisionId,
      int revisionNo,
      String status,
      UUID requestedBy,
      String requestedByName,
      UUID decidedBy,
      String decidedByName,
      String decisionReason,
      Instant decidedAt,
      Instant createdAt,
      long version
  ) {
  }

  public record EmergencyChangeView(
      UUID id,
      UUID planId,
      String planName,
      UUID revisionId,
      int revisionNo,
      String emergencyType,
      String status,
      Object affectedResources,
      Object actionTaken,
      Instant createdAt,
      Instant updatedAt,
      long version
  ) {
  }
}
