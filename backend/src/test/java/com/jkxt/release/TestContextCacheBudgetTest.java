package com.jkxt.release;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.cache.DefaultContextCache;

/** Only the test harness cache is bounded; production pools and business assertions are unchanged. */
class TestContextCacheBudgetTest {
  @Test void retainedTestContextsHaveABoundedConnectionBudget() {
    assertThat(new DefaultContextCache().getMaxSize()).isEqualTo(8);
  }
}
