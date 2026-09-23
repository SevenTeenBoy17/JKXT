package com.jkxt.common.tenant;

import java.util.Optional;
import java.util.UUID;

public final class TenantContext {

  private static final ThreadLocal<UUID> CURRENT_ORG = new ThreadLocal<>();

  private TenantContext() {
  }

  public static void set(UUID orgId) {
    CURRENT_ORG.set(orgId);
  }

  public static UUID getRequired() {
    UUID orgId = CURRENT_ORG.get();
    if (orgId == null) {
      throw new IllegalStateException("TenantContext is not set");
    }
    return orgId;
  }

  public static Optional<UUID> get() {
    return Optional.ofNullable(CURRENT_ORG.get());
  }

  public static void clear() {
    CURRENT_ORG.remove();
  }
}
