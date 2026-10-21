package com.jkxt.security;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"spring.datasource.hikari.maximum-pool-size=4", "jkxt.deploy.mode=saas"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InitialOperatorProvisioningIntegrationTest {
  private static final char[] PASSWORD = "SyntheticBootstrap@2026".toCharArray();
  @Autowired DataSource dataSource;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  private final List<UUID> orgs = new ArrayList<>();
  private final Map<UUID, String> demoStatuses = new HashMap<>();
  private InitialOperatorProvisioner provisioner;

  @BeforeEach void prepare() {
    provisioner = new InitialOperatorProvisioner(dataSource::getConnection);
    jdbc.query("select id,status from app_user where lower(username) in ('admin','ops.demo','jwc.demo','tenant.test','platform.super')",
        rs -> { while (rs.next()) demoStatuses.put(rs.getObject(1, UUID.class), rs.getString(2)); return null; });
    demoStatuses.keySet().forEach(id -> jdbc.update("update app_user set status='disabled' where id=?", id));
  }
  @AfterEach void cleanup() {
    for (UUID org : orgs) {
      jdbc.update("delete from audit_log where org_id=?", org);
      jdbc.update("delete from app_user where org_id=?", org);
      jdbc.update("delete from organization where id=?", org);
    }
    demoStatuses.forEach((id, status) -> jdbc.update("update app_user set status=? where id=?", status, id));
  }
  private InitialOperatorProvisioner.Request request() {
    UUID org = UUID.randomUUID();
    String slug = "bootstrap-" + org;
    jdbc.update("insert into organization(id,name,slug) values (?,?,?)", org, "Synthetic bootstrap school", slug);
    orgs.add(org);
    return new InitialOperatorProvisioner.Request(org, slug, "operator." + org, "Initial operator", PASSWORD.clone());
  }
  @Test void createsHashedAuditedForcedChangeOperatorThenRefusesRepeatWithoutChangingPassword() throws Exception {
    var request = request();
    UUID user = provisioner.provision(request);
    var row = jdbc.queryForMap("select * from app_user where id=?", user);
    assertThat(row.get("org_id")).isEqualTo(request.organizationId());
    assertThat(row.get("role")).isEqualTo("SUPER_ADMIN");
    assertThat(row.get("status")).isEqualTo("active");
    assertThat(row.get("must_change_password")).isEqualTo(true);
    String hash = (String) row.get("password_hash");
    assertThat(new BCryptPasswordEncoder().matches(new String(PASSWORD), hash)).isTrue();
    assertThat(jdbc.queryForObject("select count(*) from audit_log where actor_id=? and action='INITIAL_OPERATOR_CREATED'", Integer.class, user)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select payload::text from audit_log where actor_id=?", String.class, user))
        .doesNotContain(new String(PASSWORD), hash);
    assertThatThrownBy(() -> provisioner.provision(request)).isInstanceOf(IllegalStateException.class).hasMessageContaining("already exists");
    assertThat(jdbc.queryForObject("select password_hash from app_user where id=?", String.class, user)).isEqualTo(hash);

    var captcha = mapper.readTree(mvc.perform(post("/api/v1/auth/captcha")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    Cookie cookie = com.jkxt.support.TestPortal.sessionCookie(mvc.perform(post(com.jkxt.support.TestPortal.loginPath(jdbc, request.username())).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("username", request.username(), "password", new String(PASSWORD),
            "captchaId", captcha.path("captchaId").asText(), "captcha", captcha.path("debugAnswer").asText()))))
        .andExpect(status().isOk()).andReturn().getResponse());
    mvc.perform(get("/api/v1/classes").cookie(cookie)).andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_PASSWORD_CHANGE_REQUIRED"));
    mvc.perform(post("/api/v1/auth/me/change-password").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("currentPassword", new String(PASSWORD), "newPassword", "ChangedBootstrap@2026"))))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/classes").cookie(cookie)).andExpect(status().isOk());
  }
  @Test void rejectsDisabledFormalOperatorEvenInAnotherSchool() throws Exception {
    var first = request();
    UUID user = provisioner.provision(first);
    jdbc.update("update app_user set status='disabled',deleted_at=now() where id=?", user);
    var next = request();
    assertThatThrownBy(() -> provisioner.provision(next)).isInstanceOf(IllegalStateException.class).hasMessageContaining("already exists");
    assertThat(jdbc.queryForObject("select count(*) from app_user where org_id=?", Integer.class, next.organizationId())).isZero();
  }
  @Test void rejectsGlobalCaseInsensitiveDuplicateAndReservedDemoUsername() {
    var first = request();
    jdbc.update("insert into app_user(org_id,username,display_name,password_hash,role) values (?,?,'Duplicate','unused','VIEWER')",
        first.organizationId(), first.username().toUpperCase(Locale.ROOT));
    var other = request();
    var duplicate = new InitialOperatorProvisioner.Request(other.organizationId(), other.organizationSlug(), first.username(), "Operator", PASSWORD.clone());
    assertThatThrownBy(() -> provisioner.provision(duplicate)).isInstanceOf(IllegalStateException.class).hasMessageContaining("username");
    var reserved = new InitialOperatorProvisioner.Request(other.organizationId(), other.organizationSlug(), "Admin", "Operator", PASSWORD.clone());
    assertThatThrownBy(() -> provisioner.provision(reserved)).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void rejectsAmbiguousInactiveOrMissingOrganization() {
    var request = request();
    var wrong = new InitialOperatorProvisioner.Request(request.organizationId(), "wrong-school", request.username(), "Operator", PASSWORD.clone());
    assertThatThrownBy(() -> provisioner.provision(wrong)).isInstanceOf(IllegalStateException.class).hasMessageContaining("organization");
    jdbc.update("update organization set status='suspended' where id=?", request.organizationId());
    assertThatThrownBy(() -> provisioner.provision(request)).isInstanceOf(IllegalStateException.class).hasMessageContaining("organization");
    var missing = new InitialOperatorProvisioner.Request(UUID.randomUUID(), request.organizationSlug(), request.username(), "Operator", PASSWORD.clone());
    assertThatThrownBy(() -> provisioner.provision(missing)).isInstanceOf(IllegalStateException.class).hasMessageContaining("organization");
  }
  @Test void refusesWhenAnyDemoAccountRemainsEnabled() {
    var request = request();
    UUID demo = demoStatuses.keySet().iterator().next();
    jdbc.update("update app_user set status='active' where id=?", demo);
    assertThatThrownBy(() -> provisioner.provision(request)).isInstanceOf(IllegalStateException.class).hasMessageContaining("demo");
  }
  @Test void competingDifferentSchoolInvocationsCreateOnlyOneFormalOperator() throws Exception {
    var requests = List.of(request(), request());
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Boolean>> pending = requests.stream().map(request -> pool.submit(() -> {
        start.await();
        try { provisioner.provision(request); return true; }
        catch (IllegalStateException expected) { assertThat(expected).hasMessageContaining("already exists"); return false; }
      })).toList();
      start.countDown();
      int successes = 0;
      for (Future<Boolean> result : pending) if (result.get(20, TimeUnit.SECONDS)) successes++;
      assertThat(successes).isEqualTo(1);
      assertThat(jdbc.queryForObject("select count(*) from app_user where org_id in (?,?)", Integer.class,
          requests.get(0).organizationId(), requests.get(1).organizationId())).isEqualTo(1);
    } finally { pool.shutdownNow(); }
  }

  @Test void auditFailureRollsBackTheNewAccountInTheRealDatabase() {
    var request = request();
    var failingAudit = new InitialOperatorProvisioner(() -> {
      Connection real = dataSource.getConnection();
      return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
        if (method.getName().equals("prepareStatement") && args[0] instanceof String sql && sql.contains("insert into audit_log")) {
          throw new SQLException("Injected audit failure");
        }
        try { return method.invoke(real, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
      });
    });
    assertThatThrownBy(() -> failingAudit.provision(request)).isInstanceOf(SQLException.class).hasMessage("Injected audit failure");
    assertThat(jdbc.queryForObject("select count(*) from app_user where org_id=?", Integer.class, request.organizationId())).isZero();
    assertThat(jdbc.queryForObject("select count(*) from audit_log where org_id=?", Integer.class, request.organizationId())).isZero();
  }

  @Test void unsafePasswordAndNilOrganizationNeverCreateAnAccount() {
    var request = request();
    for (char[] password : List.of("short1".toCharArray(), "abcdefghijklmnop".toCharArray(), ("a1".repeat(37)).toCharArray())) {
      var unsafe = new InitialOperatorProvisioner.Request(request.organizationId(), request.organizationSlug(), request.username(), request.displayName(), password);
      assertThatThrownBy(() -> provisioner.provision(unsafe)).isInstanceOf(IllegalArgumentException.class);
    }
    var nil = new InitialOperatorProvisioner.Request(new UUID(0, 0), request.organizationSlug(), request.username(), request.displayName(), PASSWORD.clone());
    assertThatThrownBy(() -> provisioner.provision(nil)).isInstanceOf(IllegalArgumentException.class);
    assertThat(jdbc.queryForObject("select count(*) from app_user where org_id=?", Integer.class, request.organizationId())).isZero();
  }
}
