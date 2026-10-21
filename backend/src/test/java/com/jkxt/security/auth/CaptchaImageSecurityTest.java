package com.jkxt.security.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** Exercises the real renderer; only Redis storage is replaced at this unit-test boundary. */
class CaptchaImageSecurityTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void challengeUsesDecodableRasterWithoutTextMetadata(boolean debug) throws Exception {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    CaptchaResponse response = new CaptchaService(redis, 180, debug).create();

    ArgumentCaptor<String> storedAnswer = ArgumentCaptor.forClass(String.class);
    verify(values).set(eq("jkxt:captcha:" + response.captchaId()), storedAnswer.capture(), eq(Duration.ofSeconds(180)));
    assertThat(storedAnswer.getValue()).matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{5}");
    assertThat(response.expiresInSeconds()).isEqualTo(180);
    if (debug) assertThat(response.debugAnswer()).isEqualTo(storedAnswer.getValue());
    else assertThat(response.debugAnswer()).isNull();

    // A textual SVG exposes the full answer without solving the image, even with debug disabled.
    assertThat(response.imageBase64()).startsWith("data:image/png;base64,");
    byte[] bytes = Base64.getDecoder().decode(response.imageBase64().substring("data:image/png;base64,".length()));
    assertThat(bytes).startsWith(new byte[]{(byte)137, 80, 78, 71, 13, 10, 26, 10});
    assertThat(bytes.length).isBetween(300, 100_000);
    ByteBuffer chunks = ByteBuffer.wrap(bytes);
    chunks.position(8);
    List<String> types = new ArrayList<>();
    while (chunks.hasRemaining()) {
      int length = chunks.getInt();
      byte[] type = new byte[4];
      chunks.get(type);
      types.add(new String(type, StandardCharsets.US_ASCII));
      assertThat(length).isBetween(0, chunks.remaining() - 4);
      chunks.position(chunks.position() + length + 4);
    }
    assertThat(types).contains("IHDR", "IDAT", "IEND").doesNotContain("tEXt", "zTXt", "iTXt");
    BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
    assertThat(image).isNotNull();
    assertThat(image.getWidth()).isEqualTo(264);
    assertThat(image.getHeight()).isEqualTo(88);
    long inkPixels = 0;
    for (int x = 0; x < image.getWidth(); x++) {
      for (int y = 0; y < image.getHeight(); y++) {
        int rgb = image.getRGB(x, y);
        if (((rgb >> 16) & 255) < 130 && ((rgb >> 8) & 255) < 150) inkPixels++;
      }
    }
    assertThat(inkPixels).as("A nonblank challenge must contain visible dark glyphs").isGreaterThan(500);
  }
}
