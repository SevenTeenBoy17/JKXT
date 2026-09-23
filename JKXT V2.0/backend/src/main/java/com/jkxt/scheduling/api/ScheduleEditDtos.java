package com.jkxt.scheduling.api;

import com.jkxt.tasks.api.ExamTaskDtos.AssignmentRow;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public final class ScheduleEditDtos {

  private ScheduleEditDtos() {
  }

  public record SchedulePatchRequest(
      @NotNull UUID assignmentId,
      @NotNull UUID teacherId,
      Integer expectedRevision,
      String reason
  ) {
  }

  public record ConflictCheckRequest(
      @NotNull UUID assignmentId,
      @NotNull UUID teacherId
  ) {
  }

  public record ScheduleConflict(
      String code,
      String severity,
      String message,
      UUID assignmentId,
      UUID teacherId,
      UUID conflictingAssignmentId,
      LocalDate examDate,
      LocalTime startTime,
      LocalTime endTime
  ) {
  }

  public record ConflictCheckResponse(
      UUID taskId,
      UUID runId,
      UUID assignmentId,
      UUID teacherId,
      boolean allowed,
      int count,
      List<ScheduleConflict> conflicts
  ) {
  }

  public record SchedulePatchResponse(
      UUID taskId,
      UUID runId,
      UUID assignmentId,
      UUID teacherId,
      String teacherName,
      int revision,
      int revisionCursor,
      AssignmentRow assignment,
      List<ScheduleConflict> conflicts,
      Long exportRevision,
      String outputToken
  ) {
  }

  public record RevisionResponse(
      UUID taskId,
      UUID runId,
      int revision,
      int revisionCursor,
      String action,
      AssignmentRow assignment,
      Long exportRevision,
      String outputToken
  ) {
  }

  public record PublishResponse(
      UUID taskId,
      String status,
      Instant publishedAt,
      UUID notificationId,
      String notificationStatus,
      int recipientCount,
      int notificationCount,
      boolean alreadyPublished
  ) {
  }
}
