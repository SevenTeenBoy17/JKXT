package com.jkxt.security;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 迭代9 (WF1 DevOps P0 + 苏格拉底 T6)：生产安全前置门。DevOps 评审发现"一键 up 即得不安全生产实例"——
 * private 现场默认携带口令公开的演示超管、captcha 调试旁路开、Cookie 非 Secure。苏格拉底 T6 的结论是
 * <b>不要裸 flip 这些默认</b>（会同时打断冒烟脚本与本地内测），而是加一道<b>启动期前置门</b>：仅当显式声明处于
 * 严格生产模式（{@code jkxt.security.strict-production-checks=true}）且检出不安全默认时，<b>拒绝启动并明确
 * 报错</b>，把"静默上线不安全实例"变成"响亮的启动失败"。
 *
 * <p>默认关闭，故对当前 dev/test/演示栈零影响；saas 云与真实 private 生产部署应显式打开并配齐安全默认
 * （disable-demo-accounts=true、captcha-debug=false、cookie-secure=true）。冒烟/UAT 栈保持关闭。
 */
@Component
public class ProductionSecurityGate {

  private static final Logger log = LoggerFactory.getLogger(ProductionSecurityGate.class);

  private final boolean strict;
  private final boolean disableDemoAccounts;
  private final boolean captchaDebug;
  private final boolean cookieSecure;
  private final String deployMode;
  private final String fixedOrgId;
  private final String apiBase;

  /** Explicit construction retained for callers testing the original three security switches. */
  public ProductionSecurityGate(boolean strict, boolean disableDemoAccounts, boolean captchaDebug, boolean cookieSecure) {
    this(strict, disableDemoAccounts, captchaDebug, cookieSecure, "private", "00000000-0000-0000-0000-000000000001");
  }

  public ProductionSecurityGate(
      boolean strict, boolean disableDemoAccounts, boolean captchaDebug, boolean cookieSecure,
      String deployMode, String fixedOrgId
  ) {
    this(strict, disableDemoAccounts, captchaDebug, cookieSecure, deployMode, fixedOrgId, "/api/v1");
  }

  @Autowired
  public ProductionSecurityGate(
      @Value("${jkxt.security.strict-production-checks:false}") boolean strict,
      @Value("${jkxt.security.disable-demo-accounts:false}") boolean disableDemoAccounts,
      @Value("${jkxt.auth.captcha-debug:false}") boolean captchaDebug,
      @Value("${jkxt.auth.cookie-secure:false}") boolean cookieSecure,
      @Value("${jkxt.deploy.mode:dev}") String deployMode,
      @Value("${jkxt.deploy.fixed-org-id:00000000-0000-0000-0000-000000000001}") String fixedOrgId,
      @Value("${jkxt.api-base:/api/v1}") String apiBase
  ) {
    this.strict = strict;
    this.disableDemoAccounts = disableDemoAccounts;
    this.captchaDebug = captchaDebug;
    this.cookieSecure = cookieSecure;
    this.deployMode = deployMode == null ? "" : deployMode.trim().toLowerCase(Locale.ROOT);
    this.fixedOrgId = fixedOrgId == null ? "" : fixedOrgId.trim();
    this.apiBase = apiBase;
  }

  @PostConstruct
  public void verify() {
    // The frontend, session filter and authorization rules share this documented prefix.
    // A configurable controller prefix alone would escape those rules via permitAll().
    if (!"/api/v1".equals(apiBase)) {
      throw new IllegalStateException("SECURITY: jkxt.api-base must be /api/v1; custom controller prefixes are not supported by the authorization contract");
    }
    List<String> violations = violations();
    if (violations.isEmpty()) {
      if (strict) {
        log.info("SECURITY: strict production checks passed (demo accounts disabled, captcha-debug off, cookie-secure on).");
      }
      return;
    }
    // 抛出即中断 Spring 上下文初始化 → 应用拒绝启动，而非带着不安全默认静默上线。
    throw new IllegalStateException(
        "SECURITY: refusing to start in strict production mode with insecure defaults — "
            + String.join("; ", violations)
            + ". Fix these (or unset jkxt.security.strict-production-checks for a dev/UAT/smoke stack).");
  }

  /**
   * 严格模式下的不安全项清单（非严格模式恒为空）。抽成方法便于单测直接断言,无需真启 Spring 上下文。
   */
  List<String> violations() {
    List<String> violations = new ArrayList<>();
    if (!strict) {
      return violations;
    }
    if (!disableDemoAccounts) {
      violations.add("seeded demo accounts with publicly-known passwords are still enabled "
          + "(set jkxt.security.disable-demo-accounts=true)");
    }
    if (captchaDebug) {
      violations.add("captcha debug bypass is enabled (set jkxt.auth.captcha-debug=false)");
    }
    if (!cookieSecure) {
      violations.add("session cookie is not marked Secure (set jkxt.auth.cookie-secure=true, front it with TLS)");
    }
    if (!List.of("private", "saas").contains(deployMode)) {
      violations.add("production deployment mode must be private or saas (set jkxt.deploy.mode)");
    } else if ("private".equals(deployMode) && (!fixedOrgId.matches(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        || "00000000-0000-0000-0000-000000000000".equals(fixedOrgId))) {
      violations.add("private production deployment requires a canonical, non-nil jkxt.deploy.fixed-org-id UUID");
    }
    return violations;
  }
}
