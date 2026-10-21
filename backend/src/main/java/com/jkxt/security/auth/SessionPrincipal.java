package com.jkxt.security.auth;

import java.util.List;
import java.util.UUID;

/**
 * Server-side session state. {@code audience} (PB-4, round 9) is the {@link AuthPortal} the session was
 * issued for; sessions stored before round 9 have none and are refused, never silently upgraded.
 */
public record SessionPrincipal(
    String sessionId,
    UUID userId,
    UUID orgId,
    String username,
    String displayName,
    String role,
    List<String> permissions,
    long authVersion,
    long issuedAtEpochMillis,
    long expiresAtEpochMillis,
    String audience
) {
  public SessionPrincipal(String sessionId, UUID userId, UUID orgId, String username,
      String displayName, String role, List<String> permissions) {
    this(sessionId, userId, orgId, username, displayName, role, permissions, 0, 0, 0, null);
  }

  public SessionPrincipal(String sessionId, UUID userId, UUID orgId, String username, String displayName,
      String role, List<String> permissions, long authVersion, long issuedAtEpochMillis, long expiresAtEpochMillis) {
    this(sessionId, userId, orgId, username, displayName, role, permissions, authVersion,
        issuedAtEpochMillis, expiresAtEpochMillis, null);
  }

  public SessionPrincipal withAuthVersion(long version) {
    return new SessionPrincipal(sessionId, userId, orgId, username, displayName, role,
        permissions, version, issuedAtEpochMillis, expiresAtEpochMillis, audience);
  }

  public AuthPortal portal() {
    if (audience == null) return null;
    try {
      return AuthPortal.valueOf(audience);
    } catch (IllegalArgumentException unknown) {
      return null;
    }
  }
}
