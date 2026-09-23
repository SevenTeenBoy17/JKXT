package com.jkxt.examops.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

public final class RevisionResourceWriteDtos {

  private RevisionResourceWriteDtos() {
  }

  public record BulkWriteRequest(@NotEmpty List<JsonNode> items) {
    public BulkWriteRequest {
      items = items == null ? List.of() : List.copyOf(items);
    }
  }

  public record BulkWriteResponse(
      UUID id,
      UUID revisionId,
      String resourceKind,
      int acceptedCount,
      boolean replayed
  ) {
  }
}
