package com.jkxt.security;

import com.jkxt.security.auth.*;
import com.jkxt.org.repo.AcademicTermRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc(print=MockMvcPrint.NONE) @ActiveProfiles("test")
class PortalSessionRegistryIntegrationTest {
  @Autowired ApplicationContext context;
  @Autowired MockMvc http;
  @SpyBean JdbcTemplate jdbc;
  @SpyBean SessionService sessions;
  @SpyBean AcademicTermRepository terms;
  @SpyBean StringRedisTemplate redis;
  @SpyBean com.jkxt.security.repo.UserAccountRepository users;
  PortalHttpFixture f;
  @BeforeEach void setup() { f=new PortalHttpFixture(context); }
  @AfterEach void cleanup() { reset(sessions,terms,redis,jdbc,users); f.close(); }

  @Test void responsePreparationFailureNeverReachesRedisReplacement() throws Exception {
    var a=f.account("ADMIN"); var old=f.login(PortalPurpose.SCHOOL,a);
    clearInvocations(sessions);
    doThrow(new org.springframework.dao.DataAccessResourceFailureException("injected term read failure"))
        .when(terms).findByOrgIdAndCurrentTrue(any());
    http.perform(f.loginRequest(PortalPurpose.SCHOOL,a).cookie(old.cookie())).andExpect(status().isServiceUnavailable())
        .andExpect(header().doesNotExist("Set-Cookie"));
    verify(sessions,never()).replace(any(),any(),anyLong(),any());
    reset(terms); f.expectMe(old,200); assertEquals(1,f.sessionCount(a.id()));
    assertEquals(1,jdbc.queryForObject("select login_count from app_user where id=?",Integer.class,a.id()));
  }

  @Test void actualDeferredCommitFailureKeepsPriorSessionAndRemovesPreparedPayload() throws Exception {
    commitFailure(false);
  }

