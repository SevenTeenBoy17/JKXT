package com.jkxt.common.api;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${jkxt.api-base:/api/v1}")
public class VersionController {

  private final String applicationName;
  private final String version;
  private final String apiBase;

  public VersionController(
      @Value("${spring.application.name:jkxt-backend}") String applicationName,
      @Value("${jkxt.version:0.1.0-p1}") String version,
      @Value("${jkxt.api-base:/api/v1}") String apiBase
  ) {
    this.applicationName = applicationName;
    this.version = version;
    this.apiBase = apiBase;
  }

  @GetMapping("/version")
  public VersionResponse version() {
    return new VersionResponse(
        applicationName,
        version,
        "P1",
        apiBase,
        Runtime.version().feature(),
        Instant.now()
    );
  }

  public record VersionResponse(
      String application,
      String version,
      String stage,
      String apiBase,
      int javaFeature,
      Instant timestamp
  ) {
  }
}
