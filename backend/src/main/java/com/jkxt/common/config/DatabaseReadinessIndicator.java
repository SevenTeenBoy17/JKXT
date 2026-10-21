package com.jkxt.common.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** A bounded, read-only probe. Its network deadline never changes business query deadlines. */
@Component("dbHealthIndicator")
public final class DatabaseReadinessIndicator implements HealthIndicator {
  private final DataSource dataSource;
  private final int networkTimeoutMs;

  public DatabaseReadinessIndicator(DataSource dataSource,
      @Value("${jkxt.health.database-network-timeout-ms:2000}") int networkTimeoutMs) {
    if (networkTimeoutMs < 100 || networkTimeoutMs > 10000) {
      throw new IllegalArgumentException("Database probe network timeout must be between 100 and 10000 ms");
    }
    this.dataSource = dataSource;
    this.networkTimeoutMs = networkTimeoutMs;
  }

  @Override
  public Health health() {
    // Hikari separately bounds acquisition. A connected but unresponsive PostgreSQL
    // server also needs a socket bound: statement cancellation itself may need the network.
    try (Connection connection = dataSource.getConnection()) {
      int previous = connection.getNetworkTimeout();
      try {
        connection.setNetworkTimeout(Runnable::run, networkTimeoutMs);
        try (Statement statement = connection.createStatement()) {
          statement.setQueryTimeout(Math.max(1, (networkTimeoutMs + 999) / 1000));
          try (ResultSet result = statement.executeQuery("SELECT 1")) {
            if (result.next() && result.getInt(1) == 1) return Health.up().build();
            return Health.down().withDetail("reason", "DATABASE_PROBE_INVALID_RESULT").build();
          }
        }
      } finally {
        // Restore before returning a healthy pooled connection to business callers.
        // A failed PostgreSQL socket may already be closed and is evicted by Hikari.
        if (!connection.isClosed()) connection.setNetworkTimeout(Runnable::run, previous);
      }
    } catch (SQLException | RuntimeException unavailable) {
      // Do not expose JDBC URLs, credentials, or exception messages on a public health endpoint.
      return Health.down().withDetail("reason", "DATABASE_UNAVAILABLE").build();
    }
  }
}
