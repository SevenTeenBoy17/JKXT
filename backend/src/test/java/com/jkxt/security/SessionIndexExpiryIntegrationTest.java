package com.jkxt.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import com.jkxt.security.auth.SessionPrincipal;
import com.jkxt.security.auth.SessionService;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Real PostgreSQL/Redis and security filters; each case owns its synthetic accounts and keys. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionIndexExpiryIntegrationTest {

  private static final String PASSWORD = "Isolated@2026";
  private final List<UUID> userIds = new ArrayList<>();
  private final List<Cookie> sessions = new ArrayList<>();
  private Account account;

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private StringRedisTemplate redis;
  @Autowired private SessionService sessionService;

  @BeforeEach
  void createIsolatedAccount() {
    account = createAccount("ADMIN");
  }

  @AfterEach
  void removeOnlyOwnedFixtures() {
    for (Cookie session : sessions) {
      redis.delete(tokenKey(session));
    }
    for (UUID id : userIds) {
      redis.delete(indexKey(id));
      jdbc.update("delete from app_user where id = ?", id);
    }
  }

  @Test
  void legitimateRequestRenewsTokenAndRevocationIndexTogether() throws Exception {
    Cookie session = login(account);
    assertThat(redis.expire(indexKey(account.id()), Duration.ofSeconds(30))).isTrue();
    mockMvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isOk());

    Long indexTtl = redis.getExpire(indexKey(account.id()), TimeUnit.MILLISECONDS);
    Long tokenTtl = redis.getExpire(tokenKey(session), TimeUnit.MILLISECONDS);
    assertThat(indexTtl).as("active session must retain its revocation index").isGreaterThan(600_000L);
    assertThat(tokenTtl).isGreaterThan(600_000L);
    assertThat(Math.abs(tokenTtl - indexTtl)).isLessThan(1000L);
    assertThat(redis.opsForSet().isMember(indexKey(account.id()), session.getValue())).isTrue();
  }

  @Test
  void passwordChangeCannotLeaveAnExpiredIndexSessionAuthenticated() throws Exception {
    Cookie oldDevice = login(account);
    expireIndexNaturally(account);
    assertThat(redis.hasKey(tokenKey(oldDevice))).isTrue();

    // A fresh legitimate login may recreate the index, but must not revive old unindexed tokens.
    Cookie currentDevice = login(account);
    String nextPassword = "Changed@2026";
    mockMvc.perform(post("/api/v1/auth/me/change-password").cookie(currentDevice)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "currentPassword", PASSWORD, "newPassword", nextPassword))))
        .andExpect(status().isOk());
    String storedHash = jdbc.queryForObject("select password_hash from app_user where id = ?",
        String.class, account.id());
    assertThat(passwordEncoder.matches(nextPassword, storedHash)).isTrue();
    mockMvc.perform(get("/api/v1/auth/me").cookie(currentDevice)).andExpect(status().isOk());
    assertRejectedAndRemoved(oldDevice);
    assertThat(redis.opsForSet().members(indexKey(account.id())))
        .containsExactly(currentDevice.getValue());
  }

  @Test
  void disableAccountRejectsTokenWhoseRevocationIndexHasExpired() throws Exception {
    Cookie oldDevice = login(account);
    expireIndexNaturally(account);
    assertThat(redis.hasKey(tokenKey(oldDevice))).isTrue();
    Cookie admin = login(createAccount("SUPER_ADMIN"));

    mockMvc.perform(post("/api/v1/admin/accounts/" + account.id() + "/disable").cookie(admin))
        .andExpect(status().isOk());
    assertThat(jdbc.queryForObject("select status from app_user where id = ?", String.class,
        account.id())).isEqualTo("disabled");
    assertRejectedAndRemoved(oldDevice);
    assertThat(redis.hasKey(indexKey(account.id()))).isFalse();
  }

  @Test
  void missingMembershipFailsClosedWithoutRebuildingAuthorization() throws Exception {
    Cookie orphan = login(account);
    Cookie valid = login(account);
    assertThat(redis.opsForSet().remove(indexKey(account.id()), orphan.getValue())).isEqualTo(1L);
    assertThat(redis.hasKey(tokenKey(orphan))).isTrue();

    assertRejectedAndRemoved(orphan);
    mockMvc.perform(get("/api/v1/auth/me").cookie(valid)).andExpect(status().isOk());
    assertThat(redis.opsForSet().members(indexKey(account.id()))).containsExactly(valid.getValue());
  }

  @Test
  void loggedOutTokenIsNotRecreatedBySubsequentRequests() throws Exception {
    Cookie session = login(account);
    mockMvc.perform(post("/api/v1/auth/logout").cookie(session)).andExpect(status().isOk());
    assertThat(redis.hasKey(tokenKey(session))).isFalse();
    assertRejectedAndRemoved(session);
    assertRejectedAndRemoved(session);
  }

  @Test
  void revokedAfterPayloadReadMustNotAuthenticateOrRenew() throws Exception {
    Cookie session = login(account);
    AtomicBoolean competingRevokeRan = new AtomicBoolean();
    ObjectMapper interleavedReader = new ObjectMapper() {
      @Override
      public <T> T readValue(String content, Class<T> type) throws JsonProcessingException {
        T parsed = objectMapper.readValue(content, type);
        if (type == SessionPrincipal.class && competingRevokeRan.compareAndSet(false, true)) {
          // Real GET already happened. Deterministically execute the competing real Redis revoke
          // before find can refresh or authenticate; no fake Redis result or manufactured principal.
          sessionService.revoke(session.getValue());
        }
        return parsed;
      }
    };
    assertThat(serviceWith(redis, interleavedReader).find(session.getValue())).isEmpty();
    assertThat(competingRevokeRan).isTrue();
    assertRejectedAndRemoved(session);
  }

  @Test
  void retainingCurrentDeviceMustNotExposeAnEmptyAuthorizationIndex() throws Exception {
    Cookie retained = login(account);
    Cookie other = login(account);
    AtomicBoolean retainedWasValidDuringReplacement = new AtomicBoolean(true);
    StringRedisTemplate interleavedRedis = new StringRedisTemplate(redis.getConnectionFactory()) {
      @Override
      public Boolean delete(String key) {
        Boolean result = super.delete(key);
        if (key.equals(indexKey(account.id()))) {
          // Model another request in a client-side DEL -> SADD gap using the actual find + Redis.
          // Atomic server-side replacement must not expose this intermediate state to clients.
          retainedWasValidDuringReplacement.set(sessionService.find(retained.getValue()).isPresent());
        }
        return result;
      }
    };
    serviceWith(interleavedRedis, objectMapper).revokeOtherSessions(account.id(), retained.getValue());
    assertThat(retainedWasValidDuringReplacement).as("retained device cannot be falsely revoked").isTrue();
    mockMvc.perform(get("/api/v1/auth/me").cookie(retained)).andExpect(status().isOk());
    assertRejectedAndRemoved(other);
    assertThat(redis.opsForSet().members(indexKey(account.id()))).containsExactly(retained.getValue());
  }

  @Test
  void retainingAlreadyLoggedOutDeviceCannotRecreateItsMembership() throws Exception {
    Cookie removed = login(account);
    Cookie other = login(account);
    mockMvc.perform(post("/api/v1/auth/logout").cookie(removed)).andExpect(status().isOk());
    assertThat(redis.hasKey(tokenKey(removed))).isFalse();

    sessionService.revokeOtherSessions(account.id(), removed.getValue());
    assertThat(redis.opsForSet().isMember(indexKey(account.id()), removed.getValue())).isFalse();
    assertRejectedAndRemoved(removed);
    assertRejectedAndRemoved(other);
  }

  @Test
  void retainingUnindexedTokenCannotReauthorizeIt() throws Exception {
    Cookie orphan = login(account);
    Cookie other = login(account);
    assertThat(redis.opsForSet().remove(indexKey(account.id()), orphan.getValue())).isEqualTo(1L);
    assertThat(redis.hasKey(tokenKey(orphan))).isTrue();

    sessionService.revokeOtherSessions(account.id(), orphan.getValue());
    assertThat(redis.opsForSet().isMember(indexKey(account.id()), orphan.getValue())).isFalse();
    assertRejectedAndRemoved(orphan);
    assertRejectedAndRemoved(other);
  }

  private SessionService serviceWith(StringRedisTemplate store, ObjectMapper mapper) {
    return new SessionService(store, mapper, "JKXT_SCHOOL_SESSION", false, "Lax", 604800);
  }

  private Account createAccount(String role) {
    UUID id = UUID.randomUUID();
    String username = "release.session." + id;
    UUID org = jdbc.queryForObject("select org_id from app_user where username = 'jwc.demo'", UUID.class);
    jdbc.update("""
        insert into app_user
          (id, org_id, username, display_name, email, phone, password_hash, role, status,
           must_change_password, onboarded)
        values (?, ?, ?, ?, ?, ?, ?, ?, 'active', false, true)
        """, id, org, username, "隔离会话回归", username + "@example.invalid", "",
        passwordEncoder.encode(PASSWORD), role);
    userIds.add(id);
    return new Account(id, username);
  }

  private Cookie login(Account target) throws Exception {
    MvcResult challenge = mockMvc.perform(post("/api/v1/auth/captcha"))
        .andExpect(status().isOk()).andReturn();
    CaptchaResponse captcha = objectMapper.readValue(challenge.getResponse().getContentAsString(),
        CaptchaResponse.class);
    MvcResult result = mockMvc.perform(post(com.jkxt.support.TestPortal.loginPath(jdbc, target.username()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("username", target.username(),
                "password", PASSWORD, "captchaId", captcha.captchaId(), "captcha", captcha.debugAnswer()))))
        .andExpect(status().isOk()).andReturn();
    Cookie cookie = com.jkxt.support.TestPortal.sessionCookie(result.getResponse());
    assertThat(cookie).isNotNull();
    sessions.add(cookie);
    return cookie;
  }

  private void expireIndexNaturally(Account target) {
    assertThat(redis.expire(indexKey(target.id()), Duration.ofMillis(1))).isTrue();
    await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(10))
        .until(() -> Boolean.FALSE.equals(redis.hasKey(indexKey(target.id()))));
  }

  private void assertRejectedAndRemoved(Cookie session) throws Exception {
    mockMvc.perform(get("/api/v1/auth/me").cookie(session))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
    assertThat(redis.hasKey(tokenKey(session))).isFalse();
  }

  private String tokenKey(Cookie cookie) { return "jkxt:session:" + cookie.getValue(); }
  private String indexKey(UUID userId) { return "jkxt:user-sessions:" + userId; }
  private record Account(UUID id, String username) {}
}
