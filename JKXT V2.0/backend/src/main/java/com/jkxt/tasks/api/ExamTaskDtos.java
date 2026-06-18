package com.jkxt.tasks.api;

import com.jkxt.importer.api.ImportDtos.ValidationReport;
import com.jkxt.scheduling.solver.ScheduleSolverResult.ConflictItem;
import com.jkxt.scheduling.solver.ScheduleSolverResult.FairnessItem;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ExamTaskDtos {

  private ExamTaskDtos() {
  }

  public record CreateExamTaskRequest(String name, String source) {
  }

  public record ExamTaskResponse(
      UUID id,
      String taskNo,
      String name,
      String status,
      LocalDate startsOn,
      LocalDate endsOn,
      Instant createdAt,
      Instant updatedAt,
      List<UploadResponse> uploads,
      JobResponse latestJob,
      Map<String, Object> stats
  ) {
  }

  public record UploadResponse(
      UUID id,
      String kind,
      String originalName,
      long sizeBytes,
      String sha256,
      ValidationReport report
  ) {
  }

  public record ValidationResponse(
      UUID taskId,
      String status,
      ValidationReport report,
      List<UploadResponse> uploads
  ) {
  }

  public record JobResponse(
      UUID jobId,
      UUID runId,
      UUID taskId,
      String status,
      String stage,
      int progress,
      String solver,
      Long seed,
      String errorCode,
      String errorMessage,
      Map<String, Object> stats,
      List<Map<String, Object>> logs,
      List<AssignmentRow> assignments,
      List<ConflictItem> conflictSet,
      List<FairnessItem> fairness,
      AiAdvice ai,
      Map<String, String> downloads,
      String eventsPath
  ) {
  }

  public record AssignmentsResponse(
      UUID taskId,
      UUID runId,
      String status,
      List<AssignmentRow> rows,
      List<FairnessItem> fairness,
      AiAdvice ai,
      Map<String, String> downloads,
      Map<String, Object> stats
  ) {
  }

  public record AssignmentRow(
      UUID assignmentId,
      UUID examId,
      UUID roomId,
      UUID teacherId,
      String subjectName,
      String grade,
      LocalDate examDate,
      LocalTime startTime,
      LocalTime endTime,
      String roomCode,
      String roomName,
      String teacherNo,
      String teacherName,
      String role,
      String source
  ) {
  }

  public record AiAdvice(List<AiTarget> targets, List<String> summaries) {
  }

  public record AiTarget(String kind, String label, int count, String reason) {
  }
}
