package com.jkxt.security.auth;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * A3② 登录按 IP 滑窗节流，叠加在「按账号锁定」之上，抵御撞库/横向喷洒（一个 IP 对大量不同用户名各试几次，
 * 每账号都够不到 5 次锁定阈值）。以 {@link ClientIpResolver} 解析出的【真实】客户端 IP 为 key —— 该解析器
 * (B2) 已把 X-Forwarded-For 收敛为仅信任可信代理，故本节流无法被轮换伪造 XFF 绕过。
 *
 * <p>固定窗口计数：某 IP 首次失败起 {@code window-seconds} 秒内累计失败次数达 {@code max-attempts} 即拒绝，
 * 窗口到期自动清零（Redis TTL）。只计【失败】，登录成功不消耗额度，故正常用户永不触发。
 *
 * <p><b>默认关闭</b>（{@code enabled=false}）：开启后校园网大量学生共用一个 NAT 出口 IP 时可能误封整校，
 * 故是否开启与阈值属部署决策。关闭时本组件对登录零行为影响。saas（公网多租户）默认开启。
 *
 * <p><b>Redis 故障时 fail-open</b>：节流是辅助防线，绝不能因 Redis 抖动把所有人挡在登录外（何况验证码/会话
 * 本就依赖 Redis）。读写异常一律放行并记 warn。
 */
@Component
public class LoginIpThrottle {

  private static final Logger log = LoggerFactory.getLogger(LoginIpThrottle.class);
  private static final String KEY_PREFIX = "jkxt:login-ip:";

  private final StringRedisTemplate redis;
  private final boolean enabled;
  private final int maxAttempts;
  private final Duration window;

  public LoginIpThrottle(
      StringRedisTemplate redis,
      @Value("${jkxt.auth.ip-throttle.enabled:false}") boolean enabled,
      @Value("${jkxt.auth.ip-throttle.max-attempts:30}") int maxAttempts,
      @Value("${jkxt.auth.ip-throttle.window-seconds:300}") int windowSeconds
  ) {
    this.redis = redis;
    this.enabled = enabled;
    this.maxAttempts = maxAttempts;
    this.window = Duration.ofSeconds(windowSeconds);
  }

  /**
   * 登录入口最先调用。若该 IP 当前窗口内失败已达阈值，抛 429 直接拒绝——不再进入验证码/查库/校验口令，
   * 既挡住撞库也避免为被节流的 IP 白做功。
   */
  public void checkAllowed(String ip) {
    if (!enabled || ip == null || ip.isBlank()) {
      return;
    }
    try {
      String current = redis.opsForValue().get(key(ip));
      if (current != null && parse(current) >= maxAttempts) {
        throw new AuthException(
            HttpStatus.TOO_MANY_REQUESTS,
            "AUTH_IP_THROTTLED",
            "该 IP 登录尝试过于频繁，请稍后再试"
        );
      }
    } catch (AuthException throttled) {
      throw throttled;
    } catch (RuntimeException redisDown) {
      // fail-open：Redis 不可用不应把用户挡在登录外。
      log.warn("login ip-throttle check skipped (redis error): {}", redisDown.toString());
    }
  }

  /**
   * 一次登录【失败】后调用（口令错、用户名不存在、账号禁用/锁定、验证码错等任何 AuthException）。
   * 递增该 IP 的失败计数；首次失败时设定窗口 TTL，形成固定窗口。登录成功不调用本方法。
   */
  public void recordFailure(String ip) {
    if (!enabled || ip == null || ip.isBlank()) {
      return;
    }
    try {
      Long count = redis.opsForValue().increment(key(ip));
      if (count != null && count == 1L) {
        // 仅在窗口首次失败时设 TTL：窗口从首次失败起算，到期整体清零。
        redis.expire(key(ip), window);
      }
    } catch (RuntimeException redisDown) {
      log.warn("login ip-throttle record skipped (redis error): {}", redisDown.toString());
    }
  }

  private int parse(String value) {
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException malformed) {
      return 0;
    }
  }

  private String key(String ip) {
    return KEY_PREFIX + ip;
  }
}
