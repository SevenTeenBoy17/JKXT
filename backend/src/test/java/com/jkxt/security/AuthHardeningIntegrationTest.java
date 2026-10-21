package com.jkxt.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.jkxt.security.repo.UserAccountRepository;
import com.jkxt.security.auth.AuthService;
import com.jkxt.security.auth.AuthException;
import com.jkxt.security.auth.SessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {"spring.datasource.hikari.maximum-pool-size=4", "jkxt.deploy.mode=saas"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthHardeningIntegrationTest {
  private static final String PASSWORD = "AuthBoundary@2026";
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @MockitoSpyBean PasswordEncoder encoder;
  @Autowired UserAccountRepository users;
  @Autowired AuthService auth;
  @Autowired SessionService sessions;
  private final List<UUID> accounts = new ArrayList<>();
  private final List<UUID> organizations = new ArrayList<>();

  @AfterEach void cleanup() {
    reset(encoder);
    for (UUID id : accounts) {
      jdbc.update("delete from audit_log where actor_id = ?", id);
      jdbc.update("delete from app_user where id = ?", id);
    }
    for (UUID id : organizations) {
      jdbc.update("delete from audit_log where org_id = ?", id);
      jdbc.update("delete from role where org_id = ?", id);
      jdbc.update("delete from organization where id = ?", id);
    }
  }

  @Test void duplicateSchoolUsernamesRequireExplicitOrganizationAndSelectCorrectAccount() throws Exception {
    String name = "same." + UUID.randomUUID();
    Account first = account(name, "ADMIN", false);
    Account second = account(name, "ADMIN", false);
    assertThat(login(name, null).getResponse().getStatus()).isEqualTo(400);
    for (Account expected : List.of(first, second)) {
      MvcResult result = login(name, expected.slug());
      assertThat(result.getResponse().getStatus()).isEqualTo(200);
      assertThat(json(result).path("user").path("id").asText()).isEqualTo(expected.id().toString());
      assertThat(json(result).path("org").path("id").asText()).isEqualTo(expected.orgId().toString());
    }
  }

  @Test void mandatoryPasswordChangeIsEnforcedByApiAndCurrentDeviceRemainsUsable() throws Exception {
    Account target = account("must." + UUID.randomUUID(), "ADMIN", true);
    Cookie cookie = com.jkxt.support.TestPortal.sessionCookie(login(target.username(), null).getResponse());
    mvc.perform(get("/api/v1/classes").cookie(cookie)).andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_PASSWORD_CHANGE_REQUIRED"));
    mvc.perform(post("/api/v1/auth/me/change-password").cookie(cookie)
        .contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("currentPassword", PASSWORD, "newPassword", "Changed@2026"))))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/classes").cookie(cookie)).andExpect(status().isOk());
  }

  @ParameterizedTest
  @ValueSource(strings = {"disable", "reset-password", "force-logout", "change-password"})
  void loginAlreadyCheckingOldCredentialsCannotSurviveConcurrentRevocation(String action) throws Exception {
    Account target = account("race." + UUID.randomUUID(), "ADMIN", false);
    Account admin = account("operator." + UUID.randomUUID(), "SUPER_ADMIN", false);
    Cookie operator = com.jkxt.support.TestPortal.sessionCookie(login(admin.username(), null).getResponse());
    Cookie current = com.jkxt.support.TestPortal.sessionCookie(login(target.username(), null).getResponse());
    CountDownLatch checked = new CountDownLatch(1);
    CountDownLatch continueLogin = new CountDownLatch(1);
    AtomicBoolean paused = new AtomicBoolean();
    doAnswer(invocation -> {
      boolean matches = (boolean) invocation.callRealMethod();
      if (matches && paused.compareAndSet(false, true)) {
        checked.countDown();
        if (!continueLogin.await(10, TimeUnit.SECONDS)) throw new AssertionError("disable did not complete");
      }
      return matches;
    }).when(encoder).matches(eq(PASSWORD), anyString());
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<MvcResult> pending = pool.submit(() -> login(target.username(), null));
      assertThat(checked.await(10, TimeUnit.SECONDS)).isTrue();
      if ("change-password".equals(action)) {
        mvc.perform(post("/api/v1/auth/me/change-password").cookie(current)
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("currentPassword", PASSWORD, "newPassword", "ChangedRace@2026"))))
            .andExpect(status().isOk());
      } else {
        mvc.perform(post("/api/v1/admin/accounts/" + target.id() + "/" + action).cookie(operator))
            .andExpect(status().isOk());
      }
      continueLogin.countDown();
      MvcResult result = pending.get(15, TimeUnit.SECONDS);
      Cookie cookie = com.jkxt.support.TestPortal.sessionCookie(result.getResponse());
      if (cookie != null) {
        mvc.perform(get("/api/v1/classes").cookie(cookie)).andExpect(status().isUnauthorized());
      } else {
        assertThat(result.getResponse().getStatus()).isBetween(400, 499);
      }
    } finally {
      continueLogin.countDown();
      pool.shutdownNow();
    }
  }

  @Test void accountAndRolePermissionChangesRevokeExistingSessions() throws Exception {
    Account target = account("role." + UUID.randomUUID(), "ADMIN", false);
    Cookie cookie = com.jkxt.support.TestPortal.sessionCookie(login(target.username(), null).getResponse());
    jdbc.update("update app_user set role='VIEWER' where id=?", target.id());
    mvc.perform(get("/api/v1/classes").cookie(cookie)).andExpect(status().isUnauthorized());
  }

  @Test void rolePermissionReductionRevokesCachedGrants() throws Exception {
    Account target = account("permissions." + UUID.randomUUID(), "ADMIN", false);
    jdbc.update("insert into role(org_id,code,name,permissions,system_role) values (?,'ADMIN','Admin','[\"tenant:read\",\"masterdata:manage\"]'::jsonb,false)", target.orgId());
    Cookie cookie = com.jkxt.support.TestPortal.sessionCookie(login(target.username(), null).getResponse());
    jdbc.update("update role set permissions='[\"tenant:read\"]'::jsonb where org_id=? and code='ADMIN'", target.orgId());
    mvc.perform(get("/api/v1/classes").cookie(cookie)).andExpect(status().isUnauthorized());
  }

  @Test void staleEntitySaveCannotUndoCommittedCredentialRevocation() throws Exception {
    Account target = account("stale." + UUID.randomUUID(), "ADMIN", false);
    Cookie cookie = com.jkxt.support.TestPortal.sessionCookie(login(target.username(), null).getResponse());
    var stale = users.findById(target.id()).orElseThrow();
    jdbc.update("update app_user set auth_version=auth_version+1 where id=?", target.id());
    stale.setDisplayName("Updated display name");
    users.saveAndFlush(stale);
    assertThat(users.findById(target.id()).orElseThrow().getAuthVersion()).isGreaterThan(stale.getAuthVersion());
    mvc.perform(get("/api/v1/classes").cookie(cookie)).andExpect(status().isUnauthorized());
  }

  @Test void onboardingAdmittedBeforeRevocationCannotWriteWithStaleCredentials() throws Exception {
    Account target = account("onboarding." + UUID.randomUUID(), "ADMIN", false);
    jdbc.update("update app_user set onboarded=false where id=?", target.id());
    Cookie cookie = com.jkxt.support.TestPortal.sessionCookie(login(target.username(), null).getResponse());
    var admitted = sessions.find(cookie.getValue()).orElseThrow();
    // Represents revocation committed after the filter admitted this request but before its write.
    jdbc.update("update app_user set status='disabled' where id=?", target.id());
    assertThatThrownBy(() -> auth.markOnboarded(admitted)).isInstanceOf(AuthException.class);
    assertThat(users.findById(target.id()).orElseThrow().isOnboarded()).isFalse();
    assertThat(users.findById(target.id()).orElseThrow().getStatus()).isEqualTo("disabled");
  }

  @Test void suspendedOrganizationOperatorCanRestoreItButCannotUseSchoolBusiness() throws Exception {
    Account admin = account("recovery." + UUID.randomUUID(), "SUPER_ADMIN", false);
    Cookie cookie = com.jkxt.support.TestPortal.sessionCookie(login(admin.username(), null).getResponse());
    mvc.perform(post("/api/v1/admin/tenants/" + admin.orgId() + "/suspend").cookie(cookie)).andExpect(status().isOk());
    mvc.perform(get("/api/v1/classes").cookie(cookie)).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/admin/tenants").cookie(cookie)).andExpect(status().isOk());
    mvc.perform(get("/api/v1/admin/tenants").cookie(cookie).header("X-Tenant-Id", admin.orgId()))
        .andExpect(status().isOk()).andExpect(header().string("X-Tenant-Id", admin.orgId().toString()));
    mvc.perform(post("/api/v1/admin/tenants/" + admin.orgId() + "/restore").cookie(cookie)).andExpect(status().isOk());
    mvc.perform(get("/api/v1/classes").cookie(cookie)).andExpect(status().isOk());
  }

  @Test void platformRequestsPreserveExplicitSaasTenantContext() throws Exception {
    Account admin = account("context." + UUID.randomUUID(), "SUPER_ADMIN", false);
    Account school = account("school." + UUID.randomUUID(), "ADMIN", false);
    Cookie cookie = com.jkxt.support.TestPortal.sessionCookie(login(admin.username(), null).getResponse());
    mvc.perform(get("/api/v1/admin/tenants").cookie(cookie).header("X-Tenant-Id", school.orgId()))
        .andExpect(status().isOk()).andExpect(header().string("X-Tenant-Id", school.orgId().toString()));
  }

  @Test void suspendedAndRestoredSchoolDoesNotReviveOldUserSession() throws Exception {
    Account target = account("restored." + UUID.randomUUID(), "ADMIN", false);
    Cookie cookie = com.jkxt.support.TestPortal.sessionCookie(login(target.username(), null).getResponse());
    jdbc.update("update organization set status='suspended' where id=?", target.orgId());
    jdbc.update("update organization set status='active' where id=?", target.orgId());
    mvc.perform(get("/api/v1/classes").cookie(cookie)).andExpect(status().isUnauthorized());
  }

  @Test void accountCreationRejectsCaseInsensitiveDuplicateInSameSchool() throws Exception {
    Account admin = account("samecase." + UUID.randomUUID(), "SUPER_ADMIN", false);
    Cookie cookie = com.jkxt.support.TestPortal.sessionCookie(login(admin.username(), null).getResponse());
    mvc.perform(post("/api/v1/admin/accounts").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("tenantId", admin.orgId().toString(),
            "username", admin.username().toUpperCase(Locale.ROOT), "name", "Duplicate"))))
        .andExpect(status().isConflict());
  }

  @Test void preexistingCaseAmbiguityFailsClosedEvenWithOrganization() throws Exception {
    Account target = account("cases." + UUID.randomUUID(), "ADMIN", false);
    UUID duplicate = UUID.randomUUID();
    jdbc.update("""
        insert into app_user(id,org_id,username,display_name,password_hash,role,status,must_change_password,onboarded)
        values (?,?,?,'Duplicate',?,'ADMIN','active',false,true)
        """, duplicate, target.orgId(), target.username().toUpperCase(Locale.ROOT), encoder.encode(PASSWORD));
    accounts.add(duplicate);
    assertThat(login(target.username(), target.slug()).getResponse().getStatus()).isEqualTo(409);
  }

  @Test void demoGuardDisablesEveryMatchingAccountAcrossSchools() {
    account("aDmIn", "SUPER_ADMIN", false);
    Map<UUID, String> original = new HashMap<>();
    for (String name : DemoAccountSecurityGuard.DEMO_USERNAMES) {
      users.findAllByUsernameIgnoreCase(name).forEach(user -> original.put(user.getId(), user.getStatus()));
    }
    try {
      new DemoAccountSecurityGuard(users, true).disableSeededDemoAccounts();
      for (UUID id : original.keySet()) assertThat(users.findById(id).orElseThrow().getStatus()).isEqualTo("disabled");
    } finally {
      original.forEach((id, status) -> jdbc.update("update app_user set status=? where id=?", status, id));
    }
  }

  private Account account(String username, String role, boolean mustChange) {
    UUID org = UUID.randomUUID();
    String slug = "auth-" + org;
    jdbc.update("insert into organization(id,name,slug,type,status) values (?,? ,?,'school','active')", org, slug, slug);
    organizations.add(org);
    UUID id = UUID.randomUUID();
    jdbc.update("""
        insert into app_user(id,org_id,username,display_name,password_hash,role,status,must_change_password,onboarded)
        values (?,?,?,?,?,?,'active',?,true)
        """, id, org, username, username, encoder.encode(PASSWORD), role, mustChange);
    accounts.add(id);
    return new Account(id, org, slug, username);
  }

  private MvcResult login(String username, String organization) throws Exception {
    JsonNode captcha = json(mvc.perform(post("/api/v1/auth/captcha")).andExpect(status().isOk()).andReturn());
    Map<String, Object> body = new LinkedHashMap<>(Map.of("username", username, "password", PASSWORD,
        "captchaId", captcha.path("captchaId").asText(), "captcha", captcha.path("debugAnswer").asText()));
    if (organization != null) body.put("organization", organization);
    return mvc.perform(post(com.jkxt.support.TestPortal.loginPath(jdbc, username)).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(body))).andReturn();
  }

  private JsonNode json(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsString()); }
  private record Account(UUID id, UUID orgId, String slug, String username) {}
}
