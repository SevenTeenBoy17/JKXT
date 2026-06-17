package com.jkxt.scheduling.api;

import com.jkxt.scheduling.solver.ScheduleSolverResult.Assignment;
import com.jkxt.scheduling.solver.ScheduleSolverResult.ConflictItem;
import com.jkxt.scheduling.solver.ScheduleSolverResult.FairnessItem;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ScheduleDtos {

  private ScheduleDtos() {
  }

  public record FixtureInfo(String id, String name) {
  }

  public record RunStartResponse(
      UUID runId,
      UUID taskId,
      String status,
      String stage,
      int progress,
      String eventsPath
  ) {
  }

  public record RunSnapshot(
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
      List<Assignment> assignments,
      List<ConflictItem> conflictSet,
      List<FairnessItem> fairness
  ) {
  }

  public record ProgressEvent(
      UUID runId,
      String status,
      String stage,
      int progress,
      String message,
      Instant at
  ) {
  }
}
