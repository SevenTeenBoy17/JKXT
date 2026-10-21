package com.jkxt.security;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProductionModeSecurityGateTest {
  private ApplicationContextRunner strict(String mode, String org) {
    return new ApplicationContextRunner().withBean(ProductionSecurityGate.class).withPropertyValues(
        "jkxt.security.strict-production-checks=true", "jkxt.security.disable-demo-accounts=true",
        "jkxt.auth.captcha-debug=false", "jkxt.auth.cookie-secure=true",
        "jkxt.deploy.mode=" + mode, "jkxt.deploy.fixed-org-id=" + org);
  }

  @Test void strictProductionRefusesDevelopmentTestEmptyAndUnknownModes() {
    for (String mode : new String[]{"dev", "test", "", "unknown"}) {
      strict(mode, "00000000-0000-0000-0000-000000000001").run(context -> assertThat(context).hasFailed());
    }
  }

  @Test void strictPrivateRequiresCanonicalNonNilOrganizationUuid() {
    for (String org : new String[]{"", "garbage", "1-1-1-1-1", "00000000-0000-0000-0000-000000000000"}) {
      strict("private", org).run(context -> assertThat(context).hasFailed());
    }
  }

  @Test void strictPrivateAndSaasRemainSupported() {
    strict("private", "00000000-0000-0000-0000-000000000001").run(context -> assertThat(context).hasNotFailed());
    strict("saas", "").run(context -> assertThat(context).hasNotFailed());
  }

  @Test void unsupportedApiPrefixIsRejectedEvenOutsideStrictProductionMode() {
    // Frontend and the filter-chain permission rules both use /api/v1. A renamed controller
    // prefix must never land in anyRequest().permitAll(), including in a development profile.
    for (String prefix : new String[]{"/api/v2", "/", "", "/api/v1/", "/api/v1?unsafe"}) {
      new ApplicationContextRunner().withBean(ProductionSecurityGate.class).withPropertyValues(
          "jkxt.security.strict-production-checks=false", "jkxt.api-base=" + prefix)
          .run(context -> assertThat(context).hasFailed());
    }
  }

  @Test void documentedApiPrefixRemainsSupportedInDevelopment() {
    new ApplicationContextRunner().withBean(ProductionSecurityGate.class).withPropertyValues(
        "jkxt.security.strict-production-checks=false", "jkxt.api-base=/api/v1")
        .run(context -> assertThat(context).hasNotFailed());
  }
}
