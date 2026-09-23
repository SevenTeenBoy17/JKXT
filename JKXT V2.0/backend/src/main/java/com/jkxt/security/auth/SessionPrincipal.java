package com.jkxt.security.auth;

import java.util.List;
import java.util.UUID;

public record SessionPrincipal(
    String sessionId,
    UUID userId,
    UUID orgId,
    String username,
    String displayName,
    String role,
    List<String> permissions
) {
}
