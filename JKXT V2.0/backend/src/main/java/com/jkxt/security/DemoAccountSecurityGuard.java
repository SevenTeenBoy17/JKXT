package com.jkxt.security;

import com.jkxt.security.domain.UserAccount;
import com.jkxt.security.repo.UserAccountRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Locks down the seeded demo accounts whose plaintext credentials are public (they appear in
 * the Flyway seed comments): {@code admin}/Super001!!! and {@code ops.demo}/{@code platform.super}
 * (all SUPER_ADMIN), plus the tenant {@code jwc.demo}/{@code tenant.test} ADMINs.
 *
 * <p>Controlled by {@code jkxt.security.disable-demo-accounts}: OFF by default so dev, the test
 * suite, and the on-prem demo keep logging in with them; ON in the {@code saas} profile so a
 * production cloud deploy never ships a usable known-credential platform super-admin. Real
 * operator accounts must be provisioned out-of-band. The check re-runs on every boot (idempotent),
 * which is more robust than a one-time migration if the DB state ever drifts.
 */
@Component
public class DemoAccountSecurityGuard {

  private static final Logger log = LoggerFactory.getLogger(DemoAccountSecurityGuard.class);

  /** Seeded demo logins (Flyway V3/V4/V9/V10) with publicly known default passwords. */
  static final List<String> DEMO_USERNAMES =
      List.of("admin", "ops.demo", "jwc.demo", "tenant.test", "platform.super");

  private final UserAccountRepository userRepository;
  private final boolean disableDemoAccounts;

  public DemoAccountSecurityGuard(
      UserAccountRepository userRepository,
      @Value("${jkxt.security.disable-demo-accounts:false}") boolean disableDemoAccounts
  ) {
    this.userRepository = userRepository;
    this.disableDemoAccounts = disableDemoAccounts;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void enforceOnStartup() {
    disableSeededDemoAccounts();
  }

  /**
   * Disables the seeded demo accounts when the flag is enabled. Idempotent: an already-disabled
   * account is left untouched. Returns the number of accounts newly disabled (visible for testing).
   */
  @Transactional
  public int disableSeededDemoAccounts() {
    if (!disableDemoAccounts) {
      return 0;
    }
    int disabled = 0;
    for (String username : DEMO_USERNAMES) {
      UserAccount user = userRepository.findFirstByUsernameIgnoreCase(username).orElse(null);
      if (user != null && !"disabled".equalsIgnoreCase(user.getStatus())) {
        user.setStatus("disabled");
        userRepository.save(user);
        disabled++;
      }
    }
    if (disabled > 0) {
      log.warn("SECURITY: disabled {} seeded demo account(s) with publicly-known default credentials "
              + "(jkxt.security.disable-demo-accounts=true). Provision real operator accounts out-of-band.",
          disabled);
    }
    return disabled;
  }
}
