package com.jkxt.security.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class SessionService {

  private static final String KEY_PREFIX = "jkxt:session:";

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
    redis.expire(key(sessionId), sessionTtl);
    try {
      return Optional.of(objectMapper.readValue(json, SessionPrincipal.class));
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

  public record CreatedSession(SessionPrincipal principal, ResponseCookie cookie) {
  }
}
