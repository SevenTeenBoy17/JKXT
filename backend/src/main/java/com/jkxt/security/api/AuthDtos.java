package com.jkxt.security.api;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
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
      String role,
      String organization
  ) {
  }

  public record LoginResponse(
      UserView user,
      OrgView org,
      TermView term,
      List<String> permissions,
      boolean onboarded
  ) {
  }

  public record MeResponse(
      UserView user,
      OrgView org,
      TermView term,
      List<String> permissions,
      boolean onboarded
  ) {
  }

  public record LogoutResponse(boolean ok) {
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
