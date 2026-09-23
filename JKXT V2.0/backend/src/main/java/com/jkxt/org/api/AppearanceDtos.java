package com.jkxt.org.api;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public final class AppearanceDtos {

  private AppearanceDtos() {
  }

  public record AppearanceResponse(
      int version,
      String defaultPalette,
      List<String> availablePalettes
  ) {
  }

  public record UpdateAppearanceRequest(
      Integer version,
      @NotBlank String defaultPalette
  ) {
  }
}
