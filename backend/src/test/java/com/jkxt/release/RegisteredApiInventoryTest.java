package com.jkxt.release;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.apache.coyote.AbstractProtocol;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import org.springframework.web.servlet.HandlerMapping;

/** Captures instantiated Spring handlers, not regex guesses; requires real isolated PG/Redis. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RegisteredApiInventoryTest {
  @Autowired ApplicationContext context;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;

  @Test
  void recordsActualMappingsAndIsolatedStorageAssertions() throws Exception {
    var webContext = (ServletWebServerApplicationContext) context;
    var webServer = (TomcatWebServer) webContext.getWebServer();
    var protocol = (AbstractProtocol<?>) webServer.getTomcat().getConnector().getProtocolHandler();
    assertThat(protocol.getAddress()).isNotNull();
    String serverAddress = protocol.getAddress().getHostAddress();
    assertThat(serverAddress).isEqualTo("127.0.0.1");
    String database = jdbc.queryForObject("select current_database()", String.class);
    assertThat(database).matches("jkxt_test_[a-z0-9_]+");
    int migrationVersion = jdbc.queryForObject(
        "select max(version::int) from flyway_schema_history where success", Integer.class);
    // V34 (2026-10-18, PB-2) restores CHIEF for the unmodified synthetic demo seed roster.
    // V35 (2026-10-19, PB-18) retires the fabricated BAK-P10-SEED backup record.
    // V36 (2026-10-19, PB-10) adds the published duty teacher snapshot.
    assertThat(migrationVersion).isEqualTo(36);
    int failedMigrations = jdbc.queryForObject(
        "select count(*) from flyway_schema_history where not success", Integer.class);
    assertThat(failedMigrations).isZero();
    String pong = redis.execute((org.springframework.data.redis.core.RedisCallback<String>)
        connection -> connection.ping());
    assertThat(pong).isEqualTo("PONG");

    List<Map<String, Object>> routes = new ArrayList<>();
    context.getBeansOfType(RequestMappingHandlerMapping.class).forEach((beanName, mapping) ->
        mapping.getHandlerMethods().forEach((info, method) -> {
          var authorization = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), PreAuthorize.class);
          if (authorization == null) {
            authorization = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), PreAuthorize.class);
          }
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("mappingBean", beanName);
          row.put("methods", info.getMethodsCondition().isEmpty() ? List.of("ANY")
              : info.getMethodsCondition().getMethods().stream().map(Enum::name).sorted().toList());
          row.put("handler", method.getBeanType().getName() + "#" + method.getMethod().getName());
          row.put("produces", info.getProducesCondition().toString());
          row.put("consumes", info.getConsumesCondition().toString());
          row.put("preAuthorize", authorization == null ? "" : authorization.value());
          row.put("securityFilterChain", "com.jkxt.security.SecurityConfig (also applies; annotation is not the whole policy)");
          for (String route : info.getPatternValues().stream().sorted().toList()) {
            var expanded = new LinkedHashMap<>(row);
            expanded.put("path", route);
            routes.add(expanded);
          }
        }));
    routes.sort(Comparator.comparing(row -> row.get("path").toString() + row.get("methods")));
    assertThat(routes).isNotEmpty();
    assertThat(routes.stream().map(row -> row.get("path").toString()))
        .contains("/api/v1/auth/login", "/api/v1/health", "/api/v1/exam-plans/{planId}");
    // Actuator uses a separate mapping class; ordinary MVC discovery must not silently omit it.
    List<Map<String, Object>> supplemental = new ArrayList<>();
    context.getBeansOfType(RequestMappingInfoHandlerMapping.class).forEach((beanName, mapping) -> {
      if (mapping instanceof RequestMappingHandlerMapping) return;
      mapping.getHandlerMethods().forEach((info, method) -> {
        for (String route : info.getPatternValues().stream().sorted().toList()) {
          supplemental.add(Map.of("mappingBean", beanName, "path", route,
              "methods", info.getMethodsCondition().isEmpty() ? List.of("ANY")
                  : info.getMethodsCondition().getMethods().stream().map(Enum::name).sorted().toList(),
              "handler", method.getBeanType().getName() + "#" + method.getMethod().getName()));
        }
      });
    });
    supplemental.sort(Comparator.comparing(row -> row.get("path").toString() + row.get("methods")));
    assertThat(supplemental.stream().map(row -> row.get("path").toString()))
        .contains("/actuator/health", "/actuator/info");
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("schema", "JKXTSpringMappings/v1");
    report.put("collection", "RequestMappingHandlerMapping");
    report.put("environment", "isolated-test");
    report.put("runId", System.getProperty("jkxt.release.runId", "UNBOUND"));
    report.put("gitSha", System.getProperty("jkxt.release.gitSha", "UNBOUND"));
    report.put("sourceDigest", System.getProperty("jkxt.release.sourceDigest", "UNBOUND"));
    report.put("collectedAt", Instant.now().toString());
    report.put("profile", "test");
    report.put("scope", "Actual MVC RequestMappingHandlerMapping beans in an embedded-server test; not production deployment proof");
    report.put("count", routes.size());
    report.put("database", database);
    report.put("migrationVersion", migrationVersion);
    report.put("failedMigrations", failedMigrations);
    report.put("redisPing", pong);
    report.put("serverAddress", serverAddress);
    report.put("mappings", routes);
    report.put("supplementalRequestMappings", supplemental);
    report.put("handlerMappingTypes", context.getBeansOfType(HandlerMapping.class).entrySet().stream()
        .map(entry -> Map.of("bean", entry.getKey(), "type", entry.getValue().getClass().getName()))
        .sorted(Comparator.comparing(row -> row.get("bean"))).toList());
    report.put("nonRequestMappingScope", "HandlerMapping types enumerated; static resource files, dynamic router functions, filters and background jobs require separate trace evidence.");
    Path output = Path.of(System.getProperty("jkxt.release.mappingOutput", "target/release-runtime-mappings.json"));
    Files.createDirectories(output.toAbsolutePath().getParent());
    mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
  }
}
