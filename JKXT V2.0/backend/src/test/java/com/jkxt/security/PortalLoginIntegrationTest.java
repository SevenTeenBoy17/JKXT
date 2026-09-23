package com.jkxt.security;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.jkxt.security.auth.PortalPurpose;
import com.jkxt.security.auth.SessionService;
import com.jkxt.security.repo.UserAccountRepository;
import org.springframework.boot.test.mock.mockito.SpyBean;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest @AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE) @ActiveProfiles("test")
class PortalLoginIntegrationTest {
  @Autowired ApplicationContext context;
  @Autowired MockMvc http;
  @Autowired JdbcTemplate jdbc;
  @SpyBean SessionService sessions;
  @SpyBean UserAccountRepository users;
  @SpyBean org.springframework.security.crypto.password.PasswordEncoder encoder;
  PortalHttpFixture f;
  @BeforeEach void setup() { f=new PortalHttpFixture(context); }
  @AfterEach void cleanup() { reset(sessions,users,encoder); f.close(); }
  @Test void wrongPortalDoesNotIssueOrRevokeOrCountSuccess() throws Exception {
    var a=f.superAdmin(); var existing=f.login(PortalPurpose.PLATFORM,a);
    long before=f.sessionCount(a.id());
    int logins=jdbc.queryForObject("select login_count from app_user where id=?",Integer.class,a.id());
    int audits=jdbc.queryForObject("select count(*) from audit_log where actor_id=? and action='AUTH_LOGIN_SUCCESS'",Integer.class,a.id());
    var rejected=f.loginResult(PortalPurpose.SCHOOL,a);
    assertEquals(403,rejected.getResponse().getStatus());
    assertNull(rejected.getResponse().getHeader("Set-Cookie"));
    assertTrue(rejected.getResponse().getContentAsString().contains("AUTH_PORTAL_MISMATCH"));
    assertEquals(before,f.sessionCount(a.id())); f.expectMe(existing,200);
    assertEquals(logins,jdbc.queryForObject("select login_count from app_user where id=?",Integer.class,a.id()));
    assertEquals(audits,jdbc.queryForObject("select count(*) from audit_log where actor_id=? and action='AUTH_LOGIN_SUCCESS'",Integer.class,a.id()));
  }

  @Test void reversePortalAndLegacyLoginCannotElevateSchoolOrSuperAccounts() throws Exception {
    var a=f.account("ADMIN"); var existing=f.login(PortalPurpose.SCHOOL,a);
    http.perform(f.loginRequest(PortalPurpose.PLATFORM,a).cookie(existing.cookie()).header("X-JKXT-Portal","school"))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH_PORTAL_MISMATCH"))
        .andExpect(header().doesNotExist("Set-Cookie"));
    f.expectMe(existing,200);
    var request=f.loginRequest(PortalPurpose.PLATFORM,f.superAdmin()).buildRequest(new org.springframework.mock.web.MockServletContext());
    http.perform(post("/api/v1/auth/login").header("Origin",PortalHttpFixture.ORIGIN).header("X-JKXT-Portal","platform")
        .contentType("application/json").content(request.getContentAsByteArray()))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH_PORTAL_MISMATCH"))
        .andExpect(header().doesNotExist("Set-Cookie"));
    assertEquals(0,f.sessionCount(f.superAdmin().id()));
    var wrongPassword=new PortalHttpFixture.TestAccount(f.superAdmin().id(),f.superAdmin().username(),"wrong-password");
    http.perform(f.loginRequest(PortalPurpose.SCHOOL,wrongPassword)).andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
  }

