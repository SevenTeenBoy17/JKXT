package com.jkxt.security;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** One-time, non-Web provisioning boundary. Never registered as a Spring bean. */
public final class InitialOperatorProvisioner {
  @FunctionalInterface
  interface Connections { Connection open() throws SQLException; }
  record Request(UUID organizationId, String organizationSlug, String username, String displayName, char[] password) {}
  private final Connections connections;
  InitialOperatorProvisioner(Connections connections) { this.connections = connections; }
  UUID provision(Request request) throws SQLException {
    validate(request);
    String username = request.username().trim().toLowerCase(Locale.ROOT);
    // Hash before acquiring locks; no password is interpolated into SQL or audit data.
    String hash = new BCryptPasswordEncoder(12).encode(CharBuffer.wrap(request.password()));
    try (Connection connection = connections.open()) {
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      try {
        try (var statement = connection.createStatement()) {
          statement.execute("set local search_path = public");
          statement.execute("set local lock_timeout = '5s'");
          statement.execute("set local statement_timeout = '15s'");
          // Serializes this tool across all organizations, not just one school's row.
          statement.execute("select pg_advisory_xact_lock(74281953160428031)");
        }
        try (PreparedStatement statement = connection.prepareStatement(
            "select status,slug from organization where id=? for update")) {
          statement.setObject(1, request.organizationId());
          try (ResultSet result = statement.executeQuery()) {
            if (!result.next() || !"active".equals(result.getString(1))
                || !request.organizationSlug().trim().equals(result.getString(2))) {
              throw new IllegalStateException("An exact active organization UUID and slug are required");
            }
          }
        }
        try (var statement = connection.createStatement()) {
          // Also excludes non-cooperating account writers while checking global uniqueness.
          // Run in a maintenance window; locks are bounded and released on every exit.
          statement.execute("lock table app_user in share row exclusive mode");
          try (ResultSet result = statement.executeQuery(
              "select exists(select 1 from flyway_schema_history where version='31' and success)")) {
            result.next();
            if (!result.getBoolean(1)) throw new IllegalStateException("Schema migration V31 is required");
          }
          try (ResultSet result = statement.executeQuery("select username,role,status from app_user")) {
            while (result.next()) {
              String existing = result.getString(1).toLowerCase(Locale.ROOT);
              boolean demo = DemoAccountSecurityGuard.DEMO_USERNAMES.contains(existing);
              // Disabled and soft-deleted formal operators are not permission to re-bootstrap.
              if ("SUPER_ADMIN".equals(result.getString(2)) && !demo) {
                throw new IllegalStateException("A formal SUPER_ADMIN already exists; use account recovery instead");
              }
              if (demo && !"disabled".equals(result.getString(3))) {
                throw new IllegalStateException("All seeded demo accounts must be disabled first");
              }
              if (existing.equals(username)) throw new IllegalStateException("The username already exists");
            }
          }
        }
        UUID userId = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
            insert into app_user(id,org_id,username,display_name,password_hash,role,status,must_change_password,onboarded,auth_version)
            values (?,?,?,?,?,'SUPER_ADMIN','active',true,false,0)
            """)) {
          statement.setObject(1, userId);
          statement.setObject(2, request.organizationId());
          statement.setString(3, username);
          statement.setString(4, request.displayName().trim());
          statement.setString(5, hash);
          statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            insert into audit_log(org_id,actor_id,action,target_type,target_id,severity,payload)
            values (?,?,'INITIAL_OPERATOR_CREATED','app_user',?,'high','{"source":"interactive-cli","mustChangePassword":true}'::jsonb)
            """)) {
          statement.setObject(1, request.organizationId());
          statement.setObject(2, userId);
          statement.setObject(3, userId);
          statement.executeUpdate();
        }
        connection.commit();
        return userId;
      } catch (SQLException | RuntimeException failure) {
        try { connection.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
        throw failure;
      }
    }
  }

  static void validate(Request request) {
    if (request == null || request.organizationId() == null || request.organizationId().equals(new UUID(0, 0))
        || request.organizationSlug() == null || !request.organizationSlug().trim().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,59}")
        || request.username() == null || !request.username().trim().matches("[A-Za-z0-9][A-Za-z0-9._-]{2,59}")
        || DemoAccountSecurityGuard.DEMO_USERNAMES.contains(request.username().trim().toLowerCase(Locale.ROOT))
        || request.displayName() == null || request.displayName().isBlank() || request.displayName().trim().length() > 80
        || request.displayName().chars().anyMatch(Character::isISOControl)
        || request.password() == null || request.password().length < 12
        || CharBuffer.wrap(request.password()).chars().anyMatch(Character::isISOControl)
        || CharBuffer.wrap(request.password()).chars().noneMatch(Character::isLetter)
        || CharBuffer.wrap(request.password()).chars().noneMatch(Character::isDigit)) {
      throw new IllegalArgumentException("Invalid initial operator input");
    }
    var encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(request.password()));
    try {
      if (encoded.remaining() > 72 || CharBuffer.wrap(request.password()).toString().equalsIgnoreCase(request.username().trim())) {
        throw new IllegalArgumentException("Invalid initial operator password");
      }
    } finally {
      if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
    }
  }
}
