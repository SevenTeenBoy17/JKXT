package com.jkxt.examops.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public final class EmergencyChangeDtos {
  private EmergencyChangeDtos() {
  }

  public record EmergencyChangeView(
      UUID id,
      UUID changeId,
      UUID sourceRevisionId,
      UUID revisionId,
      UUID successorRevisionId,
      String status,
      double retentionRate,
      JsonNode impactAnalysis,
      boolean replayed
  ) {
  }
}
