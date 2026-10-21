package com.jkxt.security.auth;

import java.util.Locale;
import org.springframework.http.HttpStatus;

/**
 * PB-4 (round 9, login-entry isolation spec 2026-09-20 §4.1/§4.2): the login entry a session belongs to.
 * A SCHOOL session never carries SUPER_ADMIN and a PLATFORM session only carries SUPER_ADMIN; each lives in
 * its own cookie. The {@value #HEADER} request header only selects which cookie is read; it is never a
 * credential. Missing header selects SCHOOL; an unknown value is rejected (never "the more privileged one").
 */
public enum AuthPortal {
  SCHOOL, PLATFORM;

  public static final String HEADER = "X-JKXT-Portal";
  public static final String SUPER_ADMIN = "SUPER_ADMIN";

  public static AuthPortal fromHeader(String value) {
    if (value == null || value.isBlank()) return SCHOOL;
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "school" -> SCHOOL;
      case "platform" -> PLATFORM;
      default -> throw new AuthException(HttpStatus.BAD_REQUEST, "AUTH_PORTAL_INVALID",
          HEADER + " must be 'school' or 'platform'");
    };
  }

  /** The only entry a stored role may sign in through. */
  public static AuthPortal forRole(String role) {
    return SUPER_ADMIN.equals(role) ? PLATFORM : SCHOOL;
  }

  public boolean admits(String role) {
    return this == forRole(role);
  }

  public String wireName() {
    return name().toLowerCase(Locale.ROOT);
  }
}
