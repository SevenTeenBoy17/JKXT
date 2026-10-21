package com.jkxt.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A3② 登录按 IP 节流验收。默认关闭，故本类用 {@link TestPropertySource} 显式开启并把阈值压到 3 次/60 秒。
 *
 * <p>同时把 {@code trusted-proxies=127.0.0.1}（MockMvc 的直连对端）设为可信，使 {@link com.jkxt.security.auth.ClientIpResolver}
 * 采信我们发的 {@code X-Forwarded-For} —— 这样每个用例用一个专属测试 IP 作为节流 key，既与其它测试(127.0.0.1)
 * 不冲突，也顺带验证「节流以 B2 解析出的真实 IP 为准，而非裸 remoteAddr」。用 203.0.113.0/24(TEST-NET-3 文档
 * 保留段)确保不撞真实地址。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "jkxt.auth.ip-throttle.enabled=true",
    "jkxt.auth.ip-throttle.max-attempts=3",
    "jkxt.auth.ip-throttle.window-seconds=60",
    "jkxt.auth.trusted-proxies=127.0.0.1"
})
class AuthIpThrottleAcceptanceTest {

  private static final String KEY_PREFIX = "jkxt:login-ip:";
  private static final String THROTTLED_IP = "203.0.113.7";
  private static final String FRESH_IP = "203.0.113.8";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private StringRedisTemplate redis;

  @BeforeEach
  @AfterEach
  void clearCounters() {
    redis.delete(KEY_PREFIX + THROTTLED_IP);
    redis.delete(KEY_PREFIX + FRESH_IP);
  }

  @Test
  void repeatedFailuresFromOneIpGet429WhileOtherIpsStayFree() throws Exception {
    // 前 3 次失败(坏验证码)→ 400 AUTH_CAPTCHA_FAILED，计数逐次累加(每次失败 recordFailure)。
    for (int i = 1; i <= 3; i++) {
      attemptFrom(THROTTLED_IP)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("AUTH_CAPTCHA_FAILED"));
    }

    // 第 4 次(同 IP)在进入验证码/查库之前就被 checkAllowed 挡下 → 429 AUTH_IP_THROTTLED。
    attemptFrom(THROTTLED_IP)
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("AUTH_IP_THROTTLED"));

    // 换一个 IP 不受影响(按 IP 隔离，不是全局)→ 仍能走到验证码校验(400，而非 429)。
    attemptFrom(FRESH_IP)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("AUTH_CAPTCHA_FAILED"));
  }

  private ResultActions attemptFrom(String ip) throws Exception {
    return mockMvc.perform(post("/api/v1/auth/login")
        .header("X-Forwarded-For", ip)
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
            {"username":"nobody","password":"whatever","captchaId":"bad-id","captcha":"bad-answer"}
            """));
  }
}
