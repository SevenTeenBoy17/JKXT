package com.jkxt.demo;

import com.jkxt.common.config.DeployProperties;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.nio.file.*;
import java.sql.*;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Read-only, fail-closed resource attestation, called before any Flyway write. */
@Component
public class DemoDeploymentGuard {
  public static final String REDIS_MARKER = "jkxt:environment:identity";
  private final DeployProperties deploy;
  private final DemoProperties demo;
  private final Environment env;
  private final StringRedisTemplate redis;

  public DemoDeploymentGuard(DeployProperties deploy, DemoProperties demo,
      Environment env, StringRedisTemplate redis) {
    this.deploy = deploy; this.demo = demo; this.env = env; this.redis = redis;
  }

  @PostConstruct
  public void verifyStartupConfiguration() {
    if (deploy.isDemo()) verifyConfiguration();
  }

  public void verify(DataSource migrationSource, DataSource applicationSource) {
    if (!deploy.isDemo()) return;
    try {
      verifyConfiguration();
      verifyDatabase(migrationSource);
      // Flyway may have an independent URL/user. BOTH connections must attest.
      if (applicationSource != migrationSource) verifyDatabase(applicationSource);
      require(("demo:" + demo.getEnvironmentId()).equals(redis.opsForValue().get(REDIS_MARKER)), "Redis identity mismatch");
      require(Long.valueOf(-1).equals(redis.getExpire(REDIS_MARKER)), "Redis identity must be persistent");
      verifyStorage();
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      // Do not include JDBC, passwords, Redis URLs, or provider keys in startup output.
      throw new IllegalStateException("DEMO_DEPLOYMENT_UNSAFE: resource attestation failed");
    }
  }

  private void verifyConfiguration() {
    try { UUID.fromString(demo.getEnvironmentId()); }
    catch (RuntimeException e) { throw unsafe("Explicit provisioned environment UUID required"); }
    require(!demo.getDatabaseName().isBlank() && !demo.getDatabaseUser().isBlank(), "Expected database and principal required");
    require(env.getProperty("spring.flyway.enabled", Boolean.class, true), "Flyway guard cannot be disabled in demo mode");
    require(env.getProperty("jkxt.security.disable-demo-accounts", Boolean.class, false), "Known seed accounts must remain disabled");
    require(!env.getProperty("jkxt.auth.captcha-debug", Boolean.class, false), "Captcha debug must be disabled");
    require(env.getProperty("jkxt.auth.cookie-domain", "").isBlank(), "Cookie must be host-only");
    require(!deploy.isExternalSmsEnabled(), "External SMS must be disabled");
    for (String key : List.of("jkxt.llm.enabled", "jkxt.llm.acceptance-mode", "jkxt.llm.allow-test-mocks",
        "jkxt.notify.webhook.enabled"))
      require(!env.getProperty(key, Boolean.class, false), "External effect enabled: " + key);
    // NotifyProperties defaults both channels ON: omission must never mean safe.
    for (String key : List.of("jkxt.notify.email.enabled", "jkxt.notify.sms.enabled"))
      require(!env.getProperty(key, Boolean.class, true), "External channel must explicitly be disabled: " + key);
    for (String key : List.of("jkxt.llm.api-key", "jkxt.llm.base-url", "jkxt.llm.fallback-base-url",
        "spring.mail.host", "spring.mail.password", "jkxt.notify.email.host", "jkxt.notify.email.password",
        "jkxt.notify.sms.api-key", "jkxt.notify.webhook.url", "jkxt.platform.api-key"))
      require(env.getProperty(key, "").isBlank(), "External configuration forbidden: " + key);
    if (env.getProperty("jkxt.security.strict-production-checks", Boolean.class, false)) {
      URI demoOrigin = origin(demo.getPublicOrigin()), schoolOrigin = origin(demo.getSchoolOrigin());
      require("https".equals(demoOrigin.getScheme()) && "https".equals(schoolOrigin.getScheme())
          && !demoOrigin.getHost().equalsIgnoreCase(schoolOrigin.getHost()), "Production demo requires a distinct HTTPS hostname");
      require(env.getProperty("jkxt.auth.cookie-secure", Boolean.class, false), "Production demo requires Secure cookies");
    }
  }

  private URI origin(String value) {
    try {
      URI uri = URI.create(value);
      require(uri.getHost()!=null && uri.getUserInfo()==null && uri.getQuery()==null && uri.getFragment()==null
          && (uri.getPath()==null || uri.getPath().isEmpty() || uri.getPath().equals("/")), "Invalid configured origin");
      return uri;
    } catch (IllegalArgumentException e) { throw unsafe("Invalid configured origin"); }
  }

  private void verifyDatabase(DataSource source) throws SQLException {
    try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
      try (ResultSet row = statement.executeQuery("""
          SELECT current_database(),current_user,r.rolsuper,r.rolcreatedb,r.rolcreaterole,r.rolbypassrls,
            i.environment_id::text,i.purpose,
            pg_has_role(current_user,c.relowner,'MEMBER'),
            has_table_privilege(current_user,'jkxt_environment.identity','INSERT,UPDATE,DELETE,TRUNCATE'),
            has_schema_privilege(current_user,'jkxt_environment','CREATE'),
            pg_has_role(current_user,(SELECT datdba FROM pg_database WHERE datname=current_database()),'MEMBER'),
            EXISTS (SELECT 1 FROM pg_roles elevated WHERE
              (elevated.rolsuper OR elevated.rolcreatedb OR elevated.rolcreaterole OR elevated.rolbypassrls
               OR elevated.rolname IN ('pg_read_all_data','pg_write_all_data','pg_execute_server_program','pg_read_server_files','pg_write_server_files'))
              AND pg_has_role(current_user,elevated.oid,'MEMBER'))
          FROM pg_roles r CROSS JOIN jkxt_environment.identity i
            JOIN pg_class c ON c.oid='jkxt_environment.identity'::regclass
          WHERE r.rolname=current_user
          """)) {
        require(row.next(), "Provisioned database identity is absent");
        require(demo.getDatabaseName().equals(row.getString(1)) && demo.getDatabaseUser().equals(row.getString(2)), "Database principal or database mismatch");
        for (int column : new int[]{3,4,5,6,9,10,11,12,13}) require(!row.getBoolean(column), "Database principal has unsafe privileges");
        require(demo.getEnvironmentId().equals(row.getString(7)) && "demo".equals(row.getString(8)), "Database environment mismatch");
        require(!row.next(), "Database identity must be unique");
      }
    }
  }

  private void verifyStorage() throws Exception {
    require(!demo.getStorageRoot().isBlank(), "Provisioned storage root required");
    Path configured = Path.of(demo.getStorageRoot()).toAbsolutePath().normalize();
    Path real = configured.toRealPath();
    require(configured.equals(real) && real.getParent()!=null && Files.isDirectory(real), "Storage root must be a real dedicated directory");
    require(!real.equals(Path.of(System.getProperty("user.home")).toRealPath()), "Home directory is not a demo storage root");
    Path marker = real.resolve(".jkxt-demo-environment");
    require(Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) && Files.size(marker)<128
        && Files.readString(marker).trim().equals("demo:" + demo.getEnvironmentId()), "Storage identity mismatch");
  }

  private static void require(boolean condition, String reason) { if (!condition) throw unsafe(reason); }
  private static IllegalStateException unsafe(String reason) { return new IllegalStateException("DEMO_DEPLOYMENT_UNSAFE: " + reason); }
}
