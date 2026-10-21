package com.jkxt.security.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public final class UserPreferencesDtos {

  private UserPreferencesDtos() {
  }

  public record UserPreferencesResponse(ThemePreferences theme) {
  }

  public record ThemePreferences(
      int version,
      String mode,
      String paletteSource,
      String palette
  ) {
  }

  public record PatchUserPreferencesRequest(@NotNull @Valid ThemePatch theme) {
  }

  public record ThemePatch(
      Integer version,
      String mode,
      String paletteSource,
      String palette
  ) {
  }
}
