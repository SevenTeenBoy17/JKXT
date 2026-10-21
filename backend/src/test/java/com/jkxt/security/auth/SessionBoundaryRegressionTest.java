package com.jkxt.security.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.common.config.DeployProperties;
import com.jkxt.org.domain.Organization;
import com.jkxt.org.repo.OrganizationRepository;
import com.jkxt.security.domain.UserAccount;
import com.jkxt.security.repo.UserAccountRepository;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/** No Spring context or real storage: exercises the public filter/session boundary only. */
class SessionBoundaryRegressionTest {
  private final UUID userId = UUID.randomUUID();
  private final UUID orgId = UUID.randomUUID();
  private final SessionService sessions = mock(SessionService.class);
  private final OrganizationRepository organizations = mock(OrganizationRepository.class);
  private final UserAccountRepository users = mock(UserAccountRepository.class);
  private final DeployProperties deploy = new DeployProperties();
  private final UserAccount user = new UserAccount();
  private final Organization org = new Organization();

  SessionBoundaryRegressionTest() {
    user.setId(userId); user.setOrgId(orgId); user.setRole("ADMIN"); user.setStatus("active");
    org.setId(orgId); org.setStatus("active");
    when(users.findById(userId)).thenReturn(Optional.of(user));
    when(organizations.findById(orgId)).thenReturn(Optional.of(org));
    when(sessions.cookieName()).thenReturn("JKXT_SCHOOL_SESSION");
    when(sessions.cookieName(AuthPortal.SCHOOL)).thenReturn("JKXT_SCHOOL_SESSION");
    when(sessions.cookieName(AuthPortal.PLATFORM)).thenReturn("JKXT_PLATFORM_SESSION");
    when(sessions.find("token")).thenReturn(Optional.of(new SessionPrincipal(
        "token", userId, orgId, "account", "Account", "ADMIN", List.of("tenant:read", "masterdata:manage"),
        0, 0, 0, "SCHOOL")));
  }

  @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

  private MockHttpServletResponse request(String path) throws Exception {
    // Resolve production constructor dependencies, preserving this behavior test across injection changes.
    var constructor = SessionAuthenticationFilter.class.getConstructors()[0];
    Object[] dependencies = java.util.Arrays.stream(constructor.getParameterTypes()).map(type -> {
      if (type == SessionService.class) return sessions;
      if (type == DeployProperties.class) return deploy;
      if (type == OrganizationRepository.class) return organizations;
      if (type == UserAccountRepository.class) return users;
      if (type == ObjectMapper.class) return new ObjectMapper();
      throw new IllegalStateException("Unconfigured boundary dependency " + type);
    }).toArray();
    var filter = (SessionAuthenticationFilter) constructor.newInstance(dependencies);
    var request = new MockHttpServletRequest("POST", path);
    request.setCookies(new Cookie("JKXT_SCHOOL_SESSION", "token"));
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response;
  }

  @Test void disabledAccountCannotUseAnOtherwiseValidRedisSession() throws Exception {
    user.setStatus("disabled");
    assertThat(request("/api/v1/classes").getStatus()).isEqualTo(401);
  }

  @Test void suspendedOrganizationCannotUseAnOtherwiseValidRedisSession() throws Exception {
    org.setStatus("suspended");
    assertThat(request("/api/v1/classes").getStatus()).isEqualTo(401);
  }

  @Test void temporaryPasswordSessionCannotWriteBusinessData() throws Exception {
    user.setMustChangePassword(true);
    assertThat(request("/api/v1/classes").getStatus()).isEqualTo(403);
  }

  @Test void temporaryPasswordCanReachPasswordChange() throws Exception {
    user.setMustChangePassword(true);
    assertThat(request("/api/v1/auth/me/change-password").getStatus()).isEqualTo(200);
  }

  @Test void credentialVersionMismatchRejectsEvenAnIndexedToken() throws Exception {
    user.setAuthVersion(1);
    assertThat(request("/api/v1/classes").getStatus()).isEqualTo(401);
  }

  @Test void accountDatabaseUnavailableFailsClosed() throws Exception {
    when(users.findById(userId)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("unavailable"));
    assertThat(request("/api/v1/classes").getStatus()).isEqualTo(503);
  }

  @Test void expiredAbsoluteDeadlineCannotBeRenewedEvenWhenRedisStillContainsToken() throws Exception {
    var redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    var mapper = new ObjectMapper();
    var expired = new SessionPrincipal("expired", userId, orgId, "user", "User", "ADMIN", List.of(),
        0, 1_000_000, 1_001_000);
    when(values.get("jkxt:session:expired")).thenReturn(mapper.writeValueAsString(expired));
    var service = new SessionService(redis, mapper, "JKXT_SCHOOL_SESSION", false, "Lax", 600, 1200,
        Clock.fixed(Instant.ofEpochMilli(1_001_000), ZoneOffset.UTC));
    assertThat(service.find("expired")).isEmpty();
    verify(redis).delete("jkxt:session:expired");
  }

  @Test void legacyTokenWithoutAbsoluteExpiryIsRejected() throws Exception {
    var redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    var mapper = new ObjectMapper();
    String payload = "{\"sessionId\":\"legacy\",\"userId\":\"" + userId
        + "\",\"orgId\":\"" + orgId + "\",\"username\":\"account\",\"displayName\":\"Account\","
        + "\"role\":\"ADMIN\",\"permissions\":[\"tenant:read\"]}";
    when(values.get("jkxt:session:legacy")).thenReturn(payload);
    when(redis.execute(any(), any(List.class), any(Object[].class))).thenReturn(1L);
    var service = new SessionService(redis, mapper, "JKXT_SCHOOL_SESSION", false, "Lax", 604800);
    assertThat(service.find("legacy")).isEmpty();
  }
}
