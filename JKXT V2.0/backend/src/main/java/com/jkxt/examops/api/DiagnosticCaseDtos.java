package com.jkxt.examops.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public final class DiagnosticCaseDtos {
  private DiagnosticCaseDtos() {
  }

  public record DiagnosticCaseView(UUID id, UUID planId, UUID revisionId, String status, JsonNode issues) {
  }
}
