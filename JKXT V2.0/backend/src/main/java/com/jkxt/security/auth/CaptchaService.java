package com.jkxt.security.auth;

import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class CaptchaService {

  private static final String KEY_PREFIX = "jkxt:captcha:";
  private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

  private final StringRedisTemplate redis;
  private final SecureRandom random = new SecureRandom();
  private final int ttlSeconds;
  private final boolean debug;

  public CaptchaService(
      StringRedisTemplate redis,
      @Value("${jkxt.auth.captcha-ttl-seconds:180}") int ttlSeconds,
      @Value("${jkxt.auth.captcha-debug:false}") boolean debug
  ) {
    this.redis = redis;
    this.ttlSeconds = ttlSeconds;
    this.debug = debug;
  }

  public CaptchaResponse create() {
    String id = UUID.randomUUID().toString();
    String answer = randomAnswer();
    redis.opsForValue().set(key(id), answer, Duration.ofSeconds(ttlSeconds));
    return new CaptchaResponse(id, image(answer), ttlSeconds, debug ? answer : null);
  }

  public boolean consume(String captchaId, String input) {
    if (captchaId == null || input == null) {
      return false;
    }
    String key = key(captchaId);
    // 迭代9 T6-P0-2：原 get+delete 两步非原子 → 同一 captchaId 并发请求可同时通过(重放窗口)。
    // 改用 GETDEL 原子取值并删除，保证一个验证码只能被消费一次。
    String expected = redis.opsForValue().getAndDelete(key);
    return expected != null
        && expected.equals(input.trim().toUpperCase(Locale.ROOT));
  }

  private String randomAnswer() {
    StringBuilder builder = new StringBuilder(5);
    for (int i = 0; i < 5; i++) {
      builder.append(ALPHABET[random.nextInt(ALPHABET.length)]);
    }
    return builder.toString();
  }

  private String image(String answer) {
    String svg = """
        <svg xmlns="http://www.w3.org/2000/svg" width="132" height="44" viewBox="0 0 132 44">
          <rect width="132" height="44" rx="12" fill="#EEF6FF"/>
          <path d="M10 34 C30 8 58 50 86 12 S118 30 126 10" fill="none" stroke="#93C5FD" stroke-width="2" opacity=".55"/>
          <text x="66" y="29" text-anchor="middle" font-family="monospace" font-size="24" font-weight="700" letter-spacing="4" fill="#1D4ED8">%s</text>
        </svg>
        """.formatted(answer);
    String encoded = Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
    return "data:image/svg+xml;base64," + encoded;
  }

  private String key(String captchaId) {
    return KEY_PREFIX + captchaId;
  }
}
