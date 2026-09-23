package com.jkxt.demo;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class DemoWorkspaceIntegrationTest extends DemoTestFixture.IntegrationBase {
  @Autowired MockMvc http;
  @Autowired ObjectMapper mapper;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @org.junit.jupiter.api.AfterEach void retire(){DemoTestFixture.retire(jdbc);}
  @Test void visitorBootstrapCreatesOnlyShortLivedServerIdentity() throws Exception {
    var visitor=new DemoTestFixture(http,mapper).visitor();
    assertThat(visitor.cookie()).isNotNull();
    assertThat(visitor.cookie().isHttpOnly()).isTrue();
    assertThat(visitor.context()).isNotBlank();
  }
  @Test void distinctVisitorsHaveDistinctSynthetic120TeacherTenants() throws Exception {
    var fixture=new DemoTestFixture(http,mapper);var first=fixture.enter();var second=fixture.enter();
    assertThat(first.org()).isNotEqualTo(second.org());
    for(var browser:java.util.List.of(first,second)) {
      assertThat(jdbc.queryForObject("select count(*) from teacher where org_id=?",Integer.class,browser.org())).isEqualTo(120);
      assertThat(jdbc.queryForObject("select count(*) from teacher where org_id=? and is_homeroom",Integer.class,browser.org())).isPositive();
      assertThat(jdbc.queryForObject("select count(*) from teacher where org_id=? and jsonb_exists(qualifications,'CHIEF')",Integer.class,browser.org())).isPositive();
      assertThat(jdbc.queryForObject("select count(*) from exam where org_id=?",Integer.class,browser.org())).isPositive();
      assertThat(jdbc.queryForObject("select count(*) from exam e join exam_task t on t.id=e.exam_task_id where e.org_id=? and (e.exam_date<t.starts_on or e.exam_date>t.ends_on)",Integer.class,browser.org())).isZero();
    }
  }
  @Test void lostInitialResponseNeedsExplicitRecoveryAndCannotCrossVisitor() throws Exception {
    var f=new DemoTestFixture(http,mapper);var v=f.visitor();var operation=java.util.UUID.randomUUID();
    var lost=f.start(v,operation,"START");assertThat(lost.getResponse().getStatus()).isEqualTo(200);
    var replay=f.start(v,operation,"RECOVER_INITIAL");assertThat(replay.getResponse().getStatus()).isEqualTo(200);
    assertThat(replay.getResponse().getCookie("JKXT_DEMO_SESSION").getValue()).isEqualTo(lost.getResponse().getCookie("JKXT_DEMO_SESSION").getValue());
    assertThat(f.start(f.visitor(),operation,"RECOVER_INITIAL").getResponse().getStatus()).isEqualTo(403);
  }
  @Test void demoReadsOnlyOwnTeachersAndCannotPublishAdminOrForgeBootstrapIdentity() throws Exception {
    var f=new DemoTestFixture(http,mapper);var browser=f.enter();
    var teachers=http.perform(f.apply(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/teachers"),browser)).andReturn().getResponse();
    assertThat(teachers.getStatus()).isEqualTo(200);
    var actual=new java.util.ArrayList<String>();mapper.readTree(teachers.getContentAsByteArray()).forEach(row->actual.add(row.path("uuid").asText()));
    assertThat(actual).hasSize(120).containsExactlyInAnyOrderElementsOf(jdbc.queryForList("select id::text from teacher where org_id=?",String.class,browser.org()));
    assertThat(http.perform(f.apply(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/tasks/"+java.util.UUID.randomUUID()+"/publish"),browser)).andReturn().getResponse().getStatus()).isEqualTo(403);
    assertThat(http.perform(f.apply(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/accounts"),browser)).andReturn().getResponse().getStatus()).isEqualTo(403);
    var v=f.visitor();
    var forged=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/demo/sessions")
        .header("Origin",DemoTestFixture.ORIGIN).header("X-JKXT-Startup-Context",v.context()).cookie(v.cookie()).contentType("application/json")
        .content(mapper.writeValueAsString(java.util.Map.of("operationId",java.util.UUID.randomUUID(),"intent","START","visitorId",browser.visitor().id())))).andReturn();
    assertThat(forged.getResponse().getStatus()).isEqualTo(400);
  }
  @Test void initializationFailureKeepsOneRetryableIdentityAndNoPartialSeed() throws Exception {
    var f=new DemoTestFixture(http,mapper);var visitor=f.visitor();var operation=java.util.UUID.randomUUID();
    org.mockito.Mockito.doAnswer(call->{call.callRealMethod();throw new IllegalStateException("injected seed failure");})
        .when(seed).initialize(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(120));
    var failed=f.start(visitor,operation,"START");assertThat(failed.getResponse().getStatus()).isEqualTo(503);
    var orgId=jdbc.queryForObject("select org_id from demo_workspace where visitor_id=?",java.util.UUID.class,visitor.id());
    assertThat(jdbc.queryForObject("select count(*) from teacher where org_id=?",Integer.class,orgId)).isZero();
    assertThat(jdbc.queryForObject("select state from demo_workspace where visitor_id=?",String.class,visitor.id())).isEqualTo("FAILED");
    org.mockito.Mockito.doCallRealMethod().when(seed).initialize(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt());
    var success=f.start(visitor,operation,"START");assertThat(success.getResponse().getStatus()).isEqualTo(200);
    assertThat(mapper.readTree(success.getResponse().getContentAsString()).path("org").path("id").asText()).isEqualTo(orgId.toString());
    assertThat(jdbc.queryForObject("select count(*) from demo_workspace where visitor_id=?",Integer.class,visitor.id())).isEqualTo(1);
  }
  @Test void twoConcurrentStartsShareOneAllocationAndRejectAnotherVisitorsKey() throws Exception {
    var f=new DemoTestFixture(application);var visitor=f.visitor();var id=java.util.UUID.randomUUID();
    var workspace=application.getBean(DemoWorkspaceService.class);
    var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
    org.mockito.Mockito.doAnswer(call->{entered.countDown();assertThat(release.await(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();return call.callRealMethod();})
        .when(seed).initialize(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt());
    try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first=executor.submit(()->workspace.start(visitor.id(),id.toString(),DemoWorkspaceService.hash("START")));
      assertThat(entered.await(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var second=executor.submit(()->workspace.start(visitor.id(),id.toString(),DemoWorkspaceService.hash("START")));
      DemoTestFixture.awaitBlockedQuery(jdbc,"demo_workspace");
      assertThat(second.isDone()).isFalse();
      release.countDown();var a=first.get(30,java.util.concurrent.TimeUnit.SECONDS);var b=second.get(30,java.util.concurrent.TimeUnit.SECONDS);
      assertThat(a.lease()).isEqualTo(b.lease());
      assertThat(jdbc.queryForObject("select count(*) from demo_workspace where visitor_id=?",Integer.class,visitor.id())).isEqualTo(1);
      assertThatThrownBy(()->workspace.start(visitor.id(),id.toString(),"f".repeat(64))).isInstanceOf(com.jkxt.security.auth.AuthException.class);
      var other=f.visitor();assertThat(f.start(other,id,"START").getResponse().getStatus()).isEqualTo(403);
    } finally{release.countDown();}
  }
  @Test void twentyActiveWorkspacesRejectTwentyFirstAndChangedKeysCannotBypassQuota() throws Exception {
    var f=new DemoTestFixture(application);var list=new java.util.ArrayList<DemoTestFixture.Browser>();
    for(int i=0;i<20;i++)list.add(f.enter());
    var extra=f.start(f.visitor(),java.util.UUID.randomUUID(),"START");assertThat(extra.getResponse().getStatus()).isEqualTo(429);
    var existing=list.getFirst();
    var result=application.getBean(DemoWorkspaceService.class).start(existing.visitor().id(),java.util.UUID.randomUUID().toString(),DemoWorkspaceService.hash("START"));
    assertThat(result.lease().workspaceId()).isEqualTo(existing.workspace());
  }
  @Test void credentialLoginInDemoIsRejectedEvenForValidGeneratedAccountsAndCaptcha() throws Exception {
    var encoder=application.getBean(org.springframework.security.crypto.password.PasswordEncoder.class);
    var redis=application.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);
    for(String role:java.util.List.of("ADMIN","SUPER_ADMIN")) {
      var id=java.util.UUID.randomUUID();String username="mode-"+id,password="T!"+java.util.UUID.randomUUID();
      jdbc.update("insert into app_user(id,org_id,username,display_name,password_hash,role) values(?,'00000000-0000-0000-0000-000000000001',?,'Mode test',?,?)",id,username,encoder.encode(password),role);
      try {
        var captcha=application.getBean(com.jkxt.security.auth.CaptchaService.class).create();
        String answer=redis.opsForValue().get("jkxt:captcha:"+captcha.captchaId());
        var response=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/"+(role.equals("ADMIN")?"school":"platform")+"/login")
            .header("Origin",DemoTestFixture.ORIGIN).contentType("application/json")
            .content(mapper.writeValueAsString(java.util.Map.of("username",username,"password",password,"captchaId",captcha.captchaId(),"captcha",answer)))).andReturn();
        assertThat(response.getResponse().getStatus()).isEqualTo(403);assertThat(response.getResponse().getHeaders("Set-Cookie")).isEmpty();
        assertThat(jdbc.queryForObject("select login_count from app_user where id=?",Integer.class,id)).isZero();
      }finally{jdbc.update("delete from app_user where id=?",id);}
    }
  }
  @Test void revokedInitialDeliveryCanOnlyBeReplacedByExplicitIdempotentReissue() throws Exception {
    var f=new DemoTestFixture(application);var first=f.enter();
    var logout=http.perform(f.apply(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/logout"),first)).andReturn();
    assertThat(logout.getResponse().getStatus()).isEqualTo(200);
    assertThat(f.start(first.visitor(),first.operation(),"RECOVER_INITIAL").getResponse().getStatus()).isEqualTo(401);
    var op=java.util.UUID.randomUUID();var reissued=f.start(first.visitor(),op,"REISSUE");assertThat(reissued.getResponse().getStatus()).isEqualTo(200);
    var second=f.browser(first.visitor(),op,reissued,null);
    assertThat(second.cookie().getValue()).isNotEqualTo(first.cookie().getValue());assertThat(second.workspace()).isEqualTo(first.workspace());
    assertThat(f.start(first.visitor(),op,"REISSUE").getResponse().getCookie("JKXT_DEMO_SESSION").getValue()).isEqualTo(second.cookie().getValue());
    assertThat(f.start(first.visitor(),java.util.UUID.randomUUID(),"REISSUE").getResponse().getStatus()).isEqualTo(409);
    assertThat(f.start(first.visitor(),op,"START").getResponse().getStatus()).isEqualTo(409);
    assertThat(http.perform(f.apply(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me"),first)).andReturn().getResponse().getStatus()).isEqualTo(401);
  }
  @Test void restoreWithoutCookieCannotTurnAStartupProofIntoBusinessIdentity() throws Exception {
    var f=new DemoTestFixture(application);var first=f.enter();
    var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/demo/sessions").header("Origin",DemoTestFixture.ORIGIN)
        .header("X-JKXT-Startup-Context",first.visitor().context()).cookie(first.visitor().cookie()).contentType("application/json").content("{\"intent\":\"RESTORE\"}");
    assertThat(http.perform(request).andReturn().getResponse().getStatus()).isEqualTo(409);
    var restored=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/demo/sessions").header("Origin",DemoTestFixture.ORIGIN)
        .header("X-JKXT-Startup-Context",first.visitor().context()).cookie(first.visitor().cookie(),first.cookie()).contentType("application/json").content("{\"intent\":\"RESTORE\"}")).andReturn();
    assertThat(restored.getResponse().getStatus()).isEqualTo(200);assertThat(restored.getResponse().getHeaders("Set-Cookie")).isEmpty();
  }
  @Test void reissueDigestBindsServerVisitorWorkspaceGenerationAndIntent() throws Exception {
    var f=new DemoTestFixture(application);var browser=f.enter();var operation=java.util.UUID.randomUUID();
    sessions.revoke(browser.cookie().getValue(),com.jkxt.security.auth.PortalPurpose.DEMO);
    var result=f.start(browser.visitor(),operation,"REISSUE");assertThat(result.getResponse().getStatus()).isEqualTo(200);
    String canonical="REISSUE:"+browser.visitor().id()+":"+browser.workspace()+":1";
    String expected=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertThat(jdbc.queryForObject("select request_hash from demo_operation where operation_id=?",String.class,operation)).isEqualTo(expected);
    assertThatThrownBy(()->jdbc.update("update demo_operation set request_hash=? where operation_id=?","0".repeat(64),operation))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(jdbc.queryForObject("select request_hash from demo_operation where operation_id=?",String.class,operation)).isEqualTo(expected);
    assertThat(f.start(browser.visitor(),operation,"REISSUE").getResponse().getStatus()).isEqualTo(200);
    var mismatch=f.start(browser.visitor(),operation,"START");assertThat(mismatch.getResponse().getStatus()).isEqualTo(409);
    assertThat(mismatch.getResponse().getHeaders("Set-Cookie")).isEmpty();
    var current=f.browser(browser.visitor(),operation,result,null);
    assertThat(f.reset(current,java.util.UUID.randomUUID()).getResponse().getStatus()).isEqualTo(200);
    assertThat(f.start(browser.visitor(),operation,"REISSUE").getResponse().getStatus()).isEqualTo(409);
  }
  @Test void aRecoveredCookieDeliveredAfterRevocationNeverRevivesItsSession() throws Exception {
    var f=new DemoTestFixture(application);var first=f.enter();
    org.mockito.Mockito.doAnswer(call->{var cookie=call.callRealMethod();sessions.revoke(first.cookie().getValue(),com.jkxt.security.auth.PortalPurpose.DEMO);return cookie;})
        .when(sessions).demoCookie(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    var late=f.start(first.visitor(),first.operation(),"RECOVER_INITIAL");assertThat(late.getResponse().getStatus()).isEqualTo(200);
    assertThat(late.getResponse().getCookie("JKXT_DEMO_SESSION").getValue()).isEqualTo(first.cookie().getValue());
    assertThat(http.perform(f.apply(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me"),first)).andReturn().getResponse().getStatus()).isEqualTo(401);
  }
  @Test void competingReissuesAndReversedResponsesSelectOnlyOneNewIdentity() throws Exception {
    var f=new DemoTestFixture(application);var original=f.enter();sessions.revoke(original.cookie().getValue(),com.jkxt.security.auth.PortalPurpose.DEMO);
    var committed=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
    org.mockito.Mockito.doAnswer(call->{var e=call.callRealMethod();
      org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
        @Override public void afterCompletion(int status){if(status==STATUS_COMMITTED){committed.countDown();try{assertThat(release.await(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}catch(InterruptedException ex){throw new RuntimeException(ex);}}}
      });return e;
    }).when(sessions).prepareDemo(org.mockito.ArgumentMatchers.eq(original.workspace()),org.mockito.ArgumentMatchers.eq(1L),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.isNull());
    var operation=java.util.UUID.randomUUID();
    try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var late=executor.submit(()->f.start(original.visitor(),operation,"REISSUE"));
      assertThat(committed.await(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var early=f.start(original.visitor(),operation,"REISSUE");assertThat(early.getResponse().getStatus()).isEqualTo(200);
      assertThat(f.start(original.visitor(),java.util.UUID.randomUUID(),"REISSUE").getResponse().getStatus()).isEqualTo(409);
      release.countDown();var later=late.get(30,java.util.concurrent.TimeUnit.SECONDS);assertThat(later.getResponse().getStatus()).isEqualTo(200);
      assertThat(later.getResponse().getCookie("JKXT_DEMO_SESSION").getValue()).isEqualTo(early.getResponse().getCookie("JKXT_DEMO_SESSION").getValue());
      var selected=f.browser(original.visitor(),operation,later,null);
      assertThat(http.perform(f.apply(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me"),selected)).andReturn().getResponse().getStatus()).isEqualTo(200);
      assertThat(http.perform(f.apply(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me"),original)).andReturn().getResponse().getStatus()).isEqualTo(401);
    }finally{release.countDown();}
  }
}

@SpringBootTest(properties={"jkxt.deploy.mode=private"})
@AutoConfigureMockMvc(printOnlyOnFailure=false,print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@ActiveProfiles("test")
class DemoFormalEntryIntegrationTest {
  @Autowired MockMvc http;
  @Autowired org.springframework.context.ApplicationContext application;
  @Test void formalAuthenticatedMeOmitsDemoIdleDeadline() throws Exception {
    try(var f=new com.jkxt.security.PortalHttpFixture(application)) {
      var session=f.login(com.jkxt.security.auth.PortalPurpose.SCHOOL,f.account("ADMIN"));
      var result=http.perform(f.apply(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me"),session)).andReturn();
      assertThat(result.getResponse().getStatus()).isEqualTo(200);
      var json=application.getBean(ObjectMapper.class).readTree(result.getResponse().getContentAsString());
      assertThat(json.has("idleExpiresAt")).isFalse();assertThat(json.path("audience").asText()).isEqualTo("SCHOOL");
      assertThat(json.path("expiresAt").asText()).isNotBlank();assertThat(json.path("demo").path("enabled").asBoolean()).isFalse();
    }
  }
  @Test void formalInstanceRejectsBothDemoBootstrapRoutesWithoutAnyCookie() throws Exception {
    for(String route:java.util.List.of("visitor","sessions")) {
      var result=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/demo/"+route)
          .header("Origin",DemoTestFixture.ORIGIN).contentType("application/json")
          .content(route.equals("visitor")?"{}":"{\"operationId\":\""+java.util.UUID.randomUUID()+"\",\"intent\":\"START\"}")).andReturn();
      assertThat(result.getResponse().getStatus()).isEqualTo(403);
      assertThat(result.getResponse().getContentAsString()).contains("DEMO_DISABLED");
      assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
    }
  }
}
