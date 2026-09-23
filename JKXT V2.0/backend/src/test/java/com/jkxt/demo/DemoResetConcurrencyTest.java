package com.jkxt.demo;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"jkxt.auth.captcha-debug=false","jkxt.notify.email.enabled=false","jkxt.notify.sms.enabled=false"})
@AutoConfigureMockMvc(printOnlyOnFailure=false,print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@ActiveProfiles("test") @Import(DemoTestFixture.Time.class)
class DemoResetConcurrencyTest extends DemoTestFixture.IntegrationBase {
  @Autowired MockMvc http;
  @Autowired ObjectMapper mapper;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @org.junit.jupiter.api.AfterEach void retire(){DemoTestFixture.retire(jdbc);}
  @Test void initializesAReadyWorkspaceBeforeIssuingSession() throws Exception {
    var fixture=new DemoTestFixture(http,mapper);
    var result=fixture.start(fixture.visitor(),UUID.randomUUID(),"START");
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("state").asText()).isEqualTo("READY");
  }
  @Test void resetChangesOrgAndContextButNeverCookieAndSameKeyIsIdempotent() throws Exception {
    var f=new DemoTestFixture(http,mapper);var before=f.enter();var op=UUID.randomUUID();
    var result=f.reset(before,op);assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
    var after=f.browser(before.visitor(),before.operation(),result,before.cookie());
    assertThat(after.workspace()).isEqualTo(before.workspace());assertThat(after.org()).isNotEqualTo(before.org());
    assertThat(after.generation()).isEqualTo(2);assertThat(after.context()).isNotEqualTo(before.context());
    assertThat(f.reset(before,op).getResponse().getStatus()).isEqualTo(409);
    var replay=f.reset(after,op);assertThat(replay.getResponse().getStatus()).isEqualTo(200);
    assertThat(mapper.readTree(replay.getResponse().getContentAsString()).path("demo").path("generation").asLong()).isEqualTo(2);
  }
  @Test void failedResetIsUnavailableAndExactHttpRetryKeepsTheStableCookie() throws Exception {
    var f=new DemoTestFixture(application);var before=f.enter();var operation=UUID.randomUUID();
    org.mockito.Mockito.doThrow(new IllegalStateException("seed unavailable")).when(seed).initialize(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(120));
    assertThat(f.reset(before,operation).getResponse().getStatus()).isEqualTo(503);
    var me=http.perform(f.apply(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me"),before)).andReturn();
    assertThat(me.getResponse().getStatus()).isEqualTo(409);assertThat(mapper.readTree(me.getResponse().getContentAsString()).path("code").asText()).isEqualTo("DEMO_RESETTING");
    assertThat(f.reset(before,UUID.randomUUID()).getResponse().getStatus()).isEqualTo(409);
    org.mockito.Mockito.doCallRealMethod().when(seed).initialize(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt());
    var limited=f.reset(before,operation);assertThat(limited.getResponse().getStatus()).isEqualTo(429);
    assertThat(limited.getResponse().getHeader("Retry-After")).isEqualTo("60");
    var clock=application.getBean(DemoTestFixture.MutableClock.class);var original=clock.instant();
    try {
      clock.advance(java.time.Duration.ofSeconds(61));
      var retry=f.reset(before,operation);assertThat(retry.getResponse().getStatus()).isEqualTo(200);assertThat(retry.getResponse().getHeaders("Set-Cookie")).isEmpty();
      assertThat(jdbc.queryForObject("select count(*) from demo_generation where workspace_id=?",Integer.class,before.workspace())).isEqualTo(2);
    }finally{clock.set(original);}
  }
  @Test void capturedWriteBeforeResetCommitsFirstAndExpiredWriteRollsBackAtOuterCommit() throws Exception {
    var f=new DemoTestFixture(application);var browser=f.enter();
    var repository=application.getBean(DemoWorkspaceRepository.class);var fence=application.getBean(WorkspaceFence.class);var lease=repository.workspace(browser.workspace(),false).lease();
    var clock=application.getBean(DemoTestFixture.MutableClock.class);
    var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
    try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var write=executor.submit(()->fence.commit(lease,()->{entered.countDown();try{assertThat(release.await(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new RuntimeException(e);}return jdbc.update("update teacher set name='受控提交' where org_id=? and teacher_no='T001'",browser.org());}));
      assertThat(entered.await(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var reset=executor.submit(()->f.reset(browser,UUID.randomUUID()));
      org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(()->
          assertThat(jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type='Lock' and query like '%demo_workspace%'",Integer.class)).isPositive());
      release.countDown();assertThat(write.get(30,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1);
      assertThat(reset.get(30,java.util.concurrent.TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
      assertThatThrownBy(()->fence.commit(lease,()->jdbc.update("update teacher set name='过时代次' where org_id=?",browser.org()))).isInstanceOf(com.jkxt.security.auth.AuthException.class);
      var current=repository.workspace(browser.workspace(),false).lease();String before=jdbc.queryForObject("select name from teacher where org_id=? and teacher_no='T001'",String.class,current.orgId());
      assertThatThrownBy(()->repository.transaction(()->{fence.commit(current,()->jdbc.update("update teacher set name='不应提交' where org_id=? and teacher_no='T001'",current.orgId()));clock.advance(java.time.Duration.ofHours(2));return null;})).isInstanceOf(com.jkxt.security.auth.AuthException.class);
      assertThat(jdbc.queryForObject("select name from teacher where org_id=? and teacher_no='T001'",String.class,current.orgId())).isEqualTo(before);
    }finally{release.countDown();clock.set(java.time.Instant.now());}
  }
  @Test void delayedOldUserRevocationAndLogoutCannotRevokePublishedGeneration() throws Exception {
    var f=new DemoTestFixture(application);var before=f.enter();var other=f.enter();String fingerprint=f.fingerprint(other.visitor().id());
    var captured=sessions.find(before.cookie().getValue(),com.jkxt.security.auth.PortalPurpose.DEMO).orElseThrow();
    var committed=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
    org.mockito.Mockito.doAnswer(call->{
      var value=call.callRealMethod();
      org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
        @Override public int getOrder(){return Integer.MIN_VALUE;}
        @Override public void afterCompletion(int status){if(status==STATUS_COMMITTED){committed.countDown();try{assertThat(release.await(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new RuntimeException(e);}}}
      });return value;
    }).when(sessions).prepareDemo(org.mockito.ArgumentMatchers.eq(before.workspace()),org.mockito.ArgumentMatchers.eq(2L),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(before.cookie().getValue()));
    var redis=application.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);
    try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var reset=executor.submit(()->f.reset(before,UUID.randomUUID()));
      assertThat(committed.await(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      assertThat(redis.opsForSet().members("jkxt:user-sessions:v2:"+captured.principal().userId())).contains("jkxt:session:demo:g:1:"+before.cookie().getValue());
      sessions.revokeOtherSessions(captured.principal().userId(),null);
      assertThatThrownBy(()->application.getBean(com.jkxt.security.auth.AuthService.class).logout(captured)).isInstanceOf(com.jkxt.security.auth.AuthException.class);
      assertThat(http.perform(f.apply(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me"),before)).andReturn().getResponse().getStatus()).isEqualTo(200);
      assertThat(f.fingerprint(other.visitor().id())).isEqualTo(fingerprint);
      release.countDown();assertThat(reset.get(30,java.util.concurrent.TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
    } finally{release.countDown();}
  }
  @Test void resetNeedsCsrfAndExplicitConfirmationAndRegistryRedisMismatchFailsClosed() throws Exception {
    var f=new DemoTestFixture(application);var browser=f.enter();
    var missing=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/demo/workspace/reset")
        .cookie(browser.cookie()).header("X-JKXT-Session-Context",browser.context()).contentType("application/json")
        .content("{\"operationId\":\""+UUID.randomUUID()+"\",\"confirmed\":true}")).andReturn();
    assertThat(missing.getResponse().getStatus()).isEqualTo(403);
    var unconfirmed=http.perform(f.apply(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/demo/workspace/reset"),browser)
        .contentType("application/json").content("{\"operationId\":\""+UUID.randomUUID()+"\",\"confirmed\":false}")).andReturn();
    assertThat(unconfirmed.getResponse().getStatus()).isEqualTo(400);
    var redis=application.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);String key="jkxt:session:demo:g:1:"+browser.cookie().getValue(),payload=redis.opsForValue().get(key);
    var wrong=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(payload);((com.fasterxml.jackson.databind.node.ObjectNode)wrong.path("envelope")).put("generation",2);
    try {
      redis.opsForValue().set(key,mapper.writeValueAsString(wrong),java.time.Duration.ofHours(2));
      assertThat(http.perform(f.apply(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me"),browser)).andReturn().getResponse().getStatus()).isEqualTo(401);
    }finally{redis.opsForValue().set(key,payload,java.time.Duration.ofHours(2));}
    assertThat(jdbc.queryForObject("select generation from demo_workspace where id=?",Long.class,browser.workspace())).isEqualTo(1);
  }
  @Test void logoutAuthenticatedBeforeResetCannotClearTheNewGenerationCookie() throws Exception {
    var f=new DemoTestFixture(application);var before=f.enter();
    var captured=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
    org.mockito.Mockito.doAnswer(call->{captured.countDown();assertThat(release.await(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();return call.callRealMethod();})
        .when(auth).logout(org.mockito.ArgumentMatchers.any());
    try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var logout=executor.submit(()->http.perform(f.apply(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/logout"),before)).andReturn());
      assertThat(captured.await(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      assertThat(f.reset(before,UUID.randomUUID()).getResponse().getStatus()).isEqualTo(200);
      release.countDown();var late=logout.get(30,java.util.concurrent.TimeUnit.SECONDS);
      assertThat(late.getResponse().getStatus()).isEqualTo(409);assertThat(late.getResponse().getHeaders("Set-Cookie")).isEmpty();
      assertThat(http.perform(f.apply(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me"),before)).andReturn().getResponse().getStatus()).isEqualTo(200);
    }finally{release.countDown();}
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"reset,missing","reset,other","reset,expired","logout,missing","logout,other","logout,expired"})
  void failedRecoveryRequiresTheActualLiveOwningVisitorCookie(String action,String credential) throws Exception {
    var f=new DemoTestFixture(application);var browser=f.enter();var other=f.enter();var operation=UUID.randomUUID();
    String fingerprint=f.fingerprint(other.visitor().id());
    org.mockito.Mockito.doThrow(new IllegalStateException("controlled seed failure")).when(seed).initialize(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt());
    assertThat(f.reset(browser,operation).getResponse().getStatus()).isEqualTo(503);
    org.mockito.Mockito.doCallRealMethod().when(seed).initialize(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt());
    var redis=application.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);
    String key="jkxt:demo:visitor:"+browser.visitor().cookie().getValue(),stored=redis.opsForValue().get(key);
    var request=f.applySession(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(action.equals("reset")?"/api/v1/demo/workspace/reset":"/api/v1/auth/logout"),browser);
    if(credential.equals("other"))request.cookie(other.visitor().cookie());
    if(credential.equals("expired")) {
      var expired=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(stored);
      expired.put("cookieExpiry",application.getBean(DemoTestFixture.MutableClock.class).instant().minusSeconds(1).toString());
      redis.opsForValue().set(key,mapper.writeValueAsString(expired),java.time.Duration.ofMinutes(130));request.cookie(browser.visitor().cookie());
    }
    if(action.equals("reset"))request.contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("operationId",operation,"confirmed",true)));
    try {
      var response=http.perform(request).andReturn();assertThat(response.getResponse().getStatus()).isEqualTo(403);
      assertThat(response.getResponse().getContentAsString()).contains("DEMO_OPERATION_FORBIDDEN");
      assertThat(response.getResponse().getHeaders("Set-Cookie")).isEmpty();
      assertThat(jdbc.queryForObject("select state from demo_workspace where id=?",String.class,browser.workspace())).isEqualTo("FAILED");
      assertThat(sessions.find(browser.cookie().getValue(),com.jkxt.security.auth.PortalPurpose.DEMO)).isPresent();
      assertThat(f.fingerprint(other.visitor().id())).isEqualTo(fingerprint);
    }finally{redis.opsForValue().set(key,stored,java.time.Duration.ofMinutes(130));}
  }
  @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"reset","logout"})
  void failedRecoveryUsesVisitorCookieAfterStartupProofExpiresWithoutRenewal(String action) throws Exception {
    var f=new DemoTestFixture(application);var browser=f.enter();var operation=UUID.randomUUID();
    var clock=application.getBean(DemoTestFixture.MutableClock.class);var original=clock.instant();
    var redis=application.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);
    String key="jkxt:demo:visitor:"+browser.visitor().cookie().getValue(),stored=redis.opsForValue().get(key);
    var activity=jdbc.queryForObject("select last_activity_at from demo_workspace where id=?",java.sql.Timestamp.class,browser.workspace());
    var absolute=jdbc.queryForObject("select expires_at from demo_workspace where id=?",java.sql.Timestamp.class,browser.workspace());
    org.mockito.Mockito.doThrow(new IllegalStateException("controlled seed failure")).when(seed).initialize(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt());
    assertThat(f.reset(browser,operation).getResponse().getStatus()).isEqualTo(503);
    org.mockito.Mockito.doCallRealMethod().when(seed).initialize(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt());
    try {
      clock.advance(java.time.Duration.ofMinutes(11));assertThat(clock.instant()).isAfter(browser.visitor().expiresAt());
      var response=action.equals("reset")?f.reset(browser,operation):http.perform(f.apply(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/logout"),browser)).andReturn();
      assertThat(response.getResponse().getStatus()).isEqualTo(200);
      assertThat(response.getResponse().getCookie("JKXT_DEMO_VISITOR")).isNull();
      if(action.equals("reset"))assertThat(response.getResponse().getHeaders("Set-Cookie")).isEmpty();
      else assertThat(response.getResponse().getCookie("JKXT_DEMO_SESSION").getMaxAge()).isZero();
      assertThat(redis.opsForValue().get(key)).isEqualTo(stored);
      assertThat(jdbc.queryForObject("select last_activity_at from demo_workspace where id=?",java.sql.Timestamp.class,browser.workspace())).isEqualTo(activity);
      assertThat(jdbc.queryForObject("select expires_at from demo_workspace where id=?",java.sql.Timestamp.class,browser.workspace())).isEqualTo(absolute);
    }finally{clock.set(original);}
  }
}
