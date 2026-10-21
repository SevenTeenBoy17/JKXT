package com.jkxt.security.auth;

public final class AuthRequestAttributes {

  public static final String PRINCIPAL = "jkxt.auth.principal";
  /** PB-4: the {@link AuthPortal} whose cookie authenticated (or would authenticate) this request. */
  public static final String PORTAL = "jkxt.auth.portal";

  private AuthRequestAttributes() {
  }
}
