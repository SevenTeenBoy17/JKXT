package com.jkxt.security.auth;

import java.time.Instant;
import java.util.UUID;

/** Server-only immutable lease. Never reconstruct from request fields. */
public record SessionEnvelope(SessionPrincipal principal, PortalPurpose audience,
    long authVersion, String context, String csrfToken, Instant expiresAt,
    UUID workspaceId, Long generation) {
  public SessionEnvelope withPrincipal(SessionPrincipal current) {
    return new SessionEnvelope(current, audience, authVersion, context,
        csrfToken, expiresAt, workspaceId, generation);
  }
}
