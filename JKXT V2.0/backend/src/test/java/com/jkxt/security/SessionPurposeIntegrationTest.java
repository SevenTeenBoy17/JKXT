package com.jkxt.security;

import com.jkxt.security.auth.*;
import com.jkxt.common.config.DeployProperties;
import com.jkxt.common.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.*;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SessionPurposeIntegrationTest extends PortalSessionFixture {
  @Test void purposeCookieAndEnvelopeCannotBeInterchanged() throws Exception {
    var school=account("ADMIN"); var platform=account("SUPER_ADMIN");
    http.perform(get("/api/v1/auth/me").cookie(cookie(school),cookie(platform)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.user.role").value("ADMIN"));
    http.perform(get("/api/v1/auth/me").cookie(cookie(school),cookie(platform)).header("X-JKXT-Portal","platform"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.user.role").value("SUPER_ADMIN"));
    http.perform(get("/api/v1/auth/me").cookie(new Cookie(sessions.cookieName(PortalPurpose.SCHOOL),platform.principal().sessionId())))
        .andExpect(status().isUnauthorized());
    assertTrue(sessions.find(platform.principal().sessionId(),PortalPurpose.SCHOOL).isEmpty());
  }
  @Test void legacyCookieAndLegacySerializationAreNotRecovered() throws Exception {
    var s=account("ADMIN");
    http.perform(get("/api/v1/auth/me").cookie(new Cookie("JKXT_SESSION",s.principal().sessionId())))
        .andExpect(status().isUnauthorized());
    redis.opsForValue().set(key(s),mapper.writeValueAsString(s.principal()));
    http.perform(get("/api/v1/auth/me").cookie(cookie(s))).andExpect(status().isUnauthorized());
  }
  @Test void duplicateCookiesAndUnknownOrRepeatedHeadersFailClosed() throws Exception {
    var s=account("ADMIN");
    http.perform(get("/api/v1/auth/me").cookie(cookie(s),cookie(s))).andExpect(status().isBadRequest());
    for(String portal:List.of("PLATFORM","unknown","demo","school,platform"," school"))
      http.perform(get("/api/v1/auth/me").cookie(cookie(s)).header("X-JKXT-Portal",portal)).andExpect(status().isBadRequest());
    http.perform(get("/api/v1/auth/me").cookie(cookie(s)).header("X-JKXT-Portal","school","platform"))
        .andExpect(status().isBadRequest());
  }
  @Test void lookupDoesNotRenewAndExpiryRejects() throws Exception {
    var s=account("ADMIN"); redis.expire(key(s),Duration.ofSeconds(20));
    sessions.find(s.principal().sessionId(),PortalPurpose.SCHOOL);
    assertTrue(redis.getExpire(key(s))<=20);
    var stored=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(redis.opsForValue().get(key(s)));
    ((com.fasterxml.jackson.databind.node.ObjectNode)stored.get("envelope")).put("expiresAt",java.time.Instant.now().minusSeconds(60).toString());
    redis.opsForValue().set(key(s),mapper.writeValueAsString(stored),Duration.ofSeconds(20));
    http.perform(get("/api/v1/auth/me").cookie(cookie(s))).andExpect(status().isUnauthorized());
    redis.delete(key(s));
    http.perform(get("/api/v1/auth/me").cookie(cookie(s))).andExpect(status().isUnauthorized());
  }
  @Test void disabledDeletedPasswordAndRoleChangesInvalidateOldSessions() throws Exception {
    for(String update:List.of("status='disabled'","deleted_at=now()","password_hash='changed'","role='VIEWER'")) {
      var s=account("ADMIN");
      jdbc.update("update app_user set "+update+" where id=?",s.principal().userId());
      http.perform(write(s)).andExpect(status().isUnauthorized());
      assertUnchanged(s);
    }
  }
  @Test void disabledOrgAndPrivateOrgMismatchRejectBeforeBusiness() throws Exception {
    var s=account("ADMIN"); jdbc.update("update organization set status='suspended' where id=?",s.principal().orgId());
    http.perform(write(s)).andExpect(status().isUnauthorized()); assertUnchanged(s);
    var other=account("ADMIN"); deploy.setMode("private");
    http.perform(write(other)).andExpect(status().isForbidden()); assertUnchanged(other);
  }
  @Test void emptyRoleGrantsAreAuthoritativeAndPermissionChangesRefresh() throws Exception {
    var s=account("ADMIN");
    jdbc.update("update role set permissions='[]'::jsonb where org_id=?",s.principal().orgId());
    http.perform(get("/api/v1/teachers").cookie(cookie(s)).header("X-JKXT-Session-Context",s.envelope().context()))
        .andExpect(status().isForbidden());
    assertTrue(guard.validateCurrent(s.envelope()).principal().permissions().isEmpty());
  }
  @Test void saasTenantSwitchRequiresPlatformAndActiveTarget() throws Exception {
    var school=account("ADMIN"); var platform=account("SUPER_ADMIN");
    http.perform(write(school).header("X-Tenant-Id",platform.principal().orgId())).andExpect(status().isForbidden());
    assertUnchanged(school);
    http.perform(get("/api/v1/auth/me").cookie(cookie(platform)).header("X-JKXT-Portal","platform")
        .header("X-Tenant-Id",school.principal().orgId())).andExpect(status().isOk())
        .andExpect(header().string("X-Tenant-Id",school.principal().orgId().toString()));
    jdbc.update("update organization set status='suspended' where id=?",school.principal().orgId());
    http.perform(get("/api/v1/auth/me").cookie(cookie(platform)).header("X-JKXT-Portal","platform")
        .header("X-Tenant-Id",school.principal().orgId())).andExpect(status().isNotFound());
  }
  @Test void invalidPermissionShapeReturns503WithoutBusinessMutation() throws Exception {
    var s=account("ADMIN");
    jdbc.update("update role set permissions='{\"invalid\":true}'::jsonb where org_id=?",s.principal().orgId());
    http.perform(write(s)).andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("AUTH_STATE_UNAVAILABLE"));
    assertUnchanged(s);
  }
  @Test void asyncAndErrorDispatchRevalidateAndCleanUpWhenDownstreamThrows() throws Exception {
    var s=account("ADMIN");
    var filter=new SessionAuthenticationFilter(sessions,guard,deploy,mapper,"http://localhost:8080");
    for(var type:List.of(jakarta.servlet.DispatcherType.ASYNC,jakarta.servlet.DispatcherType.ERROR)) {
      var req=new org.springframework.mock.web.MockHttpServletRequest("GET","/api/v1/auth/me");
      req.setDispatcherType(type); req.setCookies(cookie(s));
      var res=new org.springframework.mock.web.MockHttpServletResponse();
      assertThrows(jakarta.servlet.ServletException.class,()->filter.doFilter(req,res,(request,response)->{
        assertNotNull(request.getAttribute(AuthRequestAttributes.ENVELOPE));
        assertEquals(s.principal().orgId(),TenantContext.getRequired());
        throw new jakarta.servlet.ServletException("fixture downstream failure");
      }));
      assertNull(req.getAttribute(AuthRequestAttributes.ENVELOPE));
      assertNull(req.getAttribute(AuthRequestAttributes.PRINCIPAL));
      assertTrue(TenantContext.get().isEmpty());
      assertNull(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication());
    }
  }
  @Test void replacementOnlyRevokesSelectedOldSession() {
    var a=account("ADMIN"); var device=sessions.create(a.principal(),PortalPurpose.SCHOOL,a.envelope().authVersion());
    ids.add(device.principal().sessionId());
    var b=account("ADMIN");
    var next=sessions.replace(b.principal(),PortalPurpose.SCHOOL,b.envelope().authVersion(),a.principal().sessionId());
    ids.add(next.principal().sessionId());
    assertTrue(sessions.find(a.principal().sessionId(),PortalPurpose.SCHOOL).isEmpty());
    assertTrue(sessions.find(device.principal().sessionId(),PortalPurpose.SCHOOL).isPresent());
    assertTrue(sessions.find(next.principal().sessionId(),PortalPurpose.SCHOOL).isPresent());
    assertNotEquals(next.envelope().context(),b.envelope().context());
  }
  @Test void revokeSnapshotPreservesConcurrentNewSessionIndex() {
    var old=account("ADMIN");
    var template=org.mockito.Mockito.spy(redis);
    var setOps=org.mockito.Mockito.spy(redis.opsForSet());
    org.mockito.Mockito.doReturn(setOps).when(template).opsForSet();
    String index="jkxt:user-sessions:v2:"+old.principal().userId();
    var created=new java.util.concurrent.atomic.AtomicReference<SessionService.CreatedSession>();
    org.mockito.Mockito.doAnswer(invocation -> {
      Set<String> snapshot=redis.opsForSet().members(index);
      var fresh=sessions.create(old.principal(),old.envelope().audience(),old.envelope().authVersion());
      ids.add(fresh.principal().sessionId()); created.set(fresh); return snapshot;
    }).when(setOps).members(index);
    new SessionService(template,mapper,null,jdbc,transactionManager,false,"Lax",600).revokeOtherSessions(old.principal().userId(),null);
    assertTrue(sessions.find(old.principal().sessionId(),PortalPurpose.SCHOOL).isEmpty());
    assertTrue(sessions.find(created.get().principal().sessionId(),PortalPurpose.SCHOOL).isPresent());
    assertTrue(Boolean.TRUE.equals(redis.opsForSet().isMember(index,key(created.get()))));
    sessions.revokeOtherSessions(old.principal().userId(),null);
    assertTrue(sessions.find(created.get().principal().sessionId(),PortalPurpose.SCHOOL).isEmpty());
  }
  @Test void productionCookiesHaveHostOnlyAttributes() {
    var secure=new SessionService(redis,mapper,null,jdbc,transactionManager,true,"Lax",600);
    for(var p:PortalPurpose.values()) {
      var cookie=secure.sessionCookie(UUID.randomUUID().toString(),p);
      assertTrue(cookie.getName().startsWith("__Host-JKXT_")); assertTrue(cookie.isSecure());
      assertTrue(cookie.isHttpOnly()); assertEquals("/",cookie.getPath()); assertNull(cookie.getDomain());
    }
  }
  @Test void requestStateIsClearedAfterSuccessAndRejection() throws Exception {
    var s=account("ADMIN");
    var success=http.perform(get("/api/v1/auth/me").cookie(cookie(s))).andExpect(status().isOk()).andReturn();
    assertNull(success.getRequest().getAttribute(AuthRequestAttributes.PRINCIPAL));
    assertNull(success.getRequest().getAttribute(AuthRequestAttributes.ENVELOPE));
    assertTrue(TenantContext.get().isEmpty());
    http.perform(write(s).header("X-Tenant-Id",UUID.randomUUID())).andExpect(status().isForbidden());
    assertTrue(TenantContext.get().isEmpty());
    assertNull(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication());
  }
}

@SpringBootTest(properties="jkxt.deploy.mode=saas")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
abstract class PortalSessionFixture {
  @Autowired MockMvc http;
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired ObjectMapper mapper;
  @Autowired SessionService sessions;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
  @Autowired SessionGuard guard;
  @Autowired DeployProperties deploy;
  final List<String> ids=new ArrayList<>();
  @AfterEach void cleanup() { deploy.setMode("saas"); ids.forEach(sessions::revoke); }
  SessionService.CreatedSession account(String role) {
    UUID org=UUID.randomUUID(),user=UUID.randomUUID();
    jdbc.update("insert into organization(id,name,slug) values (?,?,?)",org,"Portal fixture","portal-"+org);
    jdbc.update("insert into role(org_id,code,name,permissions,system_role) values (?,?,?,'[\"tenant:read\",\"masterdata:manage\"]'::jsonb,true)",org,role,role);
    jdbc.update("insert into app_user(id,org_id,username,display_name,password_hash,role,preferences) values (?,?,?,?,?,?,?::jsonb)",
        user,org,"portal-"+user,"Portal fixture","not-a-login-hash",role,"{}");
    long version=jdbc.queryForObject("select auth_version from app_user where id=?",Long.class,user);
    var s=sessions.create(new SessionPrincipal("",user,org,"portal-"+user,"Fixture",role,List.of("tenant:read")),
        role.equals("SUPER_ADMIN")?PortalPurpose.PLATFORM:PortalPurpose.SCHOOL,version);
    ids.add(s.principal().sessionId()); return s;
  }
  Cookie cookie(SessionService.CreatedSession s) { return new Cookie(s.cookie().getName(),s.cookie().getValue()); }
  String key(SessionService.CreatedSession s) { return "jkxt:session:"+s.envelope().audience().name().toLowerCase(Locale.ROOT)+":"+s.principal().sessionId(); }
  org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder write(SessionService.CreatedSession s) {
    return patch("/api/v1/auth/me/preferences").cookie(cookie(s)).contentType("application/json")
        .header("X-JKXT-Portal",s.envelope().audience().name().toLowerCase(Locale.ROOT))
        .header("X-JKXT-Session-Context",s.envelope().context()).header("X-JKXT-CSRF",s.envelope().csrfToken())
        .content("{\"theme\":{\"mode\":\"dark\"}}");
  }
  void assertUnchanged(SessionService.CreatedSession s) {
    assertEquals("{}",jdbc.queryForObject("select preferences::text from app_user where id=?",String.class,s.principal().userId()));
  }
}