  @Test void redisPreparationFailureRollsBackRegistryAndSuccessBookkeeping() throws Exception {
    var a=f.account("ADMIN"); var old=f.login(PortalPurpose.SCHOOL,a);
    doAnswer(invocation -> {
      var script=(org.springframework.data.redis.core.script.RedisScript<?>)invocation.getArgument(0);
      if(script.getScriptAsString().contains("'SET'")) throw new org.springframework.data.redis.RedisConnectionFailureException("injected prepare outage");
      return invocation.callRealMethod();
    }).when(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),anyList(),any(Object[].class));
    http.perform(f.loginRequest(PortalPurpose.SCHOOL,a).cookie(old.cookie())).andExpect(status().isServiceUnavailable())
        .andExpect(header().doesNotExist("Set-Cookie"));
    f.expectMe(old,200); assertEquals(1,f.sessionCount(a.id()));
    assertEquals(1,jdbc.queryForObject("select count(*) from portal_session_registry where user_id=?",Integer.class,a.id()));
    assertEquals(1,jdbc.queryForObject("select login_count from app_user where id=?",Integer.class,a.id()));
    assertEquals(1,jdbc.queryForObject("select count(*) from audit_log where actor_id=? and action='AUTH_LOGIN_SUCCESS'",Integer.class,a.id()));
  }

  @Test void registryDatabaseFailureNeverFallsBackToRedisIdentity() throws Exception {
    var a=f.account("ADMIN"); var s=f.login(PortalPurpose.SCHOOL,a);
    doThrow(new org.springframework.dao.DataAccessResourceFailureException("injected registry database outage"))
        .when(jdbc).queryForObject(contains("from portal_session_registry r join"),eq(Boolean.class),any(Object[].class));
    http.perform(get("/api/v1/auth/me").cookie(s.cookie())).andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("AUTH_STATE_UNAVAILABLE")).andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test void transactionBeginFailureStillDeletesEveryObservedPayloadAndRethrows() throws Exception {
    var a=f.account("ADMIN"); var one=f.login(PortalPurpose.SCHOOL,a); var two=f.login(PortalPurpose.SCHOOL,a);
    var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);
    when(manager.getTransaction(any())).thenThrow(new org.springframework.transaction.CannotCreateTransactionException("injected begin outage"));
    var failing=new SessionService(redis,context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class),
        context.getBean(CurrentAccountAccess.class),jdbc,manager,false,"Lax",600);
    assertThrows(org.springframework.transaction.CannotCreateTransactionException.class,()->failing.revokeOtherSessions(a.id(),null));
    assertFalse(redis.hasKey("jkxt:session:school:"+one.cookie().getValue()));
    assertFalse(redis.hasKey("jkxt:session:school:"+two.cookie().getValue()));
    assertEquals(0L,redis.opsForSet().size("jkxt:user-sessions:v2:"+a.id()));
    f.expectMe(one,401); f.expectMe(two,401);
  }

  @Test void rollbackCleanupOutageLeavesOnlyAnUnauthorizedOrphanPayload() throws Exception {
    commitFailure(true);
  }

  private void commitFailure(boolean cleanupFails) throws Exception {
    var a=f.account("ADMIN"); var old=f.login(PortalPurpose.SCHOOL,a);
    var prepared=new AtomicReference<SessionService.CreatedSession>();
    doAnswer(invocation -> {
      var s=(SessionService.CreatedSession)invocation.callRealMethod(); prepared.set(s);
      // Actual PostgreSQL DEFERRABLE constraint failure occurs at transaction commit, after SET.
      jdbc.update("update portal_session_lineage set current_session_id=? where current_session_id=?",
          UUID.randomUUID(),UUID.fromString(s.principal().sessionId()));
      return s;
    }).when(sessions).replace(any(),eq(PortalPurpose.SCHOOL),anyLong(),eq(old.cookie().getValue()));
    if(cleanupFails) doAnswer(invocation -> {
      var script=(org.springframework.data.redis.core.script.RedisScript<?>)invocation.getArgument(0);
      if(script.getScriptAsString().contains("'DEL'")) throw new org.springframework.data.redis.RedisConnectionFailureException("injected cleanup outage");
      return invocation.callRealMethod();
    }).when(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),anyList(),any(Object[].class));
    http.perform(f.loginRequest(PortalPurpose.SCHOOL,a).cookie(old.cookie())).andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("AUTH_REAUTHENTICATION_REQUIRED")).andExpect(header().doesNotExist("Set-Cookie"));
    assertNotNull(prepared.get());
    String newId=prepared.get().principal().sessionId();
    assertEquals(cleanupFails,Boolean.TRUE.equals(redis.hasKey("jkxt:session:school:"+newId)));
    assertTrue(sessions.find(newId,PortalPurpose.SCHOOL).isEmpty());
    http.perform(get("/api/v1/auth/me").cookie(new jakarta.servlet.http.Cookie("JKXT_SCHOOL_SESSION",newId)))
        .andExpect(status().isUnauthorized());
    f.expectMe(old,200); assertEquals(1,f.sessionCount(a.id()));
    assertEquals(1,jdbc.queryForObject("select login_count from app_user where id=?",Integer.class,a.id()));
    assertEquals(1,jdbc.queryForObject("select count(*) from audit_log where actor_id=? and action='AUTH_LOGIN_SUCCESS'",Integer.class,a.id()));
    assertEquals(0,jdbc.queryForObject("select count(*) from portal_session_registry where id=?",Integer.class,UUID.fromString(newId)));
  }

  @Test void overlappingSamePredecessorLoginsRejectTheLateEarlierCookie() throws Exception {
    var origin=f.account("ADMIN"); var a=f.account("ADMIN"); var b=f.account("ADMIN");
    var old=f.login(PortalPurpose.SCHOOL,origin); var otherDevice=f.login(PortalPurpose.SCHOOL,origin);
    var platform=f.login(PortalPurpose.PLATFORM,f.superAdmin());
    var aCommitted=new CountDownLatch(1); var allowLateResponse=new CountDownLatch(1);
    doAnswer(invocation -> {
      var result=(SessionService.CreatedSession)invocation.callRealMethod();
      if(result.principal().userId().equals(a.id())) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCompletion(int status) {
          if(status==STATUS_COMMITTED) {
            aCommitted.countDown();
            try { if(!allowLateResponse.await(20,TimeUnit.SECONDS)) throw new AssertionError("Late response barrier timed out"); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
          }
        }
      });
      return result;
    }).when(sessions).replace(any(),eq(PortalPurpose.SCHOOL),anyLong(),eq(old.cookie().getValue()));
    var requestA=f.loginRequest(PortalPurpose.SCHOOL,a).cookie(old.cookie());
    var requestB=f.loginRequest(PortalPurpose.SCHOOL,b).cookie(old.cookie());
    var pool=Executors.newFixedThreadPool(2);
    try {
      var futureA=pool.submit(()->http.perform(requestA).andReturn());
      assertTrue(aCommitted.await(20,TimeUnit.SECONDS));
      var resultB=pool.submit(()->http.perform(requestB).andReturn()).get(20,TimeUnit.SECONDS);
      assertEquals(200,resultB.getResponse().getStatus());
      allowLateResponse.countDown();
      var resultA=futureA.get(20,TimeUnit.SECONDS); assertEquals(200,resultA.getResponse().getStatus());
      // Simulates receiving B then the earlier A's Set-Cookie. The late A cookie cannot recover A.
      f.expectMe(f.from(resultB,PortalPurpose.SCHOOL),200);
      f.expectMe(f.from(resultA,PortalPurpose.SCHOOL),401);
      f.expectMe(otherDevice,200); f.expectMe(platform,200); f.expectMe(old,401);
      assertEquals(0,f.sessionCount(a.id())); assertEquals(1,f.sessionCount(b.id()));
    } finally { allowLateResponse.countDown(); pool.shutdownNow(); }
  }

  @Test void uncommittedPreparedPayloadCannotAuthenticateFromAnotherRequest() throws Exception {
    var a=f.account("ADMIN"); var old=f.login(PortalPurpose.SCHOOL,a);
    doAnswer(invocation -> {
      var s=(SessionService.CreatedSession)invocation.callRealMethod();
      int uncommitted=CompletableFuture.supplyAsync(()-> {
        try { return http.perform(get("/api/v1/auth/me").cookie(new jakarta.servlet.http.Cookie("JKXT_SCHOOL_SESSION",s.principal().sessionId())))
            .andReturn().getResponse().getStatus(); }
        catch(Exception failure) { throw new CompletionException(failure); }
      }).get(15,TimeUnit.SECONDS);
      assertEquals(401,uncommitted);
      int previous=CompletableFuture.supplyAsync(()-> {
        try { return http.perform(get("/api/v1/auth/me").cookie(old.cookie())).andReturn().getResponse().getStatus(); }
        catch(Exception failure) { throw new CompletionException(failure); }
      }).get(15,TimeUnit.SECONDS);
      assertEquals(200,previous);
      return s;
    }).when(sessions).replace(any(),eq(PortalPurpose.SCHOOL),anyLong(),eq(old.cookie().getValue()));
    var result=http.perform(f.loginRequest(PortalPurpose.SCHOOL,a).cookie(old.cookie())).andExpect(status().isOk()).andReturn();
    f.expectMe(f.from(result,PortalPurpose.SCHOOL),200); f.expectMe(old,401);
  }

  @Test void committedSelectorRejectsOldPayloadEvenWhenPostCommitRedisCleanupFails() throws Exception {
    var a=f.account("ADMIN"); var old=f.login(PortalPurpose.SCHOOL,a);
    doAnswer(invocation -> {
      var script=(org.springframework.data.redis.core.script.RedisScript<?>)invocation.getArgument(0);
      if(script.getScriptAsString().contains("'DEL'")) throw new org.springframework.data.redis.RedisConnectionFailureException("injected cleanup outage");
      return invocation.callRealMethod();
    }).when(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),anyList(),any(Object[].class));
    var result=http.perform(f.loginRequest(PortalPurpose.SCHOOL,a).cookie(old.cookie())).andExpect(status().isOk()).andReturn();
    assertTrue(Boolean.TRUE.equals(redis.hasKey("jkxt:session:school:"+old.cookie().getValue())));
    f.expectMe(old,401); f.expectMe(f.from(result,PortalPurpose.SCHOOL),200);
    assertEquals(1,f.sessionCount(a.id()));
  }

  @Test void revokingHistoricalOwnerNeverRevokesCurrentOtherOwnerAndIndexesStayBounded() throws Exception {
    var a=f.account("ADMIN"); var b=f.account("ADMIN"); var old=f.login(PortalPurpose.SCHOOL,a);
    var platform=f.login(PortalPurpose.PLATFORM,f.superAdmin());
    var response=http.perform(f.loginRequest(PortalPurpose.SCHOOL,b).cookie(old.cookie())).andExpect(status().isOk()).andReturn();
    var current=f.from(response,PortalPurpose.SCHOOL);
    sessions.revokeOtherSessions(a.id(),null); sessions.revoke(old.cookie().getValue(),PortalPurpose.SCHOOL);
    f.expectMe(current,200); f.expectMe(platform,200);
    assertEquals(0L,redis.opsForSet().size("jkxt:user-sessions:v2:"+a.id()));
    for(int i=0;i<4;i++) {
      response=http.perform(f.loginRequest(PortalPurpose.SCHOOL,b).cookie(current.cookie())).andExpect(status().isOk()).andReturn();
      current=f.from(response,PortalPurpose.SCHOOL);
      assertEquals(1L,redis.opsForSet().size("jkxt:user-sessions:v2:"+b.id()));
    }
    http.perform(f.apply(post("/api/v1/auth/logout"),current)).andExpect(status().isOk());
    assertEquals(0L,redis.opsForSet().size("jkxt:user-sessions:v2:"+b.id()));
    f.expectMe(platform,200);
  }

  @Test void expiredAliasCleanupIsBoundedAndNeverDeletesAnActiveLineage() throws Exception {
    var a=f.account("ADMIN"); var expired=f.login(PortalPurpose.SCHOOL,a); var active=f.login(PortalPurpose.SCHOOL,a);
    UUID expiredId=UUID.fromString(expired.cookie().getValue());
    jdbc.update("update portal_session_registry set expires_at=now()-interval '1 minute' where id=?",expiredId);
    jdbc.update("update portal_session_lineage set expires_at=now()-interval '1 minute' where current_session_id=?",expiredId);
    String expiredKey="jkxt:session:school:"+expired.cookie().getValue();
    String index="jkxt:user-sessions:v2:"+a.id();
    redis.expire(expiredKey,java.time.Duration.ZERO);
    assertFalse(Boolean.TRUE.equals(redis.hasKey(expiredKey)));
    assertTrue(Boolean.TRUE.equals(redis.opsForSet().isMember(index,expiredKey)));
    f.login(PortalPurpose.SCHOOL,a);
    assertFalse(Boolean.TRUE.equals(redis.opsForSet().isMember(index,expiredKey)));
    assertEquals(0,jdbc.queryForObject("select count(*) from portal_session_registry where id=?",Integer.class,expiredId));
    assertTrue(sessions.find(expired.cookie().getValue(),PortalPurpose.SCHOOL).isEmpty()); f.expectMe(active,200);
  }

  @Test void persistentDatabaseOutageStillRemovesObservedSessionsThroughHealthyRedis() throws Exception {
    var a=f.account("ADMIN"); var first=f.login(PortalPurpose.SCHOOL,a); var second=f.login(PortalPurpose.SCHOOL,a);
    String original=jdbc.queryForObject("select password_hash from app_user where id=?",String.class,a.id());
    var unavailable=new java.util.concurrent.atomic.AtomicBoolean();
    doAnswer(invocation -> {
      if(unavailable.get()) throw new org.springframework.dao.DataAccessResourceFailureException("persistent database outage");
      return invocation.callRealMethod();
    }).when(jdbc).query(anyString(),any(org.springframework.jdbc.core.ResultSetExtractor.class),any(Object[].class));
    doAnswer(invocation -> {
      unavailable.set(true);
      throw new org.springframework.dao.DataAccessResourceFailureException("password database failure");
    }).when(users).flush();
    try {
      String body=context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class).writeValueAsString(
          Map.of("currentPassword",a.password(),"newPassword","RejectedUpdate!2026"));
      http.perform(f.apply(post("/api/v1/auth/me/change-password"),first).contentType("application/json").content(body))
          .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH_REAUTHENTICATION_REQUIRED"))
          .andExpect(header().doesNotExist("Set-Cookie"));
      assertFalse(Boolean.TRUE.equals(redis.hasKey("jkxt:session:school:"+first.cookie().getValue())));
      assertFalse(Boolean.TRUE.equals(redis.hasKey("jkxt:session:school:"+second.cookie().getValue())));
    } finally { unavailable.set(false); reset(jdbc,users); }
    assertEquals(original,jdbc.queryForObject("select password_hash from app_user where id=?",String.class,a.id()));
    f.expectMe(first,401); f.expectMe(second,401);
  }
}
