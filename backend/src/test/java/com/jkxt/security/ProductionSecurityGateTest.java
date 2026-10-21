package com.jkxt.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

/**
 * 迭代9 (WF1 DevOps P0 / 苏格拉底 T6)：生产安全前置门的纯单元测试(不启 Spring 上下文)。
 */
class ProductionSecurityGateTest {

  @Test
  void lenientModeNeverBlocksEvenWithInsecureDefaults() {
    // 非严格模式(当前 dev/test/演示栈默认)：即便三项全不安全,也不拦——对现有栈零影响。
    ProductionSecurityGate gate = new ProductionSecurityGate(
        false, /*disableDemo*/ false, /*captchaDebug*/ true, /*cookieSecure*/ false);
    assertThat(gate.violations()).isEmpty();
    assertThatCode(gate::verify).doesNotThrowAnyException();
  }

  @Test
  void strictModeWithAllInsecureDefaultsListsEveryViolationAndRefusesStartup() {
    ProductionSecurityGate gate = new ProductionSecurityGate(
        true, /*disableDemo*/ false, /*captchaDebug*/ true, /*cookieSecure*/ false);
    assertThat(gate.violations()).hasSize(3);
    assertThatThrownBy(gate::verify)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("demo accounts")
        .hasMessageContaining("captcha debug")
        .hasMessageContaining("Secure");
  }

  @Test
  void strictModeWithAllSecureDefaultsStartsCleanly() {
    ProductionSecurityGate gate = new ProductionSecurityGate(
        true, /*disableDemo*/ true, /*captchaDebug*/ false, /*cookieSecure*/ true);
    assertThat(gate.violations()).isEmpty();
    assertThatCode(gate::verify).doesNotThrowAnyException();
  }

  @Test
  void strictModeFlagsOnlyTheDemoAccountViolationWhenThatIsTheSoleGap() {
    ProductionSecurityGate gate = new ProductionSecurityGate(
        true, /*disableDemo*/ false, /*captchaDebug*/ false, /*cookieSecure*/ true);
    assertThat(gate.violations()).singleElement().asString().contains("demo accounts");
    assertThatThrownBy(gate::verify).isInstanceOf(IllegalStateException.class);
  }
}
