package com.jkxt.examops.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class LeaveRequestDtos {

  private LeaveRequestDtos() {
  }

  public record CreateLeaveRequest(
      @NotNull UUID teacherId,
      @NotNull Instant startsAt,
      @NotNull Instant endsAt,
      @NotBlank @Size(max = 500) String reason
  ) {
  }

  public record DecideLeaveRequest(
      @NotBlank String decision,
      @Size(max = 500) String reason
  ) {
  }

  public record LeaveRequestView(
      UUID id,
      UUID teacherId,
      String teacherNo,
      String teacherName,
      Instant startsAt,
      Instant endsAt,
      String reason,
      String status,
      UUID requestedBy,
      UUID decidedBy,
      String decisionReason,
      Instant decidedAt,
      int substitutionCaseCount,
      long version,
      Instant createdAt,
      Instant updatedAt,
      boolean replayed
  ) {
  }
}
