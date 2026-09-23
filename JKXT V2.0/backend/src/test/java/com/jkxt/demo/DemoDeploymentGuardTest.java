package com.jkxt.demo;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.jkxt.common.config.DeployProperties;
import java.nio.file.*;
import java.sql.*;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

class DemoDeploymentGuardTest {
  @TempDir Path storage;
  DeployProperties deploy;
  DemoProperties demo;
  MockEnvironment env;
  DataSource source;
  ResultSet row;
  StringRedisTemplate redis;
  ValueOperations<String,String> values;
  DemoDeploymentGuard guard;

  @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
    deploy=new DeployProperties();deploy.setMode("demo");
    demo=new DemoProperties();demo.setEnvironmentId(UUID.randomUUID().toString());
    demo.setDatabaseName("expected-database");demo.setDatabaseUser("restricted-runtime");demo.setStorageRoot(storage.toString());
    env=new MockEnvironment().withProperty("jkxt.security.disable-demo-accounts","true")
        .withProperty("jkxt.notify.email.enabled","false").withProperty("jkxt.notify.sms.enabled","false");
    Files.writeString(storage.resolve(".jkxt-demo-environment"),"demo:"+demo.getEnvironmentId());
    source=mock(DataSource.class);var connection=mock(Connection.class);var statement=mock(Statement.class);row=mock(ResultSet.class);
    when(source.getConnection()).thenReturn(connection);when(connection.createStatement()).thenReturn(statement);
    when(statement.executeQuery(anyString())).thenReturn(row);when(row.next()).thenReturn(true,false);
    when(row.getString(1)).thenReturn(demo.getDatabaseName());when(row.getString(2)).thenReturn(demo.getDatabaseUser());
    when(row.getString(7)).thenReturn(demo.getEnvironmentId());when(row.getString(8)).thenReturn("demo");
    redis=mock(StringRedisTemplate.class);values=mock(ValueOperations.class);when(redis.opsForValue()).thenReturn(values);
    when(values.get(DemoDeploymentGuard.REDIS_MARKER)).thenReturn("demo:"+demo.getEnvironmentId());
    when(redis.getExpire(DemoDeploymentGuard.REDIS_MARKER)).thenReturn(-1L);
    guard=new DemoDeploymentGuard(deploy,demo,env,redis);
  }
  @Test void provisionedResourcesPassWithoutAnyResourceWrites() {
    assertThatCode(()->guard.verify(source,source)).doesNotThrowAnyException();
    verify(values,never()).set(anyString(),anyString());
  }
  @ParameterizedTest @ValueSource(ints={3,4,5,6,9,10,11,12,13})
  void unsafeDatabasePrivilegesAreRejected(int column) throws Exception {
    when(row.getBoolean(column)).thenReturn(true);
    assertThatThrownBy(()->guard.verify(source,source)).hasMessageContaining("unsafe privileges");
    verifyNoInteractions(redis);
  }
  @ParameterizedTest @ValueSource(ints={1,2,7,8}) void databaseIdentityMismatchRejected(int column) throws Exception {
    when(row.getString(column)).thenReturn("wrong");
    assertThatThrownBy(()->guard.verify(source,source)).isInstanceOf(IllegalStateException.class);
  }
  @Test void absentDatabaseMarkerIsNotCreated() throws Exception {
    when(row.next()).thenReturn(false);
    assertThatThrownBy(()->guard.verify(source,source)).hasMessageContaining("absent");
  }
  @Test void redisMarkerMustMatchAndNotExpire() {
    when(values.get(DemoDeploymentGuard.REDIS_MARKER)).thenReturn("production");
    assertThatThrownBy(()->guard.verify(source,source)).hasMessageContaining("Redis identity mismatch");
  }
  @Test void expiringRedisMarkerRejected() {
    when(redis.getExpire(DemoDeploymentGuard.REDIS_MARKER)).thenReturn(60L);
    assertThatThrownBy(()->guard.verify(source,source)).hasMessageContaining("persistent");
  }
  @Test void storageMarkerMustBePreprovisioned() throws Exception {
    Files.delete(storage.resolve(".jkxt-demo-environment"));
    assertThatThrownBy(()->guard.verify(source,source)).hasMessageContaining("Storage identity mismatch");
    assertThat(storage.resolve(".jkxt-demo-environment")).doesNotExist();
  }
  @Test void storageMarkerCannotPointElsewhere() throws Exception {
    Files.delete(storage.resolve(".jkxt-demo-environment"));
    Path elsewhere=storage.resolve("other");Files.writeString(elsewhere,"demo:"+demo.getEnvironmentId());
    Files.createSymbolicLink(storage.resolve(".jkxt-demo-environment"),elsewhere);
    assertThatThrownBy(()->guard.verify(source,source)).hasMessageContaining("Storage identity mismatch");
  }
  @ParameterizedTest @ValueSource(strings={"jkxt.llm.enabled","jkxt.notify.email.enabled","jkxt.notify.sms.enabled","jkxt.notify.webhook.enabled","jkxt.auth.captcha-debug"})
  void externalEffectsAndDebugAreRejected(String key) {
    env.setProperty(key,"true");
    assertThatThrownBy(()->guard.verify(source,source)).isInstanceOf(IllegalStateException.class);verifyNoInteractions(source);
  }
  @Test void notificationDefaultsAreUnsafe() {
    env=new MockEnvironment().withProperty("jkxt.security.disable-demo-accounts","true");
    assertThatThrownBy(()->new DemoDeploymentGuard(deploy,demo,env,redis).verify(source,source)).hasMessageContaining("explicitly be disabled");
  }
  @Test void cookieDomainAndProviderSecretsAreForbidden() {
    env.setProperty("jkxt.auth.cookie-domain","school.example");
    assertThatThrownBy(()->guard.verify(source,source)).hasMessageContaining("host-only");
    env.setProperty("jkxt.auth.cookie-domain","");env.setProperty("jkxt.llm.api-key","test-secret");
    assertThatThrownBy(()->guard.verify(source,source)).hasMessageContaining("External configuration forbidden").hasMessageNotContaining("test-secret");
  }
  @Test void demoCannotDisableFlywayAndEvadeGuard() {
    env.setProperty("spring.flyway.enabled","false");
    assertThatThrownBy(guard::verifyStartupConfiguration).hasMessageContaining("cannot be disabled");
  }
  @Test void productionDifferentPortsDoNotIsolateCookies() {
    env.setProperty("jkxt.security.strict-production-checks","true");env.setProperty("jkxt.auth.cookie-secure","true");
    demo.setPublicOrigin("https://school.example:8443");demo.setSchoolOrigin("https://school.example:443");
    assertThatThrownBy(()->guard.verify(source,source)).hasMessageContaining("distinct HTTPS hostname");
  }
  @Test void nonDemoModeDoesNotPretendToAttestDemoResources() {
    deploy.setMode("private");assertThat(deploy.isDemo()).isFalse();
    guard.verify(source,source);verifyNoInteractions(source,redis);
  }
  @Test void rejectedAttestationPreventsEvenFirstFlywayWrite() {
    demo.setEnvironmentId("");var flyway=mock(Flyway.class);var configuration=mock(Configuration.class);
    when(flyway.getConfiguration()).thenReturn(configuration);when(configuration.getDataSource()).thenReturn(source);
    var strategy=new DemoMigrationConfiguration().demoGuardedMigration(guard,source);
    assertThatThrownBy(()->strategy.migrate(flyway)).hasMessageContaining("UUID");verify(flyway,never()).migrate();
  }
  @Test void successfulAttestationPrecedesMigration() {
    var flyway=mock(Flyway.class);var configuration=mock(Configuration.class);
    when(flyway.getConfiguration()).thenReturn(configuration);when(configuration.getDataSource()).thenReturn(source);
    new DemoMigrationConfiguration().demoGuardedMigration(guard,source).migrate(flyway);
    var order=inOrder(values,flyway);order.verify(values).get(DemoDeploymentGuard.REDIS_MARKER);order.verify(flyway).migrate();
  }

  @Test void realProvisionedDatabaseRedisAndStorageAreCheckedBeforeMigration() throws Exception {
    DemoSchemaIntegrationTest.verifyIsolation();
    String runtimeRole="demo_guard_"+UUID.randomUUID().toString().replace("-","");
    String runtimePassword=UUID.randomUUID().toString();
    var admin=new DriverManagerDataSource(DemoSchemaIntegrationTest.url,DemoSchemaIntegrationTest.user,DemoSchemaIntegrationTest.password);
    var factory=new LettuceConnectionFactory(System.getenv("JKXT_TEST_REDIS_HOST"),Integer.parseInt(System.getenv("JKXT_TEST_REDIS_PORT")));
    factory.afterPropertiesSet();factory.start();
    var actualRedis=new StringRedisTemplate(factory);
    boolean schemaCreated=false,roleCreated=false,markerCreated=false;
    try(var c=admin.getConnection();var s=c.createStatement()) {
      // The test owns this exact marker namespace in the dedicated test service.
      // Refuse an existing schema/key rather than overwriting external fixtures.
      s.execute("CREATE SCHEMA jkxt_environment");schemaCreated=true;
      s.execute("CREATE TABLE jkxt_environment.identity(environment_id uuid NOT NULL,purpose text NOT NULL)");
      DemoSchemaIntegrationTest.sql(c,"INSERT INTO jkxt_environment.identity VALUES (?,'demo')",UUID.fromString(demo.getEnvironmentId()));
      s.execute("CREATE ROLE "+runtimeRole+" LOGIN PASSWORD '"+runtimePassword+"' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS");roleCreated=true;
      s.execute("GRANT USAGE ON SCHEMA jkxt_environment TO "+runtimeRole);
      s.execute("GRANT SELECT ON jkxt_environment.identity TO "+runtimeRole);
      demo.setDatabaseName(c.getCatalog());demo.setDatabaseUser(runtimeRole);
      assertThat(actualRedis.opsForValue().setIfAbsent(DemoDeploymentGuard.REDIS_MARKER,"demo:"+demo.getEnvironmentId())).isTrue();markerCreated=true;
      var restricted=new DriverManagerDataSource(DemoSchemaIntegrationTest.url,runtimeRole,runtimePassword);
      var actualGuard=new DemoDeploymentGuard(deploy,demo,env,actualRedis);
      assertThatCode(()->actualGuard.verify(restricted,restricted)).doesNotThrowAnyException();
      // A valid marker never makes the provisioner's superuser connection safe.
      demo.setDatabaseUser(DemoSchemaIntegrationTest.user);
      assertThatThrownBy(()->actualGuard.verify(admin,admin)).hasMessageContaining("unsafe privileges");
      demo.setDatabaseUser(runtimeRole);
      actualRedis.opsForValue().set(DemoDeploymentGuard.REDIS_MARKER,"wrong-environment");
      assertThatThrownBy(()->actualGuard.verify(restricted,restricted)).hasMessageContaining("Redis identity mismatch");
      actualRedis.opsForValue().set(DemoDeploymentGuard.REDIS_MARKER,"demo:"+demo.getEnvironmentId());
      s.execute("UPDATE jkxt_environment.identity SET purpose='production'");
      assertThatThrownBy(()->actualGuard.verify(restricted,restricted)).hasMessageContaining("Database environment mismatch");
    } finally {
      if(markerCreated)actualRedis.delete(DemoDeploymentGuard.REDIS_MARKER);
      factory.destroy();
      try(var c=admin.getConnection();var s=c.createStatement()) {
        if(schemaCreated)s.execute("DROP SCHEMA jkxt_environment CASCADE");
        if(roleCreated)s.execute("DROP ROLE "+runtimeRole);
      }
    }
  }
}
