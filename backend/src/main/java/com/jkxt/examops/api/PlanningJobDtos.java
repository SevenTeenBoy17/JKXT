package com.jkxt.examops.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public final class PlanningJobDtos {
  private PlanningJobDtos() {
  }

  public record PlanningJobRequest(String mode, Long seed, Boolean failOnPartialCoverage, Integer timeoutSeconds) {
  }

  public record PlanningJobView(
      UUID id,
      UUID jobId,
      UUID planId,
      UUID revisionId,
      String status,
      String stage,
      int progress,
      JsonNode diagnostics,
      JsonNode stats,
      boolean cancelRequested,
      Instant startedAt,
      Instant finishedAt,
      Instant createdAt,
      long version,
      String inputHash,
      String resultHash,
      String solverVersion,
      Integer iterationCount,
      String requestHash
  ) {
  }
}
