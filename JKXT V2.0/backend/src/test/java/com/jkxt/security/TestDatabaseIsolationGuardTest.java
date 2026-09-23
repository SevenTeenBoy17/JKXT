package com.jkxt.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.env.MockEnvironment;

class TestDatabaseIsolationGuardTest {
  @Test
  void absentConfigurationFailsBeforeContextRefresh() {
    assertThatThrownBy(() -> initialize(new MockEnvironment()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TEST_DATABASE_ISOLATION_REQUIRED");
  }

  @Test
  void testProfileAutomaticallyRunsGuardBeforeCreatingBeans() {
    ProbeConfiguration.refreshed.set(false);
    SpringApplication application = new SpringApplication(ProbeConfiguration.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setLogStartupInfo(false);
    assertThatThrownBy(() -> application.run("--spring.profiles.active=test", "--JKXT_TEST_DATASOURCE_URL="))
        .hasStackTraceContaining("TEST_DATABASE_ISOLATION_REQUIRED");
    assertThat(ProbeConfiguration.refreshed).isFalse();
  }

  @Test
  void dedicatedContainersAndMappedTestcontainersPortsAreAllowed() {
    assertThatCode(() -> initialize(isolated())).doesNotThrowAnyException();
    MockEnvironment mapped = isolated();
    setDatabase(mapped, "jdbc:postgresql://localhost:35432/jkxt_test_unit");
    setRedis(mapped, "127.0.0.1", "36379");
    assertThatCode(() -> initialize(mapped)).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "jdbc:postgresql://host.docker.internal:5432/jkxt",
      "jdbc:postgresql://jkxt-test-postgres:5432/jkxt",
      "jdbc:postgresql://localhost:5432/jkxt_test_unit",
      "jdbc:postgresql://jkxt-test-postgres:5432/jkxt_test_unit?currentSchema=public"
  })
  void unsafeDatabaseTargetsAreRejected(String url) {
    MockEnvironment environment = isolated();
    setDatabase(environment, url);
    assertThatThrownBy(() -> initialize(environment)).hasMessageContaining("TEST_DATABASE_ISOLATION_REQUIRED");
  }

  @Test
  void businessRedisAndConnectionOverridesAreRejectedWithoutLeakingSecrets() {
    MockEnvironment sharedRedis = isolated();
    setRedis(sharedRedis, "host.docker.internal", "6379");
    assertThatThrownBy(() -> initialize(sharedRedis)).hasMessageContaining("TEST_DATABASE_ISOLATION_REQUIRED");
    MockEnvironment override = isolated().withProperty("spring.datasource.url", "jdbc:postgresql://business:5432/jkxt");
    assertThatThrownBy(() -> initialize(override)).hasMessageContaining("Conflicting override")
        .hasMessageNotContaining("test-secret").hasMessageNotContaining("business:5432");
    MockEnvironment redisUrl = isolated().withProperty("spring.data.redis.url", "redis://business:6379");
    assertThatThrownBy(() -> initialize(redisUrl)).hasMessageContaining("Redis endpoint overrides");
  }

  private static MockEnvironment isolated() {
    MockEnvironment environment = new MockEnvironment()
        .withProperty("JKXT_TEST_DATASOURCE_USERNAME", "test-user")
        .withProperty("spring.datasource.username", "test-user")
        .withProperty("JKXT_TEST_DATASOURCE_PASSWORD", "test-secret")
        .withProperty("spring.datasource.password", "test-secret");
    setDatabase(environment, "jdbc:postgresql://jkxt-test-postgres:5432/jkxt_test_unit");
    setRedis(environment, "jkxt-test-redis", "6379");
    return environment;
  }

  private static void setDatabase(MockEnvironment environment, String url) {
    environment.setProperty("JKXT_TEST_DATASOURCE_URL", url);
    environment.setProperty("spring.datasource.url", url);
  }

  private static void setRedis(MockEnvironment environment, String host, String port) {
    environment.setProperty("JKXT_TEST_REDIS_HOST", host);
    environment.setProperty("spring.data.redis.host", host);
    environment.setProperty("JKXT_TEST_REDIS_PORT", port);
    environment.setProperty("spring.data.redis.port", port);
  }

  private static void initialize(MockEnvironment environment) {
    try (GenericApplicationContext context = new GenericApplicationContext()) {
      context.setEnvironment(environment);
      new TestDatabaseIsolationGuard().initialize(context);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ProbeConfiguration {
    private static final AtomicBoolean refreshed = new AtomicBoolean();

    @Bean
    String refreshMarker() {
      refreshed.set(true);
      return "refreshed";
    }
  }
}
