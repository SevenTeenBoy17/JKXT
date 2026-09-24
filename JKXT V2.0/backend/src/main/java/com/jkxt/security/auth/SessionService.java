package com.jkxt.security.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
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
      redis.call('PEXPIRE', KEYS[2], ARGV[3])
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
  private final String cookieName;
  private final boolean cookieSecure;
  private final String sameSite;
  private final Duration sessionTtl;

  public SessionService(
      StringRedisTemplate redis,
      ObjectMapper objectMapper,
      @Value("${jkxt.auth.cookie-name:JKXT_SESSION}") String cookieName,
      @Value("${jkxt.auth.cookie-secure:false}") boolean cookieSecure,
      @Value("${jkxt.auth.cookie-same-site:Lax}") String sameSite,
      @Value("${jkxt.auth.session-ttl-seconds:604800}") long sessionTtlSeconds
  ) {
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.cookieName = cookieName;
    this.cookieSecure = cookieSecure;
    this.sameSite = sameSite;
    this.sessionTtl = Duration.ofSeconds(sessionTtlSeconds);
  }

  public String cookieName() {
    return cookieName;
  }

  public CreatedSession create(SessionPrincipal principal) {
    String sessionId = UUID.randomUUID().toString();
    SessionPrincipal stored = new SessionPrincipal(
        sessionId,
        principal.userId(),
        principal.orgId(),
        principal.username(),
        principal.displayName(),
        principal.role(),
        principal.permissions()
    );
    try {
      redis.opsForValue().set(key(sessionId), objectMapper.writeValueAsString(stored), sessionTtl);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to serialize session", exception);
    }
    // 维护 用户→会话 索引，供改密码等敏感操作撤销该用户其它会话使用。
    String userKey = userKey(principal.userId());
    redis.opsForSet().add(userKey, sessionId);
    redis.expire(userKey, sessionTtl);
    return new CreatedSession(stored, sessionCookie(sessionId));
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
      Long refreshed = redis.execute(REFRESH_SESSION, List.of(key(sessionId), userKey(principal.userId())),
          json, sessionId, Long.toString(sessionTtl.toMillis()));
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
    return ResponseCookie.from(cookieName, sessionId)
        .httpOnly(true)
        .secure(cookieSecure)
        .sameSite(sameSite)
        .path("/")
        .maxAge(sessionTtl)
        .build();
  }

  public ResponseCookie clearCookie() {
    return ResponseCookie.from(cookieName, "")
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

  private String userKey(UUID userId) {
    return USER_KEY_PREFIX + userId;
  }

  public record CreatedSession(SessionPrincipal principal, ResponseCookie cookie) {
  }
}
