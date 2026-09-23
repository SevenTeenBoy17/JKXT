package com.jkxt.scheduling.solver;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ScheduleSolverResult(
    String status,
    String solverStatus,
    String errorCode,
    String errorMessage,
    List<Assignment> assignments,
    List<ConflictItem> conflictSet,
    List<FairnessItem> fairness,
    Map<String, Object> stats,
    long solveMillis,
    Instant solvedAt
) {

  public boolean feasible() {
    return "succeeded".equals(status);
  }

  public record Assignment(
      UUID examId,
      UUID roomId,
      UUID teacherId,
      String teacherName,
      String roomCode,
      String role,
      LocalDate date,
      LocalTime start,
      LocalTime end
  ) {
  }

  public record ConflictItem(
      String code,
      String constraint,
      String severity,
      String scope,
      String message,
      Map<String, Object> refs
  ) {
  }

  public record FairnessItem(
      String id,
      String label,
      String value,
      String status,
      String detail
  ) {
  }
}
