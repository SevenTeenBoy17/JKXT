package com.jkxt.p11;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@EnabledIfSystemProperty(named = "jkxt.testcontainers.enabled", matches = "true")
class P11TestcontainersInfrastructureTest {

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
      .withDatabaseName("jkxt_tc")
      .withUsername("jkxt")
      .withPassword("jkxt");

  @Container
  static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
      .withExposedPorts(6379);

  @Test
  void flywayMigrationsAndRedisRunOnRealContainers() throws Exception {
    Flyway flyway = Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/migration")
        .load();
    flyway.migrate();

    try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
         var statement = connection.createStatement();
         var rs = statement.executeQuery("select count(*) from organization")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(1);
    }

    try (Socket socket = new Socket(redis.getHost(), redis.getMappedPort(6379))) {
      OutputStream out = socket.getOutputStream();
      InputStream in = socket.getInputStream();
      out.write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII));
      out.flush();
      byte[] buffer = in.readNBytes(7);
      assertThat(new String(buffer, StandardCharsets.US_ASCII)).contains("PONG");
    }
  }
}
