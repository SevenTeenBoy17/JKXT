package com.jkxt.examops.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Deliberately excludes free-text reasons, raw state, login names and personal contact data. */
public final class ExamPlanAuditDtos {
  private ExamPlanAuditDtos() {}

  public record AuditEventView(UUID id, String action, Instant occurredAt, String actorDisplayName) {}

  public record AuditEventPage(UUID planId, UUID revisionId, String source, List<AuditEventView> items,
      String nextCursor, boolean hasMore) {}
}
