package com.jkxt.ai.config;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** An explicit fixture flag must never enable canned model output in an operational profile. */
@Component
public class LlmTestModeGuard {
  private final LlmProperties properties;
  private final Environment environment;

  public LlmTestModeGuard(LlmProperties properties, Environment environment) {
    this.properties = properties;
    this.environment = environment;
  }

  @PostConstruct
  public void validate() {
    if (properties.isAllowTestMocks() && !environment.acceptsProfiles(Profiles.of("test"))) {
      throw new IllegalStateException("LLM test fixtures require the test profile");
    }
  }
}