  @Test void replacementOnlyRevokesSelectedBrowserAndRejectsOldTabWithNewCookie() throws Exception {
    var a=f.account("ADMIN"); var b=f.account("ADMIN");
    jdbc.update("update app_user set onboarded=false where id=?",b.id());
    var old=f.login(PortalPurpose.SCHOOL,a); var otherBrowser=f.login(PortalPurpose.SCHOOL,a);
    var platform=f.login(PortalPurpose.PLATFORM,f.superAdmin());
    var result=http.perform(f.loginRequest(PortalPurpose.SCHOOL,b).cookie(old.cookie(),platform.cookie())
        .header("X-JKXT-Portal","platform")) // Endpoint, not selector, chooses replacement purpose.
        .andExpect(status().isOk()).andExpect(jsonPath("$.audience").value("SCHOOL")).andReturn();
    var fresh=f.from(result,PortalPurpose.SCHOOL);
    f.expectMe(old,401); f.expectMe(otherBrowser,200); f.expectMe(platform,200); f.expectMe(fresh,200);
    assertEquals(1,result.getResponse().getHeaders("Set-Cookie").size());
    http.perform(post("/api/v1/auth/me/onboarded").cookie(fresh.cookie()).header("X-JKXT-Portal","school")
        .header("X-JKXT-Session-Context",old.context()).header("X-JKXT-CSRF",old.csrfToken()))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SESSION_CONTEXT_CHANGED"));
    assertEquals(false,jdbc.queryForObject("select onboarded from app_user where id=?",Boolean.class,b.id()));
    http.perform(f.apply(post("/api/v1/auth/logout"),fresh)).andExpect(status().isOk())
        .andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.startsWith("JKXT_SCHOOL_SESSION=")));
    f.expectMe(platform,200); f.expectMe(otherBrowser,200);
  }

  @Test void discoveryReturnsAdditiveProtocolNoStoreAndNeverAcceptsLegacyOrCrossPurposeCookie() throws Exception {
    var a=f.account("ADMIN"); var s=f.login(PortalPurpose.SCHOOL,a);
    http.perform(get("/api/v1/auth/me").cookie(s.cookie()).header("X-JKXT-Portal","school"))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(jsonPath("$.audience").value("SCHOOL")).andExpect(jsonPath("$.demo.enabled").value(false))
        .andExpect(jsonPath("$.sessionContext").value(s.context())).andExpect(jsonPath("$.csrfToken").value(s.csrfToken()))
        .andExpect(jsonPath("$.expiresAt").isNotEmpty()).andExpect(jsonPath("$.user.id").value(a.id().toString()));
    http.perform(get("/api/v1/auth/me").cookie(new jakarta.servlet.http.Cookie("JKXT_SESSION",s.cookie().getValue())))
        .andExpect(status().isUnauthorized());
    http.perform(get("/api/v1/auth/me").cookie(new jakarta.servlet.http.Cookie("JKXT_PLATFORM_SESSION",s.cookie().getValue()))
        .header("X-JKXT-Portal","platform")).andExpect(status().isUnauthorized());
    http.perform(post("/api/v1/auth/me").cookie(s.cookie()).header("X-JKXT-CSRF",s.csrfToken()))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SESSION_CONTEXT_CHANGED"));
    http.perform(f.apply(post("/api/v1/auth/me"),s)).andExpect(status().isMethodNotAllowed());
  }

  @Test void explicitEmptyGrantsRemainEmptyInLoginMeAndOnboarding() throws Exception {
    var a=f.account("ADMIN");
    java.util.UUID org=jdbc.queryForObject("select org_id from app_user where id=?",java.util.UUID.class,a.id());
    String grants=jdbc.queryForObject("select permissions::text from role where org_id=? and code='ADMIN'",String.class,org);
    try {
      jdbc.update("update role set permissions='[]'::jsonb where org_id=? and code='ADMIN'",org);
      var result=http.perform(f.loginRequest(PortalPurpose.SCHOOL,a)).andExpect(status().isOk())
          .andExpect(jsonPath("$.permissions").isEmpty()).andReturn();
      var s=f.from(result,PortalPurpose.SCHOOL);
      http.perform(f.apply(get("/api/v1/auth/me"),s)).andExpect(status().isOk()).andExpect(jsonPath("$.permissions").isEmpty());
      http.perform(f.apply(post("/api/v1/auth/me/onboarded"),s)).andExpect(status().isOk()).andExpect(jsonPath("$.permissions").isEmpty());
    } finally { jdbc.update("update role set permissions=?::jsonb where org_id=? and code='ADMIN'",grants,org); }
  }

  @Test void loginRedisFailureRollsBackSuccessBookkeepingAndDoesNotReplaceOldSession() throws Exception {
    var a=f.account("ADMIN"); var old=f.login(PortalPurpose.SCHOOL,a);
    int before=jdbc.queryForObject("select login_count from app_user where id=?",Integer.class,a.id());
    doThrow(new org.springframework.data.redis.RedisConnectionFailureException("injected Redis failure"))
        .when(sessions).replace(any(),eq(PortalPurpose.SCHOOL),anyLong(),eq(old.cookie().getValue()));
    http.perform(f.loginRequest(PortalPurpose.SCHOOL,a).cookie(old.cookie())).andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("AUTH_STATE_UNAVAILABLE")).andExpect(header().doesNotExist("Set-Cookie"));
    assertEquals(before,jdbc.queryForObject("select login_count from app_user where id=?",Integer.class,a.id()));
    assertEquals(before,jdbc.queryForObject("select count(*) from audit_log where actor_id=? and action='AUTH_LOGIN_SUCCESS'",Integer.class,a.id()));
    assertEquals(1,f.sessionCount(a.id())); f.expectMe(old,200);
  }

  @Test void logoutRedisFailureIsNotSuccessAndDoesNotClearCookie() throws Exception {
    var a=f.account("ADMIN"); var s=f.login(PortalPurpose.SCHOOL,a);
    doThrow(new org.springframework.data.redis.RedisConnectionFailureException("injected Redis failure"))
        .when(sessions).revoke(s.cookie().getValue(),s.purpose());
    http.perform(f.apply(post("/api/v1/auth/logout"),s)).andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("AUTH_STATE_UNAVAILABLE")).andExpect(jsonPath("$.ok").doesNotExist())
        .andExpect(header().doesNotExist("Set-Cookie"));
    assertEquals(0,jdbc.queryForObject("select count(*) from audit_log where actor_id=? and action='AUTH_LOGOUT'",Integer.class,a.id()));
    f.expectMe(s,200);
  }

  @Test void passwordRedisFailureLeavesCommittedPasswordAndRejectsAllOldVersions() throws Exception {
    var a=f.account("ADMIN"); var s=f.login(PortalPurpose.SCHOOL,a); var other=f.login(PortalPurpose.SCHOOL,a);
    long version=jdbc.queryForObject("select auth_version from app_user where id=?",Long.class,a.id());
    doThrow(new org.springframework.data.redis.RedisConnectionFailureException("injected Redis failure"))
        .when(sessions).replace(any(),eq(PortalPurpose.SCHOOL),anyLong(),eq(s.cookie().getValue()));
    http.perform(f.apply(post("/api/v1/auth/me/change-password"),s).contentType("application/json").content(passwordBody(a)))
        .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH_REAUTHENTICATION_REQUIRED"))
        .andExpect(header().doesNotExist("Set-Cookie"));
    assertEquals(version+1,jdbc.queryForObject("select auth_version from app_user where id=?",Long.class,a.id()));
    f.expectMe(s,401); f.expectMe(other,401);
    reset(sessions);
    assertEquals(401,f.loginResult(PortalPurpose.SCHOOL,a).getResponse().getStatus());
    assertEquals(200,f.loginResult(PortalPurpose.SCHOOL,new PortalHttpFixture.TestAccount(a.id(),a.username(),"ChangedTest!2026")).getResponse().getStatus());
  }

  @Test void passwordDatabaseRollbackRequiresReauthenticationWithoutChangingPassword() throws Exception {
    var a=f.account("ADMIN"); var s=f.login(PortalPurpose.SCHOOL,a); var other=f.login(PortalPurpose.SCHOOL,a);
    String hash=jdbc.queryForObject("select password_hash from app_user where id=?",String.class,a.id());
    doThrow(new org.springframework.dao.DataAccessResourceFailureException("injected flush failure" )).when(users).flush();
    http.perform(f.apply(post("/api/v1/auth/me/change-password"),s).contentType("application/json").content(passwordBody(a)))
        .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH_REAUTHENTICATION_REQUIRED"))
        .andExpect(header().doesNotExist("Set-Cookie"));
    assertEquals(hash,jdbc.queryForObject("select password_hash from app_user where id=?",String.class,a.id()));
    assertEquals(0,jdbc.queryForObject("select count(*) from audit_log where actor_id=? and action='AUTH_PASSWORD_CHANGED'",Integer.class,a.id()));
    f.expectMe(s,401); f.expectMe(other,401); assertEquals(0,f.sessionCount(a.id()));
    reset(users); f.expectMe(f.login(PortalPurpose.SCHOOL,a),200);
  }

  @Test void passwordFailureAfterReplacementNeverReturnsOrRetainsNewSession() throws Exception {
    var a=f.account("ADMIN"); var s=f.login(PortalPurpose.SCHOOL,a); var other=f.login(PortalPurpose.SCHOOL,a);
    doThrow(new org.springframework.data.redis.RedisConnectionFailureException("injected revoke failure"))
        .when(sessions).revokeOtherSessions(eq(a.id()),notNull());
    http.perform(f.apply(post("/api/v1/auth/me/change-password"),s).contentType("application/json").content(passwordBody(a)))
        .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH_REAUTHENTICATION_REQUIRED"))
        .andExpect(header().doesNotExist("Set-Cookie"));
    f.expectMe(s,401); f.expectMe(other,401);
    // Only the old other-device key remains physically; DB-version validation already rejects it.
    assertEquals(1,f.sessionCount(a.id()));
    assertTrue(sessions.find(other.cookie().getValue(),PortalPurpose.SCHOOL).isPresent());
  }

  @Test void platformForceLogoutAndDisableRevokeSchoolAndPlatformIndexes() throws Exception {
    var operator=f.login(PortalPurpose.PLATFORM,f.superAdmin());
    var a=f.account("ADMIN"); var school=f.login(PortalPurpose.SCHOOL,a);
    // A legitimate role transition leaves an old SCHOOL key plus a new PLATFORM key for one user.
    jdbc.update("update app_user set role='SUPER_ADMIN' where id=?",a.id());
    var platform=f.login(PortalPurpose.PLATFORM,a); assertEquals(2,f.sessionCount(a.id()));
    http.perform(f.apply(post("/api/v1/admin/accounts/"+a.id()+"/force-logout"),operator)).andExpect(status().isOk());
    assertEquals(0,f.sessionCount(a.id())); f.expectMe(school,401); f.expectMe(platform,401);
    var again=f.login(PortalPurpose.PLATFORM,a);
    http.perform(f.apply(post("/api/v1/admin/accounts/"+a.id()+"/disable"),operator)).andExpect(status().isOk());
    assertEquals(0,f.sessionCount(a.id())); f.expectMe(again,401);
    assertEquals(401,f.loginResult(PortalPurpose.PLATFORM,a).getResponse().getStatus());
  }

  @Test void concurrentPasswordResetCannotIssueNewVersionUsingOldPassword() throws Exception {
    var a=f.account("ADMIN");
    var request=f.loginRequest(PortalPurpose.SCHOOL,a);
    String newHash=encoder.encode("ConcurrentReset!2026");
    var verifying=new java.util.concurrent.CountDownLatch(1);
    var release=new java.util.concurrent.CountDownLatch(1);
    doAnswer(invocation -> {
      verifying.countDown();
      if(!release.await(15,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Credential barrier timed out");
      return invocation.callRealMethod();
    }).when(encoder).matches(eq(a.password()),anyString());
    var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var login=pool.submit(()->http.perform(request).andReturn());
      assertTrue(verifying.await(15,java.util.concurrent.TimeUnit.SECONDS));
      var pid=new java.util.concurrent.CompletableFuture<Integer>();
      var resetPassword=pool.submit(()->jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Integer>)connection -> {
        try(var statement=connection.createStatement(); var rows=statement.executeQuery("select pg_backend_pid()")) {
          rows.next(); pid.complete(rows.getInt(1));
        }
        try(var update=connection.prepareStatement("update app_user set password_hash=? where id=?")) {
          update.setString(1,newHash); update.setObject(2,a.id()); return update.executeUpdate();
        }
      }));
      int resetPid=pid.get(10,java.util.concurrent.TimeUnit.SECONDS);
      boolean waiting=false; long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
      while(System.nanoTime()<deadline) {
        waiting=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",Boolean.class,resetPid));
        if(waiting) break;
        Thread.sleep(10);
      }
      assertTrue(waiting,"Password reset must wait on the same DB account lock as credential issuance");
      release.countDown();
      var result=login.get(15,java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(200,result.getResponse().getStatus()); assertEquals(1,resetPassword.get(15,java.util.concurrent.TimeUnit.SECONDS));
      f.expectMe(f.from(result,PortalPurpose.SCHOOL),401);
      reset(encoder);
      assertEquals(401,f.loginResult(PortalPurpose.SCHOOL,a).getResponse().getStatus());
    } finally { release.countDown(); pool.shutdownNow(); }
  }

  @Test void responseOrganizationAndTermFollowAuthorizedActiveTenant() throws Exception {
    var deploy=context.getBean(com.jkxt.common.config.DeployProperties.class);
    String mode=deploy.getMode(); var target=java.util.UUID.randomUUID();
    jdbc.update("insert into organization(id,name,slug) values(?,?,?)",target,"Active tenant fixture","portal-"+target);
    try {
      deploy.setMode("saas");
      var a=f.superAdmin(); var s=f.login(PortalPurpose.PLATFORM,a);
      http.perform(f.apply(get("/api/v1/auth/me"),s).header("X-Tenant-Id",target))
          .andExpect(status().isOk()).andExpect(jsonPath("$.org.id").value(target.toString()))
          .andExpect(jsonPath("$.term").doesNotExist()).andExpect(jsonPath("$.sessionContext").value(s.context()));
      http.perform(f.apply(post("/api/v1/auth/me/onboarded"),s).header("X-Tenant-Id",target))
          .andExpect(status().isOk()).andExpect(jsonPath("$.org.id").value(target.toString()))
          .andExpect(jsonPath("$.sessionContext").value(s.context()));
      var result=http.perform(f.apply(post("/api/v1/auth/me/change-password"),s).header("X-Tenant-Id",target)
          .contentType("application/json").content(passwordBody(a)))
          .andExpect(status().isOk()).andExpect(jsonPath("$.org.id").value(target.toString())).andReturn();
      f.expectMe(f.from(result,PortalPurpose.PLATFORM),200);
    } finally { deploy.setMode(mode); jdbc.update("delete from organization where id=?",target); }
  }

  @Test void suspendedOrganizationAndPrivateMismatchNeverIssueSessions() throws Exception {
    var a=f.account("ADMIN"); var org=java.util.UUID.randomUUID();
    jdbc.update("insert into organization(id,name,slug,status) values(?,?,?,'suspended')",org,"Suspended test","portal-"+org);
    var deploy=context.getBean(com.jkxt.common.config.DeployProperties.class); String mode=deploy.getMode();
    try {
      jdbc.update("update app_user set org_id=? where id=?",org,a.id());
      http.perform(f.loginRequest(PortalPurpose.SCHOOL,a)).andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("TENANT_SUSPENDED")).andExpect(header().doesNotExist("Set-Cookie"));
      jdbc.update("update organization set status='active' where id=?",org); deploy.setMode("private");
      http.perform(f.loginRequest(PortalPurpose.SCHOOL,a)).andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("TENANT_VIOLATION")).andExpect(header().doesNotExist("Set-Cookie"));
      assertEquals(0,f.sessionCount(a.id()));
      assertEquals(0,jdbc.queryForObject("select login_count from app_user where id=?",Integer.class,a.id()));
    } finally {
      deploy.setMode(mode);
      jdbc.update("update app_user set org_id=(select id from organization where slug='demo') where id=?",a.id());
      jdbc.update("delete from audit_log where org_id=?",org); jdbc.update("delete from organization where id=?",org);
    }
  }
  private String passwordBody(PortalHttpFixture.TestAccount a) throws Exception {
    return context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class).writeValueAsString(
        java.util.Map.of("currentPassword",a.password(),"newPassword","ChangedTest!2026"));
  }

  @Test void forgedAuthorityFieldsAreRejectedWithoutSessionOrSuccessSideEffects() throws Exception {
    var mapper=context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
    for(var purpose:new PortalPurpose[]{PortalPurpose.SCHOOL,PortalPurpose.PLATFORM}) {
      var a=purpose==PortalPurpose.SCHOOL?f.account("ADMIN"):f.superAdmin();
      var existing=f.login(purpose,a);
      int before=jdbc.queryForObject("select login_count from app_user where id=?",Integer.class,a.id());
      for(String field:new String[]{"role","org","orgId","audience"}) {
        var request=f.loginRequest(purpose,a);
        var json=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(
            request.buildRequest(new org.springframework.mock.web.MockServletContext()).getContentAsByteArray());
        json.put(field,field.equals("role")?"SUPER_ADMIN":field.equals("audience")?"PLATFORM":java.util.UUID.randomUUID().toString());
        http.perform(request.content(mapper.writeValueAsString(json)).cookie(existing.cookie()))
            .andExpect(status().isBadRequest()).andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(jsonPath("$.traceId").isNotEmpty());
        assertEquals(1,f.sessionCount(a.id())); f.expectMe(existing,200);
        assertEquals(before,jdbc.queryForObject("select login_count from app_user where id=?",Integer.class,a.id()));
        assertEquals(before,jdbc.queryForObject("select count(*) from audit_log where actor_id=? and action='AUTH_LOGIN_SUCCESS'",Integer.class,a.id()));
      }
    }
  }
}
