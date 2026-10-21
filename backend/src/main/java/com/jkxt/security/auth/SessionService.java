package com.jkxt.security.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class SessionService {

  private static final String KEY_PREFIX = "jkxt:session:";
  // 每个用户的会话索引(sessionId 集)，使改密码等操作能撤销该用户的其它会话(无需扫描全部 key)。
  private static final String USER_KEY_PREFIX = "jkxt:user-sessions:";

  // A token without its revocation-index membership is no longer authorized. Compare the payload
  // again inside Redis so a revoke between GET/JSON decoding and this script cannot authenticate it.
  private static final DefaultRedisScript<Long> REFRESH_SESSION = new DefaultRedisScript<>("""
      if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
      if redis.call('SISMEMBER', KEYS[2], ARGV[2]) ~= 1 then
        redis.call('DEL', KEYS[1])
        return 0
      end
      redis.call('PEXPIRE', KEYS[2], ARGV[4])
      redis.call('PEXPIRE', KEYS[1], ARGV[3])
      return 1
      """, Long.class);

  // Replace the index atomically: a retained device must never observe a DEL -> SADD gap.
  // KEYS contains every accessed key explicitly (index, retained token, other observed tokens).
  // A concurrent login after this revocation still needs separate account-version fencing.
  private static final DefaultRedisScript<Long> REVOKE_OTHER_SESSIONS = new DefaultRedisScript<>("""
      local keepActive = ARGV[1] ~= ''
          and redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1
          and redis.call('EXISTS', KEYS[2]) == 1
      local removed = 0
      for i = 3, #KEYS do
        removed = removed + redis.call('DEL', KEYS[i])
      end
      redis.call('DEL', KEYS[1])
      if keepActive then
        redis.call('SADD', KEYS[1], ARGV[1])
        redis.call('PEXPIRE', KEYS[1], ARGV[2])
      end
      return removed
      """, Long.class);

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  // PB-4: one cookie per login entry (spec 2026-09-20 §4.2). A Secure deployment uses the __Host- prefix.
  private final String schoolCookieName;
  private final String platformCookieName;
  private final boolean cookieSecure;
  private final String sameSite;
  private final Duration sessionTtl;
  private final Duration absoluteTtl;
  private final Clock clock;

  public SessionService(StringRedisTemplate redis, ObjectMapper objectMapper, String cookieName,
      boolean cookieSecure, String sameSite, long sessionTtlSeconds) {
    this(redis, objectMapper, cookieName, "JKXT_PLATFORM_SESSION", cookieSecure, sameSite, sessionTtlSeconds, 604800,
        Clock.systemUTC());
  }

  @Autowired
  public SessionService(
      StringRedisTemplate redis,
      ObjectMapper objectMapper,
      @Value("${jkxt.auth.school-cookie-name:JKXT_SCHOOL_SESSION}") String schoolCookieName,
      @Value("${jkxt.auth.platform-cookie-name:JKXT_PLATFORM_SESSION}") String platformCookieName,
      @Value("${jkxt.auth.cookie-secure:false}") boolean cookieSecure,
      @Value("${jkxt.auth.cookie-same-site:Lax}") String sameSite,
      @Value("${jkxt.auth.session-ttl-seconds:604800}") long sessionTtlSeconds,
      @Value("${jkxt.auth.session-absolute-ttl-seconds:604800}") long absoluteTtlSeconds
  ) {
    this(redis, objectMapper, schoolCookieName, platformCookieName, cookieSecure, sameSite, sessionTtlSeconds,
        absoluteTtlSeconds, Clock.systemUTC());
  }

  SessionService(StringRedisTemplate redis, ObjectMapper objectMapper, String cookieName,
      boolean cookieSecure, String sameSite, long sessionTtlSeconds, long absoluteTtlSeconds, Clock clock) {
    this(redis, objectMapper, cookieName, "JKXT_PLATFORM_SESSION", cookieSecure, sameSite, sessionTtlSeconds,
        absoluteTtlSeconds, clock);
  }

  SessionService(StringRedisTemplate redis, ObjectMapper objectMapper, String schoolCookieName,
      String platformCookieName, boolean cookieSecure, String sameSite, long sessionTtlSeconds,
      long absoluteTtlSeconds, Clock clock) {
    if (schoolCookieName == null || schoolCookieName.isBlank() || platformCookieName == null
        || platformCookieName.isBlank() || hostPrefixed(schoolCookieName, cookieSecure)
        .equals(hostPrefixed(platformCookieName, cookieSecure))) {
      throw new IllegalArgumentException("School and platform session cookies must have distinct names");
    }
    if (sessionTtlSeconds <= 0 || absoluteTtlSeconds <= 0) {
      throw new IllegalArgumentException("Session lifetimes must be positive");
    }
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.schoolCookieName = hostPrefixed(schoolCookieName, cookieSecure);
    this.platformCookieName = hostPrefixed(platformCookieName, cookieSecure);
    this.cookieSecure = cookieSecure;
    this.sameSite = sameSite;
    this.sessionTtl = Duration.ofSeconds(sessionTtlSeconds);
    this.absoluteTtl = Duration.ofSeconds(absoluteTtlSeconds);
    this.clock = clock;
  }

  private static String hostPrefixed(String name, boolean secure) {
    String trimmed = name == null ? "" : name.trim();
    return secure && !trimmed.startsWith("__Host-") ? "__Host-" + trimmed : trimmed;
  }

  /** The SCHOOL entry cookie (legacy accessor). */
  public String cookieName() {
    return schoolCookieName;
  }

  public String cookieName(AuthPortal portal) {
    return portal == AuthPortal.PLATFORM ? platformCookieName : schoolCookieName;
  }

  /** Issues a session for the entry the stored role belongs to (fixtures); login passes the entry explicitly. */
  public CreatedSession create(SessionPrincipal principal) {
    return create(principal, AuthPortal.forRole(principal.role()));
  }

  public CreatedSession create(SessionPrincipal principal, AuthPortal portal) {
    if (portal == null || !portal.admits(principal.role())) {
      throw new IllegalArgumentException("Role " + principal.role() + " cannot hold a " + portal + " session");
    }
    String sessionId = UUID.randomUUID().toString();
    long issued = clock.millis();
    SessionPrincipal stored = new SessionPrincipal(
        sessionId,
        principal.userId(),
        principal.orgId(),
        principal.username(),
        principal.displayName(),
        principal.role(),
        principal.permissions(),
        principal.authVersion(),
        issued,
        Math.addExact(issued, absoluteTtl.toMillis()),
        portal.name()
    );
    try {
      redis.opsForValue().set(key(sessionId), objectMapper.writeValueAsString(stored), remainingTtl(stored));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to serialize session", exception);
    }
    // 维护 用户→会话 索引，供改密码等敏感操作撤销该用户其它会话使用。
    String userKey = userKey(principal.userId());
    redis.opsForSet().add(userKey, sessionId);
    redis.expire(userKey, sessionTtl);
    return new CreatedSession(stored, sessionCookie(sessionId, portal));
  }

  public Optional<SessionPrincipal> find(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      return Optional.empty();
    }
    String json = redis.opsForValue().get(key(sessionId));
    if (json == null) {
      return Optional.empty();
    }
    try {
      SessionPrincipal principal = objectMapper.readValue(json, SessionPrincipal.class);
      if (principal == null || principal.userId() == null || !sessionId.equals(principal.sessionId())) {
        redis.delete(key(sessionId));
        return Optional.empty();
      }
      Duration remaining = remainingTtl(principal);
      if (principal.issuedAtEpochMillis() <= 0 || remaining.isZero() || remaining.isNegative()) {
        redis.delete(key(sessionId));
        return Optional.empty();
      }
      Long refreshed = redis.execute(REFRESH_SESSION, List.of(key(sessionId), userKey(principal.userId())),
          json, sessionId, Long.toString(remaining.toMillis()), Long.toString(sessionTtl.toMillis()));
      return Long.valueOf(1).equals(refreshed) ? Optional.of(principal) : Optional.empty();
    } catch (JsonProcessingException exception) {
      redis.delete(key(sessionId));
      return Optional.empty();
    }
  }

  public void revoke(String sessionId) {
    if (sessionId != null && !sessionId.isBlank()) {
      redis.delete(key(sessionId));
    }
  }

  /**
   * Revoke every other session of this user, keeping only {@code keepSessionId} (the caller's current
   * device), provided it is still valid and indexed. Unindexed tokens fail closed in {@link #find}.
   * Used on password change so other devices stop working while the current device stays signed in.
   */
  public void revokeOtherSessions(UUID userId, String keepSessionId) {
    if (userId == null) {
      return;
    }
    String userKey = userKey(userId);
    String keep = keepSessionId == null || keepSessionId.isBlank() ? "" : keepSessionId;
    Set<String> sessionIds = redis.opsForSet().members(userKey);
    List<String> keys = new ArrayList<>();
    keys.add(userKey);
    // No arbitrary placeholder key: this position is not read when no device is retained.
    keys.add(keep.isEmpty() ? userKey : key(keep));
    if (sessionIds != null) {
      for (String sessionId : sessionIds) {
        if (!keep.equals(sessionId)) {
          keys.add(key(sessionId));
        }
      }
    }
    redis.execute(REVOKE_OTHER_SESSIONS, keys, keep, Long.toString(sessionTtl.toMillis()));
  }

  public ResponseCookie sessionCookie(String sessionId) {
    return sessionCookie(sessionId, AuthPortal.SCHOOL);
  }

  public ResponseCookie sessionCookie(String sessionId, AuthPortal portal) {
    return ResponseCookie.from(cookieName(portal), sessionId)
        .httpOnly(true)
        .secure(cookieSecure)
        .sameSite(sameSite)
        .path("/")
        .maxAge(sessionTtl.compareTo(absoluteTtl) < 0 ? sessionTtl : absoluteTtl)
        .build();
  }

  public ResponseCookie clearCookie() {
    return clearCookie(AuthPortal.SCHOOL);
  }

  /** Clears only this entry's cookie: signing out of one portal never signs the other one out. */
  public ResponseCookie clearCookie(AuthPortal portal) {
    return ResponseCookie.from(cookieName(portal), "")
        .httpOnly(true)
        .secure(cookieSecure)
        .sameSite(sameSite)
        .path("/")
        .maxAge(Duration.ZERO)
        .build();
  }

  private String key(String sessionId) {
    return KEY_PREFIX + sessionId;
  }

  private Duration remainingTtl(SessionPrincipal principal) {
    return Duration.ofMillis(Math.min(sessionTtl.toMillis(), principal.expiresAtEpochMillis() - clock.millis()));
  }

  /** Preserve the current device after a committed password change without reviving a revoked token. */
  public void updateRetainedVersion(String sessionId, long version) {
    String json = redis.opsForValue().get(key(sessionId));
    if (json == null) return;
    try {
      SessionPrincipal current = objectMapper.readValue(json, SessionPrincipal.class);
      Duration ttl = remainingTtl(current);
      if (ttl.isNegative() || ttl.isZero()) return;
      var script = new DefaultRedisScript<Long>("""
          if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
          if redis.call('SISMEMBER', KEYS[2], ARGV[2]) ~= 1 then return 0 end
          redis.call('SET', KEYS[1], ARGV[3], 'PX', ARGV[4])
          return 1
          """, Long.class);
      redis.execute(script, List.of(key(sessionId), userKey(current.userId())), json, sessionId,
          objectMapper.writeValueAsString(current.withAuthVersion(version)), Long.toString(ttl.toMillis()));
    } catch (JsonProcessingException malformed) {
      redis.delete(key(sessionId));
    }
  }

  private String userKey(UUID userId) {
    return USER_KEY_PREFIX + userId;
  }

  public record CreatedSession(SessionPrincipal principal, ResponseCookie cookie) {
  }
}
