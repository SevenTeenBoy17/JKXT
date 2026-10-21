package com.jkxt.ai.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class LlmTestModeGuardTest {
  @Test
  void fixtureFlagIsRejectedOutsideTestProfile() {
    LlmProperties properties = new LlmProperties();
    properties.setAllowTestMocks(true);
    assertThatThrownBy(() -> new LlmTestModeGuard(properties, new MockEnvironment()).validate())
        .isInstanceOf(IllegalStateException.class);
    MockEnvironment test = new MockEnvironment();
    test.setActiveProfiles("test");
    assertThatCode(() -> new LlmTestModeGuard(properties, test).validate()).doesNotThrowAnyException();
  }
}
