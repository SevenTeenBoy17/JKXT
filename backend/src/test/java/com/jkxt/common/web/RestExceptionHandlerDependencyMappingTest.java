package com.jkxt.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisCommandTimeoutException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;

/**
 * Round 9:
 * PB-6 (AUTH-05) - a Redis outage on the login entry is a controlled 503 SERVICE_DEPENDENCY_UNAVAILABLE,
 *        not 409 RESOURCE_CONFLICT ("reload and retry") or a 500;
 * PB-9 (DATA-02) - a unique-index violation (concurrent duplicate) is 409 CONFLICT, the same answer as a
 *        sequential duplicate; other constraint violations stay 400 DATA_CONSTRAINT_VIOLATION.
 */
class RestExceptionHandlerDependencyMappingTest {

  private final RestExceptionHandler handler = new RestExceptionHandler();

  @Test
  void redisTimeoutIsDependencyUnavailableNotWriteConflict() {
    var response = handler.handleWriteConflict(
        new QueryTimeoutException("Redis command timed out", new RedisCommandTimeoutException("timeout")));
    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getBody().code()).isEqualTo("SERVICE_DEPENDENCY_UNAVAILABLE");
    assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("5");
  }

  @Test
  void redisConnectionFailureAndSystemErrorsAreDependencyUnavailable() {
    assertThat(handler.handleResourceFailure(new RedisConnectionFailureException("down"))
        .getStatusCode().value()).isEqualTo(503);
    var system = handler.handleUnexpected(new RedisSystemException("boom", new RuntimeException("x")));
    assertThat(system.getStatusCode().value()).isEqualTo(503);
    assertThat(system.getBody().code()).isEqualTo("SERVICE_DEPENDENCY_UNAVAILABLE");
  }

  @Test
  void databaseTransientFailureStaysARetryableConflict() {
    var response = handler.handleWriteConflict(new QueryTimeoutException("statement timeout"));
    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(response.getBody().code()).isEqualTo("RESOURCE_CONFLICT");
  }

  @Test
  void uniqueViolationIsAConflictLikeASequentialDuplicate() {
    var duplicate = handler.handleDataIntegrity(new DuplicateKeyException("duplicate key"));
    assertThat(duplicate.getStatusCode().value()).isEqualTo(409);
    assertThat(duplicate.getBody().code()).isEqualTo("CONFLICT");
    var wrapped = handler.handleDataIntegrity(new DataIntegrityViolationException("dup",
        new SQLException("duplicate key value violates unique constraint", "23505")));
    assertThat(wrapped.getStatusCode().value()).isEqualTo(409);
    var tooLong = handler.handleDataIntegrity(new DataIntegrityViolationException("too long",
        new SQLException("value too long for type character varying(64)", "22001")));
    assertThat(tooLong.getStatusCode().value()).isEqualTo(400);
    assertThat(tooLong.getBody().code()).isEqualTo("DATA_CONSTRAINT_VIOLATION");
  }
}
