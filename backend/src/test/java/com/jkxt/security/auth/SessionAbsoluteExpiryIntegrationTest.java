package com.jkxt.security.auth;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=2")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SessionAbsoluteExpiryIntegrationTest {
  @Autowired StringRedisTemplate redis;
  @Autowired ObjectMapper mapper;

  @Test void continuousActivityCannotExtendAbsoluteDeadlineOrExpireNewerDevicesIndex() {
    UUID userId = UUID.randomUUID();
    AtomicLong now = new AtomicLong(System.currentTimeMillis());
    Clock clock = new Clock() {
      public ZoneId getZone() { return ZoneOffset.UTC; }
      public Clock withZone(ZoneId zone) { return this; }
      public Instant instant() { return Instant.ofEpochMilli(now.get()); }
      public long millis() { return now.get(); }
    };
    SessionService service = new SessionService(redis, mapper, "JKXT_SCHOOL_SESSION", false, "Lax", 600, 60, clock);
    SessionPrincipal identity = new SessionPrincipal("", userId, UUID.randomUUID(), "isolated", "Isolated", "ADMIN", List.of());
    var old = service.create(identity).principal();
    String newer = null;
    try {
      now.addAndGet(59_000);
      newer = service.create(identity).principal().sessionId();
      assertThat(service.find(old.sessionId())).isPresent();
      Long ttl = redis.getExpire("jkxt:session:" + old.sessionId(), TimeUnit.MILLISECONDS);
      assertThat(ttl).isBetween(1L, 1000L);
      // A near-expiry old token must not shorten the shared index of the newer device.
      assertThat(redis.getExpire("jkxt:user-sessions:" + userId, TimeUnit.SECONDS)).isGreaterThan(500L);
      now.incrementAndGet();
      assertThat(service.find(old.sessionId())).isPresent();
      now.addAndGet(999);
      assertThat(service.find(old.sessionId())).isEmpty();
      assertThat(service.find(newer)).isPresent();
    } finally {
      redis.delete("jkxt:session:" + old.sessionId());
      if (newer != null) redis.delete("jkxt:session:" + newer);
      redis.delete("jkxt:user-sessions:" + userId);
    }
  }
}
