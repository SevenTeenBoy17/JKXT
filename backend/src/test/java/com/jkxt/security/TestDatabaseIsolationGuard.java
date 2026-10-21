package com.jkxt.security;

import java.net.URI;
import java.util.Set;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

/** Fail closed before Spring creates any database or Redis infrastructure. */
public final class TestDatabaseIsolationGuard
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  @Override
  public void initialize(ConfigurableApplicationContext context) {
    ConfigurableEnvironment environment = context.getEnvironment();
    String jdbcUrl = required(environment, "JKXT_TEST_DATASOURCE_URL");
    requireMatch(environment, "spring.datasource.url", jdbcUrl);
    requireMatch(environment, "spring.datasource.username",
        required(environment, "JKXT_TEST_DATASOURCE_USERNAME"));
    requireMatch(environment, "spring.datasource.password",
        required(environment, "JKXT_TEST_DATASOURCE_PASSWORD"));
    if (!jdbcUrl.startsWith("jdbc:postgresql://")) reject("PostgreSQL JDBC URL required");
    URI database;
    try {
      database = URI.create(jdbcUrl.substring("jdbc:".length()));
    } catch (IllegalArgumentException exception) {
      throw failure("Invalid test JDBC URL");
    }
    if (database.getPath() == null || !database.getPath().matches("/jkxt_test_[a-z0-9_]+")
        || database.getUserInfo() != null || database.getRawQuery() != null
        || database.getFragment() != null) {
      reject("Database must be named jkxt_test_<run> without URL overrides");
    }
    requireIsolatedEndpoint(database.getHost(), database.getPort(), 5432);
    requireOptionalMatch(environment, "spring.datasource.hikari.jdbc-url", jdbcUrl);
    requireOptionalMatch(environment, "spring.flyway.url", jdbcUrl);

    String redisHost = required(environment, "JKXT_TEST_REDIS_HOST");
    String redisPort = required(environment, "JKXT_TEST_REDIS_PORT");
    requireMatch(environment, "spring.data.redis.host", redisHost);
    requireMatch(environment, "spring.data.redis.port", redisPort);
    int port;
    try {
      port = Integer.parseInt(redisPort);
    } catch (NumberFormatException exception) {
      throw failure("Invalid test Redis port");
    }
    requireIsolatedEndpoint(redisHost, port, 6379);
    for (String key : Set.of("spring.data.redis.url", "spring.data.redis.cluster.nodes",
        "spring.data.redis.sentinel.master", "spring.data.redis.sentinel.nodes")) {
      if (environment.getProperty(key) != null) reject("Redis endpoint overrides are forbidden");
    }
  }

  private static void requireIsolatedEndpoint(String host, int port, int standardPort) {
    boolean dedicatedContainer = host != null && host.matches("jkxt-test-[a-z0-9-]+");
    boolean mappedLoopback = Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(host == null ? "" : host)
        && port >= 1024 && port != standardPort;
    if (port < 1 || port > 65535 || (!dedicatedContainer && !mappedLoopback)) {
      reject("Use dedicated jkxt-test-* containers or nonstandard loopback ports");
    }
  }

  private static String required(ConfigurableEnvironment environment, String key) {
    String value = environment.getProperty(key);
    if (value == null || value.isBlank()) reject("Missing explicit " + key);
    return value;
  }

  private static void requireMatch(ConfigurableEnvironment environment, String key, String expected) {
    if (!expected.equals(environment.getProperty(key))) reject("Conflicting override for " + key);
  }

  private static void requireOptionalMatch(ConfigurableEnvironment environment, String key, String expected) {
    String actual = environment.getProperty(key);
    if (actual != null && !expected.equals(actual)) reject("Conflicting override for " + key);
  }

  private static void reject(String reason) {
    throw failure(reason);
  }

  private static IllegalStateException failure(String reason) {
    // Never echo connection strings or credentials into test reports.
    return new IllegalStateException("TEST_DATABASE_ISOLATION_REQUIRED: " + reason);
  }
}
