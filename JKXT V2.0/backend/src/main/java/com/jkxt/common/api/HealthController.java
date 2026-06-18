package com.jkxt.common.api;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${jkxt.api-base:/api/v1}")
public class HealthController {

  private final String version;

  public HealthController(@Value("${jkxt.version:0.1.0-p1}") String version) {
    this.version = version;
  }

  @GetMapping("/health")
  public HealthResponse health() {
    return new HealthResponse("UP", "jkxt-backend", "P1", version, Instant.now());
  }

  public record HealthResponse(
      String status,
      String service,
      String stage,
      String version,
      Instant timestamp
  ) {
  }
}
