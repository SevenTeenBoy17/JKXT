package com.jkxt.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.common.config.DeployProperties;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** The real accounts projection is under test; only the JDBC result boundary is replaced. */
class AdminAccountSecurityProjectionTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void initialPasswordStateMustNeverMasqueradeAsSecondFactorEnrollment(boolean mustChangePassword)
      throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ResultSet result = mock(ResultSet.class);
    UUID accountId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    when(result.getObject("id", UUID.class)).thenReturn(accountId);
    when(result.getString("username")).thenReturn("isolated.account");
    when(result.getString("display_name")).thenReturn("Isolated Account");
    when(result.getString("tenant_slug")).thenReturn("isolated-school");
    when(result.getString("role")).thenReturn("ADMIN");
    when(result.getString("status")).thenReturn("active");
    when(result.getBoolean("must_change_password")).thenReturn(mustChangePassword);
    when(jdbc.query(anyString(), any(Object[].class),
        org.mockito.ArgumentMatchers.<RowMapper<Map<String, Object>>>any()))
        .thenAnswer(invocation -> {
          RowMapper<Map<String, Object>> productionMapper = invocation.getArgument(2);
          return List.of(productionMapper.mapRow(result, 0));
        });

    DeployProperties deployment = new DeployProperties();
    deployment.setMode("private");
    AdminPlatformService service = new AdminPlatformService(jdbc, null, deployment, null, null, null);
    List<Map<String, Object>> accounts = service.accounts();
    assertThat(accounts).hasSize(1);
    Map<String, Object> account = accounts.getFirst();
    assertThat(account).containsEntry("id", accountId.toString())
        .containsEntry("username", "isolated.account")
        .containsEntry("role", "admin")
        .containsEntry("twoFactor", null)
        .containsEntry("twoFactorSupported", false)
        .containsEntry("mustChangePassword", mustChangePassword);

    // Preserve the old response key without inventing a boolean enrollment state.
    JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(account));
    assertThat(json.has("twoFactor")).isTrue();
    assertThat(json.path("twoFactor").isNull()).isTrue();
    assertThat(json.path("twoFactorSupported").isBoolean()).isTrue();
    assertThat(json.path("twoFactorSupported").booleanValue()).isFalse();
    assertThat(json.path("mustChangePassword").booleanValue()).isEqualTo(mustChangePassword);
  }
}
