package com.jkxt.security.auth;

import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
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
    String challenge = image(answer);
    redis.opsForValue().set(key(id), answer, Duration.ofSeconds(ttlSeconds));
    return new CaptchaResponse(id, challenge, ttlSeconds, debug ? answer : null);
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
    // SVG <text> reveals the answer by Base64 decoding. Encode pixels only; do not attach
    // text metadata or provide an SVG fallback. Captcha is defense in depth, not a rate limit.
    BufferedImage pixels = new BufferedImage(264, 88, BufferedImage.TYPE_INT_RGB);
    Graphics2D canvas = pixels.createGraphics();
    try {
      canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      canvas.setColor(new Color(238, 246, 255));
      canvas.fillRect(0, 0, pixels.getWidth(), pixels.getHeight());
      canvas.setStroke(new BasicStroke(2));
      canvas.setColor(new Color(147, 197, 253));
      for (int i = 0; i < 5; i++) {
        canvas.drawLine(random.nextInt(264), random.nextInt(88), random.nextInt(264), random.nextInt(88));
      }
      canvas.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 52));
      for (int i = 0; i < answer.length(); i++) {
        Graphics2D glyph = (Graphics2D) canvas.create();
        try {
          glyph.setColor(new Color(29, 78, 165));
          glyph.translate(18 + i * 46, 61 + random.nextInt(7) - 3);
          glyph.rotate((random.nextDouble() - 0.5) * 0.2);
          glyph.drawString(String.valueOf(answer.charAt(i)), 0, 0);
        } finally {
          glyph.dispose();
        }
      }
    } finally {
      canvas.dispose();
    }
    try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      if (!ImageIO.write(pixels, "png", output)) throw new IOException("PNG writer is unavailable");
      return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
    } catch (IOException failure) {
      throw new IllegalStateException("Unable to render captcha", failure);
    }
  }

  private String key(String captchaId) {
    return KEY_PREFIX + captchaId;
  }
}
