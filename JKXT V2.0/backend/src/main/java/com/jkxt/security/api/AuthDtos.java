package com.jkxt.security.api;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.time.Instant;
import com.jkxt.security.auth.PortalPurpose;
import java.util.List;
import java.util.UUID;

public final class AuthDtos {

  private AuthDtos() {
  }

  public record CaptchaResponse(
      String captchaId,
      String imageBase64,
      int expiresInSeconds,
      String debugAnswer
  ) {
  }

  public record LoginRequest(
      @NotBlank String username,
      @NotBlank String password,
      @NotBlank String captchaId,
      @NotBlank String captcha,
      @jakarta.validation.constraints.Null String role
  ) {
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectAuthorityField(String name,Object ignoredValue) {
      if (java.util.Set.of("org","orgId","audience").contains(name)) {
        throw new IllegalArgumentException("Login identity is determined by the server");
      }
      // Preserve existing compatibility for unrelated unknown, non-authority fields.
    }
  }

  public record LoginResponse(
      UserView user,
      OrgView org,
      TermView term,
      List<String> permissions,
      boolean onboarded,
      PortalPurpose audience,
      String sessionContext,
      String csrfToken,
      Instant expiresAt,
      DemoView demo
  ) {
  }

  public record MeResponse(
      UserView user,
      OrgView org,
      TermView term,
      List<String> permissions,
      boolean onboarded,
      PortalPurpose audience,
      String sessionContext,
      String csrfToken,
      Instant expiresAt,
      DemoView demo,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      Instant idleExpiresAt
  ) {
  }

  public record LogoutResponse(boolean ok) {
  }

  public record DemoView(boolean enabled,Long generation,boolean canReset) {
    public DemoView(boolean enabled) {this(enabled,null,false);}
  }

  public record ChangePasswordRequest(
      @NotBlank String currentPassword,
      @NotBlank String newPassword
  ) {
  }

  public record UserView(
      UUID id,
      String username,
      String displayName,
      String role,
      boolean mustChangePassword
  ) {
  }

  public record OrgView(UUID id, String slug, String name, String status) {
  }

  public record TermView(
      UUID id,
      String yearLabel,
      String term,
      String name,
      LocalDate startDate,
      LocalDate endDate,
      boolean current
  ) {
  }
}
