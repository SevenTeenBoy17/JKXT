package com.jkxt.support;

import jakarta.servlet.http.Cookie;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * PB-4 (round 9): seeded platform operators sign in through the platform entry and carry the platform
 * cookie; everyone else uses the school entry. Mirrors what the SPA does per entry.
 */
public final class TestPortal {
  public static final String SCHOOL_COOKIE = "JKXT_SCHOOL_SESSION";
  public static final String PLATFORM_COOKIE = "JKXT_PLATFORM_SESSION";

  private TestPortal() {
  }

  public static boolean platformAccount(String username) {
    return "admin".equals(username) || "platform.super".equals(username) || "ops.demo".equals(username);
  }

  public static String loginPath(String username) {
    return platformAccount(username) ? "/api/v1/auth/platform/login" : "/api/v1/auth/school/login";
  }

  /** Fixture accounts created on the fly: the stored role decides the entry (all rows SUPER_ADMIN = platform). */
  public static String loginPath(org.springframework.jdbc.core.JdbcTemplate jdbc, String username) {
    java.util.List<String> roles = jdbc.queryForList(
        "select role from app_user where lower(username) = lower(?) and deleted_at is null", String.class, username);
    boolean platform = !roles.isEmpty() && roles.stream().allMatch("SUPER_ADMIN"::equals);
    return platform ? "/api/v1/auth/platform/login" : "/api/v1/auth/school/login";
  }

  /** The session cookie a login response issued, whichever entry it came from. */
  public static Cookie sessionCookie(MockHttpServletResponse response) {
    Cookie platform = response.getCookie(PLATFORM_COOKIE);
    return platform != null ? platform : response.getCookie(SCHOOL_COOKIE);
  }
}
