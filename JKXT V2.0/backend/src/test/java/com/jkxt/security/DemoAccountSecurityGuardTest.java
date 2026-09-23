package com.jkxt.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jkxt.security.repo.UserAccountRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Epic 2 Part 3 — seeded demo accounts (publicly-known default credentials) must be disabled when
 * {@code jkxt.security.disable-demo-accounts} is on (the saas profile), and left active otherwise.
 */
@SpringBootTest
@ActiveProfiles("test")
class DemoAccountSecurityGuardTest {

  @Autowired
  private UserAccountRepository userRepository;

  @Test
  void disablesSeededDemoAccountsOnlyWhenFlagEnabled() {
    // The test profile keeps the flag OFF, so the startup guard is a no-op and the demo accounts
    // stay active (other test classes log in with them). Snapshot to restore after this test.
    Map<String, String> original = new HashMap<>();
    for (String username : DemoAccountSecurityGuard.DEMO_USERNAMES) {
      userRepository.findFirstByUsernameIgnoreCase(username)
          .ifPresent(account -> original.put(account.getId().toString(), account.getStatus()));
    }
    assertThat(original).isNotEmpty();

    try {
      // Flag OFF -> guard does nothing.
      DemoAccountSecurityGuard off = new DemoAccountSecurityGuard(userRepository, false);
      assertThat(off.disableSeededDemoAccounts()).isZero();

      // Flag ON -> every seeded demo account becomes disabled.
      DemoAccountSecurityGuard on = new DemoAccountSecurityGuard(userRepository, true);
      assertThat(on.disableSeededDemoAccounts()).isGreaterThanOrEqualTo(1);
      for (String username : DemoAccountSecurityGuard.DEMO_USERNAMES) {
        userRepository.findFirstByUsernameIgnoreCase(username).ifPresent(account ->
            assertThat(account.getStatus()).isEqualToIgnoringCase("disabled"));
      }

      // Idempotent: a second run disables nothing new.
      assertThat(on.disableSeededDemoAccounts()).isZero();
    } finally {
      // CRITICAL: restore original statuses so other test classes can still log in.
      original.forEach((id, status) ->
          userRepository.findById(UUID.fromString(id)).ifPresent(account -> {
            account.setStatus(status);
            userRepository.save(account);
          }));
    }
  }
}
