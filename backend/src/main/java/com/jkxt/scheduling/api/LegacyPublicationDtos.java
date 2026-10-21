package com.jkxt.scheduling.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public final class LegacyPublicationDtos {
  private LegacyPublicationDtos() {}

  public record SubmitPublicationRequest(@NotNull UUID runId, @NotNull @Min(0) Long runRevisionCursor) {}

  public record PublicationView(UUID taskId, UUID planId, UUID revisionId, UUID runId,
      long runRevisionCursor, Long revisionCursor, String status, String mode, String notice,
      boolean sourceCurrent) {}
}
