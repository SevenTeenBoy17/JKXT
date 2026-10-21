package com.jkxt.examops.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ClosedLoopDtos {

  private ClosedLoopDtos() {
  }

  public record ResolveSubstitutionRequest(
      @NotNull UUID replacementTeacherId,
      @NotBlank @Size(max = 500) String reason
  ) {
  }

  public record SubstitutionCaseView(
      UUID id,
      UUID revisionId,
      UUID dutyAssignmentId,
      UUID teacherId,
      String teacherNo,
      UUID replacementTeacherId,
      String replacementTeacherNo,
      UUID leaveRequestId,
      String dutyRole,
      Instant startsAt,
      Instant endsAt,
      String status,
      String reason,
      String resolutionReason,
      UUID resolvedBy,
      Instant resolvedAt,
      long version,
      Instant createdAt,
      Instant updatedAt,
      long engagementNo,
      UUID planId,
      String planName,
      String teacherName,
      String replacementTeacherName,
      String roomName,
      String roomCode,
      String sessionCode,
      boolean actionable
  ) {
  }

  public record LifecycleIssue(String code, String message, int count) {
  }

  public record LifecycleTransitionView(
      UUID planId,
      UUID revisionId,
      String previousStatus,
      String status,
      long revisionCursor,
      Instant transitionedAt,
      List<LifecycleIssue> readinessIssues
  ) {
  }

  public record CreateIncidentRequest(
      @NotBlank @Size(max = 64) String incidentCode,
      @NotBlank @Size(max = 48) String incidentType,
      @NotBlank @Size(max = 16) String severity,
      @NotBlank @Size(max = 240) String summary,
      JsonNode details
  ) {
  }

  public record ResolveIncidentRequest(@NotBlank @Size(max = 500) String resolution) {
  }

  public record IncidentView(
      UUID id,
      UUID revisionId,
      String incidentCode,
      String incidentType,
      String severity,
      String summary,
      JsonNode details,
      String status,
      UUID reportedBy,
      UUID resolvedBy,
      Instant resolvedAt,
      String resolution,
      long version,
      Instant createdAt,
      Instant updatedAt
  ) {
  }

  public record CreateChecklistItemRequest(
      @NotBlank @Size(max = 64) String itemCode,
      @NotBlank @Size(max = 240) String label,
      boolean required
  ) {
  }

  public record CompleteChecklistItemRequest(
      @NotBlank String status,
      JsonNode evidence
  ) {
  }

  public record ChecklistItemView(
      UUID id,
      UUID revisionId,
      String itemCode,
      String label,
      boolean required,
      String status,
      JsonNode evidence,
      UUID completedBy,
      Instant completedAt,
      long version,
      Instant createdAt,
      Instant updatedAt
  ) {
  }
}
