package com.jkxt.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

/** Test servers must never be reachable from other machines by default. */
class TestNetworkBindingTest {
  @Test
  void testProfileBindsSharedApplicationAndManagementPortToLoopback() throws IOException {
    var sources = new YamlPropertySourceLoader()
        .load("test-network", new ClassPathResource("application-test.yml"));
    assertThat(sources).hasSize(1);
    assertThat(sources.getFirst().getProperty("server.address")).isEqualTo("127.0.0.1");
    // Actuator shares the same listener. Boot rejects a management-specific address
    // unless a distinct management port is configured; do not expose a second listener.
    assertThat(sources.getFirst().getProperty("management.server.address")).isNull();
    assertThat(sources.getFirst().getProperty("management.server.port")).isNull();
  }
}
