package com.jkxt.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LlmProfileConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withInitializer(new ConfigDataApplicationContextInitializer())
      .withUserConfiguration(TestConfiguration.class)
      .withPropertyValues("spring.profiles.active=saas");

  @Test
  void saasProfileKeepsRealProviderAndSupportedEndpointDefaults() {
    contextRunner.run(context -> {
      assertThat(context).hasNotFailed();
      LlmProperties properties = context.getBean(LlmProperties.class);
      assertThat(properties.isEnabled()).isFalse();
      assertThat(properties.isAllowTestMocks()).isFalse();
      assertThat(properties.getProvider()).isEqualTo("aliyun");
      assertThat(properties.getBaseUrl()).isEmpty();
      assertThat(properties.getFallbackBaseUrl()).isEmpty();
      assertThat(properties.getModel()).isEqualTo("qwen-plus");
      assertThat(properties.getMaxRetries()).isEqualTo(2);
    });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(LlmProperties.class)
  static class TestConfiguration {
  }
}
