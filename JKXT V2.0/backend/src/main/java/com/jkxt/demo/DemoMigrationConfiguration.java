package com.jkxt.demo;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class DemoMigrationConfiguration {
  @Bean
  FlywayMigrationStrategy demoGuardedMigration(DemoDeploymentGuard guard, DataSource applicationSource) {
    return flyway -> {
      guard.verify(flyway.getConfiguration().getDataSource(), applicationSource);
      flyway.migrate();
    };
  }
}
