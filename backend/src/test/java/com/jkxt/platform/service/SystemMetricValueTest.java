package com.jkxt.platform.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SystemMetricValueTest {
  private Double value(double sample) {
    var registry = new SimpleMeterRegistry();
    try {
      Gauge.builder("system.cpu.usage", () -> sample).register(registry);
      return ReflectionTestUtils.invokeMethod(mock(AdminPlatformService.class, CALLS_REAL_METHODS),
          "gaugeValue", registry, "system.cpu.usage");
    } finally { registry.close(); }
  }

  @Test void unavailableOperatingSystemSentinelMustNotBeReportedAsNegativeLoad() {
    assertNull(value(-1.0));
  }

  @Test void nonFiniteSampleIsUnavailable() {
    assertNull(value(Double.NaN));
    assertNull(value(Double.POSITIVE_INFINITY));
  }

  @Test void validZeroAndPositiveSamplesRemainUnchanged() {
    assertEquals(0.0, value(0.0));
    assertEquals(0.37, value(0.37));
  }
}
