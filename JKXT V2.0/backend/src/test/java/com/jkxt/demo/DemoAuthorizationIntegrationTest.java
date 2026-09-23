package com.jkxt.demo;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.time.*;
import java.util.concurrent.*;
import jakarta.servlet.DispatcherType;
import com.jkxt.security.auth.AuthException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"jkxt.auth.captcha-debug=false","jkxt.notify.email.enabled=false","jkxt.notify.sms.enabled=false"})
@AutoConfigureMockMvc(printOnlyOnFailure=false,print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@ActiveProfiles("test") @Import(DemoTestFixture.Time.class)
class DemoAuthorizationIntegrationTest extends DemoTestFixture.IntegrationBase {
  @Autowired MockMvc http;
  @Autowired ObjectMapper mapper;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @Autowired DemoTestFixture.MutableClock clock;
  @Autowired DemoWorkspaceRepository repository;
  @Autowired DemoQuotaService quotas;
  @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
  @AfterEach void retire(){DemoTestFixture.retire(jdbc);}

  @Test void allowedTeachersReadUsesOwnTenantAndFixedCapabilities() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();
    var result=http.perform(f.apply(get("/api/v1/teachers"),b)).andReturn().getResponse();
    assertThat(result.getStatus()).isEqualTo(200);
    assertThat(mapper.readTree(result.getContentAsString()).size()).isEqualTo(120);
    var me=http.perform(f.apply(get("/api/v1/auth/me"),b)).andReturn().getResponse();
    assertThat(mapper.readTree(me.getContentAsString()).path("permissions").toString())
        .contains("tenant:read","masterdata:manage","exam:manage","schedule:manage").doesNotContain("platform:manage");
  }

  @Test void overviewUsesCurrentWorkspaceRows() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();
    var result=http.perform(f.apply(get("/api/v1/demo/overview"),b)).andReturn().getResponse();
    assertThat(result.getStatus()).isEqualTo(200);
    var json=mapper.readTree(result.getContentAsString());
    assertThat(json.path("teacherCount").asLong()).isEqualTo(120);
    assertThat(json.path("examCount").asLong()).isEqualTo(jdbc.queryForObject("select count(*) from exam where org_id=?",Long.class,b.org()));
    assertThat(json.path("recentTasks").size()).isBetween(1,5);
  }

  @Test void simulationStoresResultWithoutPublishingOrNotifying() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();
    UUID task=jdbc.queryForObject("select id from exam_task where org_id=? limit 1",UUID.class,b.org()),op=UUID.randomUUID();
    String before=jdbc.queryForObject("select status from exam_task where id=?",String.class,task);
    var result=http.perform(f.apply(post("/api/v1/demo/tasks/"+task+"/simulate-publish"),b).contentType("application/json")
        .content("{\"operationId\":\""+op+"\"}")).andReturn().getResponse();
    assertThat(result.getStatus()).isEqualTo(200);
    assertThat(mapper.readTree(result.getContentAsByteArray()).path("label").asText()).isEqualTo("模拟发布，未通知真实教师");
    assertThat(jdbc.queryForObject("select status from exam_task where id=?",String.class,task)).isEqualTo(before);
    assertThat(jdbc.queryForObject("select count(*) from notification where org_id=?",Integer.class,b.org())).isZero();
  }

  @Test void malformedBootstrapAttemptsShareRollingRequestBudget() throws Exception {
    var f=new DemoTestFixture(application);String ip=f.clientIp(f.visitor());
    // The successful visitor call has already consumed one of the shared 60 attempts.
    quotaDiagnostic("bootstrap",ip);
    for(int i=0;i<59;i++) {
      var result=http.perform(post("/api/v1/demo/"+(i%2==0?"visitor":"sessions")).with(r->{r.setRemoteAddr(ip);return r;})
          .header("Origin",DemoTestFixture.ORIGIN).contentType("application/json").content("{")).andReturn().getResponse();
      assertThat(result.getStatus()).isEqualTo(400);
    }
    quotaDiagnostic("bootstrap",ip);
    var denied=http.perform(post("/api/v1/demo/visitor").with(r->{r.setRemoteAddr(ip);return r;})
        .header("Origin",DemoTestFixture.ORIGIN).contentType("application/json").content("{")).andReturn().getResponse();
    assertThat(denied.getStatus()).isEqualTo(429);
    assertThat(denied.getHeader("Retry-After")).isNotBlank();
  }

  @Test void allMvcMappingsOutsideIndependentApprovedTableRemainDenied() {
    var mapping=application.getBean("requestMappingHandlerMapping",org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
    var policy=new DemoRoutePolicy();int excluded=0;
    for(var entry:mapping.getHandlerMethods().entrySet())for(String template:entry.getKey().getPatternValues()) {
      String path=template.replaceAll("\\{(?:id|taskId|jobId|runId)\\}",DemoRoutePolicyTest.ID).replace("{kind}","teachers");
      if(!path.startsWith("/api/v1/"))continue;
      for(var method:entry.getKey().getMethodsCondition().getMethods()) {
        boolean expected=DemoRoutePolicyTest.approved().getOrDefault(path,Set.of()).contains(method.name());
        assertThat(policy.allows(method.name(),path)).as(method+" "+template).isEqualTo(expected);
        if(!expected)excluded++;
      }
    }
    assertThat(excluded).isGreaterThan(20);
  }

  @Test void foreignMalformedAnonymousAndRealPublishRemainDenied() throws Exception {
    var f=new DemoTestFixture(application);var own=f.enter();var other=f.enter();
    String foreign=jdbc.queryForObject("select id::text from teacher where org_id=? limit 1",String.class,other.org());
    for(String path:List.of("/api/v1/teachers/"+foreign,"/api/v1/teachers/123","/api/v1/future-route","/api/v1/admin/accounts","/api/v1/auth/me/password","/api/v1/notifications"))
      assertThat(http.perform(f.apply(get(path),own)).andReturn().getResponse().getStatus()).as(path).isEqualTo(403);
    for(String path:List.of("/api/v1/tasks/"+UUID.randomUUID()+"/publish","/api/v1/nl-policy/parse","/api/v1/exam-plans"))
      assertThat(http.perform(f.apply(post(path),own).contentType("application/json").content("{}")).andReturn().getResponse().getStatus()).as(path).isEqualTo(403);
    for(String path:List.of("/api/v1/teachers","/api/v1/demo/overview"))assertThat(http.perform(get(path)).andReturn().getResponse().getStatus()).isEqualTo(401);
    assertThat(http.perform(f.apply(post("/api/v1/auth/me"),own)).andReturn().getResponse().getStatus()).isEqualTo(403);
    assertThat(http.perform(f.apply(get("/api/v1/auth/logout"),own)).andReturn().getResponse().getStatus()).isEqualTo(403);
  }

  @Test void fixedCapabilitiesCannotBeExpandedByRoleRowsAndSuspensionTakesEffect() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();var account=application.getBean(com.jkxt.security.auth.CurrentAccountAccess.class);
    var other=f.enter();
    jdbc.update("insert into role(org_id,code,name,permissions,system_role) values(?,'DEMO','Forged demo','[\"platform:manage\",\"security:manage\"]',true)",other.org());
    jdbc.update("insert into role(org_id,code,name,permissions) values(?,'DEMO','Local forged demo','[\"user:manage\"]')",b.org());
    assertThat(account.permissions(b.org(),"DEMO")).containsExactlyElementsOf(List.of("tenant:read","masterdata:manage","exam:manage","schedule:manage"));
    UUID user=repository.user(b.workspace(),b.generation());
    jdbc.update("update app_user set status='disabled' where id=?",user);
    assertThat(http.perform(f.apply(get("/api/v1/teachers"),b)).andReturn().getResponse().getStatus()).isEqualTo(401);
  }

  @Test void sampleKindAndFormatBypassesAreDeniedBeforeHandler() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();String task=task(b).toString();
    for(String sample:List.of("true","TRUE","1"))assertThat(http.perform(f.apply(post("/api/v1/exam-tasks/"+task+"/uploads"),b).param("kind","teachers").param("sample",sample)).andReturn().getResponse().getStatus()).isEqualTo(403);
    assertThat(http.perform(f.apply(post("/api/v1/import/validate"),b).param("kind","users")).andReturn().getResponse().getStatus()).isEqualTo(403);
    assertThat(http.perform(f.apply(post("/api/v1/exam-tasks/"+task+"/uploads"),b).param("kind","subjects")).andReturn().getResponse().getStatus()).isEqualTo(403);
    assertThat(http.perform(f.apply(get("/api/v1/exam-tasks/"+task+"/export"),b).param("format","csv")).andReturn().getResponse().getStatus()).isEqualTo(403);
    assertThat(http.perform(f.apply(post("/api/v1/import/validate"),b).param("kind","teachers","users")).andReturn().getResponse().getStatus()).isEqualTo(400);
  }

  @Test void simulationReplayIsImmutableAndBoundToLiveTaskGeneration() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();var other=f.enter();UUID task=task(b),op=UUID.randomUUID();
    var first=simulate(f,b,task,op);assertThat(first.getStatus()).isEqualTo(200);
    clock.advance(Duration.ofSeconds(1));
    assertThat(simulate(f,b,task,op).getContentAsString()).isEqualTo(first.getContentAsString());
    assertThat(simulate(f,other,task,op).getStatus()).isEqualTo(403);
    UUID newTask=UUID.randomUUID();jdbc.update("insert into exam_task(id,org_id,task_no,name) values(?,?,?,'Extra')",newTask,b.org(),"T"+newTask);
    assertThat(simulate(f,b,newTask,op).getStatus()).isEqualTo(409);
    assertThatThrownBy(()->jdbc.update("update demo_simulation set label='changed' where workspace_id=? and operation_id=?",b.workspace(),op)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(()->jdbc.update("delete from demo_simulation where workspace_id=? and operation_id=?",b.workspace(),op)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    var reset=f.reset(b,UUID.randomUUID());assertThat(reset.getResponse().getStatus()).isEqualTo(200);
    var current=f.browser(b.visitor(),b.operation(),reset,b.cookie());
    assertThat(simulate(f,current,task,op).getStatus()).isEqualTo(403);
    assertThat(simulate(f,current,task(current),op).getStatus()).isEqualTo(409);
    assertThat(jdbc.queryForObject("select count(*) from demo_simulation where workspace_id=?",Integer.class,b.workspace())).isEqualTo(1);
  }

  @Test void overviewIsBoundedAndEmptyMeansActualZero() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();var other=f.enter();
    for(int i=0;i<7;i++)jdbc.update("insert into exam_task(org_id,task_no,name) values(?,?,?)",b.org(),"extra"+i,"Extra"+i);
    var data=mapper.readTree(http.perform(f.apply(get("/api/v1/demo/overview"),b)).andReturn().getResponse().getContentAsString());
    assertThat(data.path("recentTasks").size()).isEqualTo(5);
    for(var item:data.path("recentTasks"))assertThat(jdbc.queryForObject("select org_id from exam_task where id=?",UUID.class,UUID.fromString(item.path("taskId").asText()))).isEqualTo(b.org());
    jdbc.update("delete from exam_task where org_id=?",b.org());
    jdbc.update("update school_class set homeroom_teacher_id=null where org_id=?",b.org());
    jdbc.update("delete from teacher where org_id=?",b.org());
    jdbc.update("update school_class set default_classroom_id=null where org_id=?",b.org());
    jdbc.update("delete from classroom where org_id=?",b.org());
    var empty=mapper.readTree(http.perform(f.apply(get("/api/v1/demo/overview"),b)).andReturn().getResponse().getContentAsString());
    assertThat(empty.path("teacherCount").asLong()).isZero();assertThat(empty.path("roomCount").asLong()).isZero();assertThat(empty.path("examCount").asLong()).isZero();assertThat(empty.path("recentTasks").size()).isZero();
    assertThat(jdbc.queryForObject("select count(*) from teacher where org_id=?",Integer.class,other.org())).isEqualTo(120);
  }

  @Test void simulationCapacitySixtyAllowsReplayButRejectsNewSixtyFirst() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();UUID task=task(b),firstOp=UUID.randomUUID();
    var first=simulate(f,b,task,firstOp);assertThat(first.getStatus()).isEqualTo(200);
    fillSimulations(f,b,task,59);
    assertThat(simulationCount(b)).isEqualTo(60);
    var rejected=simulate(f,b,task,UUID.randomUUID());assertThat(rejected.getStatus()).isEqualTo(429);
    var error=mapper.readTree(rejected.getContentAsByteArray());
    assertThat(error.path("code").asText()).isEqualTo("DEMO_SIMULATION_CAPACITY_EXCEEDED");
    assertThat(error.path("message").asText()).isEqualTo("演示工作区模拟发布记录已达60条上限，重置不会恢复额度");
    var replay=simulate(f,b,task,firstOp);assertThat(replay.getStatus()).isEqualTo(200);
    assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
    UUID secondTask=UUID.randomUUID();jdbc.update("insert into exam_task(id,org_id,task_no,name) values(?,?,?,'Other')",secondTask,b.org(),"T"+secondTask);
    assertThat(simulate(f,b,secondTask,firstOp).getStatus()).isEqualTo(409);
    assertThat(simulationCount(b)).isEqualTo(60);
  }

  @Test void simulationCapacityPersistsAcrossResetGenerations() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();UUID oldTask=task(b),oldOp=UUID.randomUUID();
    assertThat(simulate(f,b,oldTask,oldOp).getStatus()).isEqualTo(200);fillSimulations(f,b,oldTask,58);
    var reset=f.reset(b,UUID.randomUUID());assertThat(reset.getResponse().getStatus()).isEqualTo(200);
    var current=f.browser(b.visitor(),b.operation(),reset,b.cookie());UUID currentTask=task(current);
    assertThat(simulate(f,current,currentTask,oldOp).getStatus()).isEqualTo(409);
    assertThat(simulate(f,current,currentTask,UUID.randomUUID()).getStatus()).isEqualTo(200);
    assertThat(simulate(f,current,currentTask,UUID.randomUUID()).getStatus()).isEqualTo(429);
    assertThat(simulationCount(current)).isEqualTo(60);
    assertThat(jdbc.queryForObject("select count(*) from demo_simulation where workspace_id=? and generation=?",Integer.class,b.workspace(),b.generation())).isEqualTo(59);
    assertThat(jdbc.queryForObject("select count(*) from demo_simulation where workspace_id=? and generation=?",Integer.class,current.workspace(),current.generation())).isEqualTo(1);
  }

  @Test void simulationCapacitySerializesConcurrentLastSlot() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();UUID task=task(b);fillSimulations(f,b,task,59);
    var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(2);
    try(var pool=Executors.newFixedThreadPool(3)) {
      var owner=pool.submit(()->repository.transaction(()->{repository.workspace(b.workspace(),true);locked.countDown();
        try{assertThat(release.await(20,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new IllegalStateException(e);}return null;}));
      assertThat(locked.await(20,TimeUnit.SECONDS)).isTrue();
      var first=pool.submit(()->{started.countDown();return simulate(f,b,task,UUID.randomUUID()).getStatus();});
      var second=pool.submit(()->{started.countDown();return simulate(f,b,task,UUID.randomUUID()).getStatus();});
      assertThat(started.await(20,TimeUnit.SECONDS)).isTrue();DemoTestFixture.awaitBlockedQuery(jdbc,"demo_workspace");
      release.countDown();owner.get(30,TimeUnit.SECONDS);
      assertThat(List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,429);
      assertThat(simulationCount(b)).isEqualTo(60);
    }finally{release.countDown();}
  }

  @Test void simulationCapacityRollbackDoesNotConsumeSuccessfulRecord() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();UUID task=task(b),operation=UUID.randomUUID();fillSimulations(f,b,task,59);
    assertThatThrownBy(()->repository.transaction(()->{
      assertThat(simulateUnchecked(f,b,task,operation).getStatus()).isEqualTo(200);
      throw new IllegalStateException("forced caller rollback");
    })).isInstanceOf(IllegalStateException.class).hasMessage("forced caller rollback");
    assertThat(simulationCount(b)).isEqualTo(59);
    assertThat(simulate(f,b,task,operation).getStatus()).isEqualTo(200);
    assertThat(simulationCount(b)).isEqualTo(60);
    assertThat(simulate(f,b,task,UUID.randomUUID()).getStatus()).isEqualTo(429);
  }

  @Test void simulationCommitFenceRejectsExpiryWithoutSpendingSlot() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();UUID task=task(b),operation=UUID.randomUUID();var original=clock.instant();
    try {
      assertThatThrownBy(()->repository.transaction(()->{
        assertThat(simulateUnchecked(f,b,task,operation).getStatus()).isEqualTo(200);
        clock.set(repository.workspace(b.workspace(),false).expires());return null;
      })).isInstanceOf(AuthException.class);
      assertThat(simulationCount(b)).isZero();
    }finally{clock.set(original);}
    assertThat(simulate(f,b,task,operation).getStatus()).isEqualTo(200);assertThat(simulationCount(b)).isEqualTo(1);
  }

  private void fillSimulations(DemoTestFixture f,DemoTestFixture.Browser b,UUID task,int count)throws Exception {
    for(int i=0;i<count;i++)assertThat(simulate(f,b,task,UUID.randomUUID()).getStatus()).as("successful simulation "+i).isEqualTo(200);
  }
  private int simulationCount(DemoTestFixture.Browser b){return jdbc.queryForObject("select count(*) from demo_simulation where workspace_id=?",Integer.class,b.workspace());}
  private org.springframework.mock.web.MockHttpServletResponse simulateUnchecked(DemoTestFixture f,DemoTestFixture.Browser b,UUID task,UUID op) {
    try{return simulate(f,b,task,op);}catch(Exception failure){throw new IllegalStateException(failure);}
  }

  @Test void onlyExplicitForegroundBusinessRenewsIdleAndNeverAbsoluteExpiry() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();var before=repository.workspace(b.workspace(),false);var original=clock.instant();
    try {
      clock.advance(Duration.ofMinutes(5));
      assertThat(http.perform(f.apply(get("/api/v1/auth/me"),b).header(DemoRequestGuard.ACTIVITY,"foreground")).andReturn().getResponse().getStatus()).isEqualTo(200);
      assertThat(repository.workspace(b.workspace(),false).activity()).isEqualTo(before.activity());
      assertThat(http.perform(f.apply(get("/api/v1/teachers"),b)).andReturn().getResponse().getStatus()).isEqualTo(200);
      assertThat(repository.workspace(b.workspace(),false).activity()).isEqualTo(before.activity());
      assertThat(http.perform(f.apply(get("/api/v1/teachers"),b).header(DemoRequestGuard.ACTIVITY,"foreground")).andReturn().getResponse().getStatus()).isEqualTo(200);
      var after=repository.workspace(b.workspace(),false);assertThat(after.activity()).isEqualTo(repository.now());assertThat(after.expires()).isEqualTo(before.expires());
      clock.advance(Duration.ofSeconds(1));
      // Accepted foreground work may fail later DTO validation; the touch is not rolled back.
      assertThat(http.perform(f.apply(post("/api/v1/teachers"),b).header(DemoRequestGuard.ACTIVITY,"foreground").contentType("application/json").content("{}")).andReturn().getResponse().getStatus()).isEqualTo(400);
      assertThat(repository.workspace(b.workspace(),false).activity()).isEqualTo(repository.now());
    }finally{clock.set(original);}
  }

  @Test void staleAndExpiredCapturedLeaseCannotReviveWorkspace() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();var before=repository.workspace(b.workspace(),false);var lease=before.lease();var original=clock.instant();
    try {
      clock.set(before.idleExpiresAt());
      assertThatThrownBy(()->repository.touchActivity(lease)).isInstanceOf(AuthException.class);
      assertThat(repository.workspace(b.workspace(),false).activity()).isEqualTo(before.activity());
      clock.set(original);assertThat(f.reset(b,UUID.randomUUID()).getResponse().getStatus()).isEqualTo(200);
      assertThatThrownBy(()->repository.touchActivity(lease)).isInstanceOf(AuthException.class);
      assertThat(repository.workspace(b.workspace(),false).activity()).isEqualTo(before.activity());
    }finally{clock.set(original);}
  }

  @Test void rollingQuotaIsAtomicAndWindowBoundaryExpiresWithoutRefund() throws Exception {
    String key=UUID.randomUUID().toString();var original=clock.instant();
    try(var pool=Executors.newFixedThreadPool(12)) {
      var results=new ArrayList<Future<Boolean>>();var release=new CountDownLatch(1);
      for(int i=0;i<12;i++)results.add(pool.submit(()->{release.await();try{quotas.consumeAttempt("upload",key);return true;}catch(DemoQuotaService.RateExceeded e){return false;}}));
      release.countDown();int accepted=0;for(var r:results)if(r.get(15,TimeUnit.SECONDS))accepted++;
      assertThat(accepted).isEqualTo(10);
      assertThat(redis.getExpire(DemoQuotaService.rateKey("upload",key),TimeUnit.MILLISECONDS)).isBetween(1L,60000L);
      clock.advance(Duration.ofSeconds(59));
      assertThatThrownBy(()->quotas.consumeAttempt("upload",key)).isInstanceOfSatisfying(DemoQuotaService.RateExceeded.class,e->assertThat(e.retryAfter()).isEqualTo(1));
      clock.advance(Duration.ofSeconds(1));assertThatCode(()->quotas.consumeAttempt("upload",key)).doesNotThrowAnyException();
    }finally{clock.set(original);}
  }

  @Test void failedUploadAndSolveAttemptsStayChargedAcrossReset() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();UUID task=task(b);
    quotaDiagnostic("upload",b.workspace().toString());
    for(int i=0;i<10;i++)assertThat(http.perform(f.apply(post("/api/v1/exam-tasks/"+task+"/uploads"),b).param("kind","teachers").param("sample","true")).andReturn().getResponse().getStatus()).isEqualTo(403);
    var activity=repository.workspace(b.workspace(),false).activity();
    var limit=http.perform(f.apply(post("/api/v1/exam-tasks/"+task+"/uploads"),b).header(DemoRequestGuard.ACTIVITY,"foreground")).andReturn().getResponse();assertThat(limit.getStatus()).isEqualTo(429);
    assertThat(repository.workspace(b.workspace(),false).activity()).isEqualTo(activity);
    assertThat(limit.getHeader("Retry-After")).isEqualTo("60");
    for(int i=0;i<3;i++)assertThat(http.perform(f.apply(post("/api/v1/exam-tasks/"+UUID.randomUUID()+"/schedule-jobs"),b)).andReturn().getResponse().getStatus()).isEqualTo(403);
    assertThat(http.perform(f.apply(post("/api/v1/exam-tasks/"+task+"/schedule-jobs"),b)).andReturn().getResponse().getStatus()).isEqualTo(429);
    quotaDiagnostic("upload",b.workspace().toString());quotaDiagnostic("solve",b.workspace().toString());
    var reset=f.reset(b,UUID.randomUUID());assertThat(reset.getResponse().getStatus()).isEqualTo(200);var current=f.browser(b.visitor(),b.operation(),reset,b.cookie());
    quotaDiagnostic("upload",b.workspace().toString());quotaDiagnostic("solve",b.workspace().toString());
    assertThat(http.perform(f.apply(post("/api/v1/exam-tasks/"+task(current)+"/uploads"),current)).andReturn().getResponse().getStatus()).isEqualTo(429);
    assertThat(http.perform(f.apply(post("/api/v1/exam-tasks/"+task(current)+"/schedule-jobs"),current)).andReturn().getResponse().getStatus()).isEqualTo(429);
  }

  @Test void resetAttemptsUseActualVisitorAndFailedConfirmationStillCounts() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();
    for(int i=0;i<2;i++)assertThat(http.perform(f.apply(post("/api/v1/demo/workspace/reset"),b).contentType("application/json").content("{\"operationId\":\""+UUID.randomUUID()+"\",\"confirmed\":false}")).andReturn().getResponse().getStatus()).isEqualTo(400);
    assertThat(f.reset(b,UUID.randomUUID()).getResponse().getStatus()).isEqualTo(429);
    var other=f.enter();assertThat(f.reset(other,UUID.randomUUID()).getResponse().getStatus()).isEqualTo(200);
  }

  @Test void admissionIsFiveNewAttemptsPerIpAndRestoreDoesNotAllocateAgain() throws Exception {
    var f=new DemoTestFixture(application);String ip=f.clientIp(f.visitor());DemoTestFixture.Browser first=null;
    for(int i=0;i<5;i++){var visitor=f.visitor(ip);var op=UUID.randomUUID();var start=f.start(visitor,op,"START");assertThat(start.getResponse().getStatus()).isEqualTo(200);if(first==null)first=f.browser(visitor,op,start,null);}
    var rejected=f.start(f.visitor(ip),UUID.randomUUID(),"START");assertThat(rejected.getResponse().getStatus()).isEqualTo(429);assertThat(rejected.getResponse().getHeader("Retry-After")).isEqualTo("600");
    assertThat(f.start(first.visitor(),first.operation(),"RECOVER_INITIAL").getResponse().getStatus()).isEqualTo(200);
    var restore=http.perform(post("/api/v1/demo/sessions").with(r->{r.setRemoteAddr(ip);return r;}).header("Origin",DemoTestFixture.ORIGIN).header("X-JKXT-Startup-Context",first.visitor().context()).cookie(first.cookie(),first.visitor().cookie()).contentType("application/json").content("{\"intent\":\"RESTORE\"}")).andReturn().getResponse();
    assertThat(restore.getStatus()).isEqualTo(200);assertThat(restore.getHeaders("Set-Cookie")).isEmpty();
    assertThat(redis.opsForZSet().zCard(DemoQuotaService.rateKey("start",ip))).isEqualTo(5);
  }

  @Test void capacityCountsRealRowsAndUncommittedReservationRollsBackWrites() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();var lease=repository.workspace(b.workspace(),false).lease();
    assertThatThrownBy(()->quotas.reserve(lease,"teachers",1)).isInstanceOf(AuthException.class).hasMessageContaining("事务");
    assertThatThrownBy(()->repository.transaction(()->{quotas.reserve(lease,"teachers",31);return null;})).isInstanceOf(AuthException.class);
    assertThatCode(()->repository.transaction(()->{try(var r=quotas.reserve(lease,"teachers",30)){r.commit();}return null;})).doesNotThrowAnyException();
    UUID created=UUID.randomUUID();
    assertThatThrownBy(()->repository.transaction(()->{try(var r=quotas.reserve(lease,"tasks",1)){jdbc.update("insert into exam_task(id,org_id,task_no,name) values(?,?,?,'rolled back')",created,b.org(),"T"+created);}return null;})).isInstanceOf(AuthException.class);
    assertThat(jdbc.queryForObject("select count(*) from exam_task where id=?",Integer.class,created)).isZero();
    assertThatThrownBy(()->repository.transaction(()->{quotas.reserve(lease,"solve",1);return null;})).isInstanceOf(AuthException.class).hasMessageContaining("尚未接入");
  }

  @Test void encodedHttpPathsAndUnlistedPublicPathsDoNotWidenAuthenticatedDemo() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();
    for(String path:List.of("/api/v1/%74eachers","/api/v1/teachers%2f","/api/v1/teachers;scope=all","/api/v1/teachers/../subjects","/api/v1/teachers%252f")) {
      var response=http.perform(f.apply(get(java.net.URI.create(path)),b)).andReturn().getResponse();
      assertThat(response.getStatus()).as(path).isIn(400,403);
    }
    for(String path:List.of("/api/v1/auth/captcha","/api/v1/health","/api/v1/version"))assertThat(http.perform(f.apply(get(path),b)).andReturn().getResponse().getStatus()).isEqualTo(403);
  }

  @Test void trustedProxyResolutionCannotBeSpoofedByDirectClient() {
    var request=new org.springframework.mock.web.MockHttpServletRequest();request.setRemoteAddr("203.0.113.9");request.addHeader("X-Forwarded-For","198.51.100.1, 198.51.100.2");
    assertThat(new com.jkxt.security.auth.ClientIpResolver("").resolve(request)).isEqualTo("203.0.113.9");
    var trusted=new com.jkxt.security.auth.ClientIpResolver("10.0.0.0/24");
    assertThat(trusted.resolve(request)).isEqualTo("203.0.113.9");
    request.setRemoteAddr("10.0.0.2");assertThat(trusted.resolve(request)).isEqualTo("198.51.100.2");
    request.removeHeader("X-Forwarded-For");request.addHeader("X-Forwarded-For","203.0.113.77, 198.51.100.2, 10.0.0.3");
    assertThat(trusted.resolve(request)).isEqualTo("198.51.100.2");
  }

  @Test void dispatcherRedispatchDoesNotDoubleChargeOrRenewActivity() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();var lease=repository.workspace(b.workspace(),false).lease();var guard=application.getBean(DemoRequestGuard.class);
    var request=new org.springframework.mock.web.MockHttpServletRequest("POST","/api/v1/import/validate");var response=new org.springframework.mock.web.MockHttpServletResponse();
    request.addHeader(DemoRequestGuard.ACTIVITY,"foreground");
    guard.check(request,response,lease,"/api/v1/import/validate",false);
    var touched=repository.workspace(b.workspace(),false).activity();var original=clock.instant();
    try {
      clock.advance(Duration.ofSeconds(1));
      for(DispatcherType type:List.of(DispatcherType.ASYNC,DispatcherType.ERROR)){request.setDispatcherType(type);guard.check(request,response,lease,"/api/v1/import/validate",false);}
      assertThat(redis.opsForZSet().zCard(DemoQuotaService.rateKey("upload",b.workspace().toString()))).isEqualTo(1);
      assertThat(repository.workspace(b.workspace(),false).activity()).isEqualTo(touched);
      request=new org.springframework.mock.web.MockHttpServletRequest("POST","/api/v1/demo/visitor");String ip=f.clientIp(b.visitor());request.setRemoteAddr(ip);
      long before=redis.opsForZSet().zCard(DemoQuotaService.rateKey("bootstrap",ip));guard.bootstrap(request,response,"/api/v1/demo/visitor");
      for(DispatcherType type:List.of(DispatcherType.ASYNC,DispatcherType.ERROR)){request.setDispatcherType(type);guard.bootstrap(request,response,"/api/v1/demo/visitor");}
      assertThat(redis.opsForZSet().zCard(DemoQuotaService.rateKey("bootstrap",ip))).isEqualTo(before+1);
    }finally{clock.set(original);}
  }

  @Test void forgedForegroundHeaderOnLifecycleAndJobPollNeverTouchesActivity() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();UUID run=f.solve(b,f.smallTask(b));
    var before=repository.workspace(b.workspace(),false);var original=clock.instant();
    try {
      clock.advance(Duration.ofMinutes(1));
      var refresh=http.perform(post("/api/v1/demo/visitor").cookie(b.visitor().cookie()).header("Origin",DemoTestFixture.ORIGIN).header(DemoRequestGuard.ACTIVITY,"foreground").contentType("application/json").content("{}")).andReturn().getResponse();
      assertThat(refresh.getStatus()).isEqualTo(200);
      String startup=mapper.readTree(refresh.getContentAsByteArray()).path("startupContext").asText();
      var restored=http.perform(post("/api/v1/demo/sessions").cookie(b.cookie(),b.visitor().cookie()).header("Origin",DemoTestFixture.ORIGIN).header("X-JKXT-Startup-Context",startup).header(DemoRequestGuard.ACTIVITY,"foreground").contentType("application/json").content("{\"intent\":\"RESTORE\"}")).andReturn().getResponse();
      assertThat(restored.getStatus()).isEqualTo(200);
      assertThat(http.perform(f.apply(get("/api/v1/schedule-jobs/"+run),b).header(DemoRequestGuard.ACTIVITY,"foreground")).andReturn().getResponse().getStatus()).isEqualTo(200);
      var guard=application.getBean(DemoRequestGuard.class);
      for(String path:List.of("/api/v1/schedule-jobs/"+run+"/events","/api/v1/auth/me","/api/v1/auth/logout","/api/v1/demo/workspace/reset")) {
        String method=path.endsWith("logout")||path.endsWith("reset")?"POST":"GET";
        var request=new org.springframework.mock.web.MockHttpServletRequest(method,path);request.addHeader(DemoRequestGuard.ACTIVITY,"foreground");
        guard.check(request,new org.springframework.mock.web.MockHttpServletResponse(),before.lease(),path,method.equals("POST"));
      }
      assertThat(repository.workspace(b.workspace(),false).activity()).isEqualTo(before.activity());
      assertThat(repository.workspace(b.workspace(),false).expires()).isEqualTo(before.expires());
    }finally{clock.set(original);}
  }

  @Test void quotaRedisOutageIs503AndNeverInvokesHandler() throws Exception {
    int port;try(var socket=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))){port=socket.getLocalPort();}
    var config=new org.springframework.data.redis.connection.RedisStandaloneConfiguration("127.0.0.1",port);
    var settings=org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(250)).shutdownTimeout(Duration.ZERO).build();
    var factory=new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(config,settings);factory.afterPropertiesSet();factory.start();
    try {
      var disconnected=new org.springframework.data.redis.core.StringRedisTemplate(factory);
      var response=bootstrapWithQuota(new DemoQuotaService(disconnected,repository,application.getBean(jakarta.persistence.EntityManagerFactory.class)),"203.0.113.254");
      assertThat(response.getStatus()).isEqualTo(503);assertThat(response.getContentAsString()).contains("DEMO_QUOTA_UNAVAILABLE");
    }finally{factory.destroy();}
  }

  @Test void ambiguousRedisDebitFailsClosedAndRemainsCounted() throws Exception {
    var uncertain=new org.springframework.data.redis.core.StringRedisTemplate(redis.getConnectionFactory()){
      @Override public <T>T execute(org.springframework.data.redis.core.script.RedisScript<T> script,List<String> keys,Object...args){super.execute(script,keys,args);throw new org.springframework.data.redis.RedisSystemException("synthetic ambiguous reply",new java.io.IOException());}
    };
    var f=new DemoTestFixture(application);String ip=f.clientIp(f.visitor());long before=redis.opsForZSet().zCard(DemoQuotaService.rateKey("bootstrap",ip));
    assertThat(bootstrapWithQuota(new DemoQuotaService(uncertain,repository,application.getBean(jakarta.persistence.EntityManagerFactory.class)),ip).getStatus()).isEqualTo(503);
    assertThat(redis.opsForZSet().zCard(DemoQuotaService.rateKey("bootstrap",ip))).isEqualTo(before+1);
  }

  @Test void failedMinimalAllocationIsChargedAgainButCommittedSeedRetryIsNot() throws Exception {
    var f=new DemoTestFixture(application);var visitor=f.visitor();String ip=f.clientIp(visitor);var op=UUID.randomUUID();
    org.mockito.Mockito.doThrow(new IllegalStateException("synthetic allocation rollback")).when(seed).allocateIdentity(org.mockito.ArgumentMatchers.any());
    assertThat(f.start(visitor,op,"START").getResponse().getStatus()).isEqualTo(500);
    assertThat(jdbc.queryForObject("select count(*) from demo_workspace where visitor_id=?",Integer.class,visitor.id())).isZero();
    org.mockito.Mockito.doCallRealMethod().when(seed).allocateIdentity(org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.doThrow(new IllegalStateException("synthetic seed failure")).when(seed).initialize(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt());
    assertThat(f.start(visitor,op,"START").getResponse().getStatus()).isEqualTo(503);
    assertThat(redis.opsForZSet().zCard(DemoQuotaService.rateKey("start",ip))).isEqualTo(2);
    org.mockito.Mockito.doCallRealMethod().when(seed).initialize(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt());
    assertThat(f.start(visitor,op,"START").getResponse().getStatus()).isEqualTo(200);
    assertThat(redis.opsForZSet().zCard(DemoQuotaService.rateKey("start",ip))).isEqualTo(2);
  }

  @Test void concurrentSameVisitorStartDebitsOneActualAllocation()throws Exception {
    var f=new DemoTestFixture(application);var visitor=f.visitor();var op=UUID.randomUUID();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    org.mockito.Mockito.doAnswer(call->{entered.countDown();assertThat(release.await(20,TimeUnit.SECONDS)).isTrue();return call.callRealMethod();}).when(seed).initialize(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt());
    try(var executor=Executors.newFixedThreadPool(2)) {
      var first=executor.submit(()->f.start(visitor,op,"START"));assertThat(entered.await(20,TimeUnit.SECONDS)).isTrue();
      var second=executor.submit(()->f.start(visitor,op,"START"));DemoTestFixture.awaitBlockedQuery(jdbc,"demo_workspace");
      release.countDown();var statuses=List.of(first.get(30,TimeUnit.SECONDS).getResponse().getStatus(),second.get(30,TimeUnit.SECONDS).getResponse().getStatus());
      assertThat(statuses).containsExactlyInAnyOrder(200,409);
      assertThat(jdbc.queryForObject("select count(*) from demo_workspace where visitor_id=?",Integer.class,visitor.id())).isEqualTo(1);
      assertThat(redis.opsForZSet().zCard(DemoQuotaService.rateKey("start",f.clientIp(visitor)))).isEqualTo(1);
    }finally{release.countDown();}
  }

  @Test void initialDeliveryRechecksCookieIssuedAfterWorkspaceLock()throws Exception {
    var f=new DemoTestFixture(application);var visitor=f.visitor();var operation=UUID.randomUUID();
    var service=application.getBean(DemoWorkspaceService.class);
    var grant=service.start(visitor.id(),operation.toString(),DemoWorkspaceService.hash("START"),()->{},f.clientIp(visitor));
    assertThat(jdbc.queryForObject("select (result_json->>'cookieIssued')::boolean from demo_operation where operation_id=?",Boolean.class,operation)).isFalse();
    var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(2);
    try(var pool=Executors.newFixedThreadPool(3)) {
      var owner=pool.submit(()->repository.transaction(()->{repository.workspace(grant.lease().workspaceId(),true);locked.countDown();
        try{assertThat(release.await(20,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new IllegalStateException(e);}return null;}));
      assertThat(locked.await(20,TimeUnit.SECONDS)).isTrue();
      java.util.concurrent.Callable<Integer> deliver=()->{started.countDown();try {
        var delivered=service.initialDelivery(visitor.id(),operation.toString(),false,null);
        assertThat(delivered.envelope().principal().sessionId()).isEqualTo(grant.envelope().principal().sessionId());return 200;
      }catch(AuthException rejected){assertThat(rejected.getCode()).isEqualTo("SESSION_CONTEXT_CHANGED");return rejected.getStatus().value();}};
      var first=pool.submit(deliver);var second=pool.submit(deliver);assertThat(started.await(20,TimeUnit.SECONDS)).isTrue();
      // Both callers must be blocked AFTER their operation snapshot, not merely scheduled.
      org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(()->
          assertThat(jdbc.queryForObject("select count(*) from pg_stat_activity where pid<>pg_backend_pid() and datname=current_database() and usename=current_user and wait_event_type='Lock' and query like '%demo_workspace%'",Integer.class)).isGreaterThanOrEqualTo(2));
      release.countDown();owner.get(30,TimeUnit.SECONDS);
      assertThat(List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
      assertThat(jdbc.queryForObject("select (result_json->>'cookieIssued')::boolean from demo_operation where operation_id=?",Boolean.class,operation)).isTrue();
      assertThat(jdbc.queryForObject("select count(*) from demo_workspace where visitor_id=?",Integer.class,visitor.id())).isEqualTo(1);
      assertThat(redis.opsForZSet().zCard(DemoQuotaService.rateKey("start",f.clientIp(visitor)))).isEqualTo(1);
    }finally{release.countDown();}
  }

  @Test void initialDeliveryExpiryBeforeCommitRollsBackCookieIssued()throws Exception {
    var f=new DemoTestFixture(application);var visitor=f.visitor();var operation=UUID.randomUUID();
    var service=application.getBean(DemoWorkspaceService.class);
    var grant=service.start(visitor.id(),operation.toString(),DemoWorkspaceService.hash("START"),()->{},f.clientIp(visitor));
    var original=clock.instant();
    try {
      assertThatThrownBy(()->repository.transaction(()->{
        service.initialDelivery(visitor.id(),operation.toString(),false,null);
        clock.set(grant.lease().expiresAt());return null;
      })).isInstanceOfSatisfying(AuthException.class,error->{assertThat(error.getCode()).isEqualTo("DEMO_EXPIRED");assertThat(error.getStatus().value()).isEqualTo(401);});
      assertThat(jdbc.queryForObject("select (result_json->>'cookieIssued')::boolean from demo_operation where operation_id=?",Boolean.class,operation)).isFalse();
    }finally{clock.set(original);}
    assertThat(service.initialDelivery(visitor.id(),operation.toString(),false,null).envelope().principal().sessionId()).isEqualTo(grant.envelope().principal().sessionId());
    assertThat(jdbc.queryForObject("select (result_json->>'cookieIssued')::boolean from demo_operation where operation_id=?",Boolean.class,operation)).isTrue();
  }

  @Test void foregroundWaitingOnWorkspaceLockCannotRenewExpiredLease()throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();var before=repository.workspace(b.workspace(),false);var original=clock.instant();
    var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
    try(var executor=Executors.newFixedThreadPool(2)) {
      var blocker=executor.submit(()->repository.transaction(()->{repository.workspace(b.workspace(),true);locked.countDown();try{assertThat(release.await(20,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new IllegalStateException(e);}return null;}));
      assertThat(locked.await(20,TimeUnit.SECONDS)).isTrue();
      var request=executor.submit(()->http.perform(f.apply(get("/api/v1/teachers"),b).header(DemoRequestGuard.ACTIVITY,"foreground")).andReturn());
      DemoTestFixture.awaitBlockedQuery(jdbc,"demo_workspace");clock.set(before.idleExpiresAt());release.countDown();blocker.get(30,TimeUnit.SECONDS);
      assertThat(request.get(30,TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(401);
      assertThat(repository.workspace(b.workspace(),false).activity()).isEqualTo(before.activity());
      assertThat(repository.workspace(b.workspace(),false).expires()).isEqualTo(before.expires());
    }finally{release.countDown();clock.set(original);}
  }

  private org.springframework.mock.web.MockHttpServletResponse bootstrapWithQuota(DemoQuotaService quota,String ip)throws Exception {
    var filter=new com.jkxt.security.auth.SessionAuthenticationFilter(sessions,application.getBean(com.jkxt.security.auth.SessionGuard.class),application.getBean(com.jkxt.common.config.DeployProperties.class),mapper,DemoTestFixture.ORIGIN);
    filter.demoRequestResolver(new DemoRequestGuard(new DemoRoutePolicy(),quota,repository,new com.jkxt.security.auth.ClientIpResolver("")));
    var request=new org.springframework.mock.web.MockHttpServletRequest("POST","/api/v1/demo/visitor");request.setServletPath("/api/v1/demo/visitor");request.setRemoteAddr(ip);request.addHeader("Origin",DemoTestFixture.ORIGIN);request.setContentType("application/json");request.setContent("{}".getBytes());
    var response=new org.springframework.mock.web.MockHttpServletResponse();var reached=new java.util.concurrent.atomic.AtomicInteger();
    filter.doFilter(request,response,(req,res)->reached.incrementAndGet());assertThat(reached.get()).isZero();return response;
  }
  private void quotaDiagnostic(String operation,String key){System.out.printf("QUOTA_DIAGNOSTIC operation=%s logicalTime=%s physicalTime=%s monotonicNanos=%s cardinality=%s ttlMs=%s%n",operation,repository.now(),Instant.now(),System.nanoTime(),redis.opsForZSet().zCard(DemoQuotaService.rateKey(operation,key)),redis.getExpire(DemoQuotaService.rateKey(operation,key),TimeUnit.MILLISECONDS));}

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"teachers,teacher,150","classrooms,classroom,40","exams,exam,100","tasks,exam_task,10","classes,school_class,200","subjects,subject,50"})
  void dataCapacityHasExplicitPerResourceBoundary(String resource,String table,long maximum)throws Exception {
    var b=new DemoTestFixture(application).enter();var lease=repository.workspace(b.workspace(),false).lease();
    long rows=jdbc.queryForObject("select count(*) from "+table+" where org_id=?",Long.class,b.org());
    assertThatCode(()->repository.transaction(()->{try(var r=quotas.reserve(lease,resource,maximum-rows)){r.commit();}return null;})).doesNotThrowAnyException();
    assertThatThrownBy(()->repository.transaction(()->{quotas.reserve(lease,resource,maximum-rows+1);return null;})).isInstanceOf(AuthException.class);
  }

  @Test void capacityRechecksActualFlushAndExpiryBeforeCommit()throws Exception {
    var b=new DemoTestFixture(application).enter();var lease=repository.workspace(b.workspace(),false).lease();
    long before=jdbc.queryForObject("select count(*) from exam_task where org_id=?",Long.class,b.org());
    assertThatThrownBy(()->repository.transaction(()->{try(var r=quotas.reserve(lease,"tasks",1)){
      for(int i=0;i<10;i++)jdbc.update("insert into exam_task(org_id,task_no,name) values(?,?,?)",b.org(),"over-cap"+i,"Extra");r.commit();
    }return null;})).isInstanceOf(AuthException.class);
    assertThat(jdbc.queryForObject("select count(*) from exam_task where org_id=?",Long.class,b.org())).isEqualTo(before);
    var original=clock.instant();
    try {
      assertThatThrownBy(()->repository.transaction(()->{try(var r=quotas.reserve(lease,"tasks",1)){
        jdbc.update("insert into exam_task(org_id,task_no,name) values(?,'late-cap','Late')",b.org());r.commit();clock.advance(Duration.ofHours(2));
      }return null;})).isInstanceOf(AuthException.class);
      assertThat(jdbc.queryForObject("select count(*) from exam_task where org_id=?",Long.class,b.org())).isEqualTo(before);
    }finally{clock.set(original);}
  }

  @Test void capacityCannotCommitUnflushedJpaRows()throws Exception {
    var b=new DemoTestFixture(application).enter();var lease=repository.workspace(b.workspace(),false).lease();
    var tasks=application.getBean(com.jkxt.tasks.repo.ExamTaskRepository.class);var fence=application.getBean(WorkspaceFence.class);
    long before=jdbc.queryForObject("select count(*) from exam_task where org_id=?",Long.class,b.org());
    assertThatThrownBy(()->repository.transaction(()->fence.commit(lease,()->{
      try(var reservation=quotas.reserve(lease,"tasks",1)) {
        for(int i=0;i<10;i++){var task=new com.jkxt.tasks.domain.ExamTask();task.setOrgId(b.org());task.setTaskNo("pending-jpa-"+i);task.setName("Pending JPA row");tasks.save(task);}
        reservation.commit();
      }
      return null;
    }))).isInstanceOf(AuthException.class);
    assertThat(jdbc.queryForObject("select count(*) from exam_task where org_id=?",Long.class,b.org())).isEqualTo(before);
  }

  @Test void expiryDuringJpaFlushRollsBackRegardlessOfFenceCallbackOrder()throws Exception {
    var b=new DemoTestFixture(application).enter();var lease=repository.workspace(b.workspace(),false).lease();
    var tasks=application.getBean(com.jkxt.tasks.repo.ExamTaskRepository.class);var fence=application.getBean(WorkspaceFence.class);var original=clock.instant();
    var factory=application.getBean(jakarta.persistence.EntityManagerFactory.class).unwrap(org.hibernate.engine.spi.SessionFactoryImplementor.class);
    var listeners=factory.getServiceRegistry().getService(org.hibernate.event.service.spi.EventListenerRegistry.class);
    var prior=new ArrayList<org.hibernate.event.spi.PreInsertEventListener>();listeners.getEventListenerGroup(org.hibernate.event.spi.EventType.PRE_INSERT).listeners().forEach(prior::add);
    var flushed=new java.util.concurrent.atomic.AtomicBoolean();
    listeners.appendListeners(org.hibernate.event.spi.EventType.PRE_INSERT,(org.hibernate.event.spi.PreInsertEventListener)event->{
      if(event.getEntity() instanceof com.jkxt.tasks.domain.ExamTask task && b.org().equals(task.getOrgId())){flushed.set(true);clock.set(lease.expiresAt());}return false;
    });
    try {
      assertThatThrownBy(()->repository.transaction(()->fence.commit(lease,()->{
        try(var reservation=quotas.reserve(lease,"tasks",1)) {
          var task=new com.jkxt.tasks.domain.ExamTask();task.setOrgId(b.org());task.setTaskNo("expires-at-flush");task.setName("Late flush");tasks.save(task);reservation.commit();
        }return null;
      }))).isInstanceOf(AuthException.class);
      assertThat(flushed.get()).isTrue();
      assertThat(jdbc.queryForObject("select count(*) from exam_task where org_id=? and task_no='expires-at-flush'",Integer.class,b.org())).isZero();
    }finally{listeners.setListeners(org.hibernate.event.spi.EventType.PRE_INSERT,prior.toArray(org.hibernate.event.spi.PreInsertEventListener[]::new));clock.set(original);}
  }

  @Test void concurrentDataReservationsCannotOverbookLastTaskSlot()throws Exception {
    var b=new DemoTestFixture(application).enter();var lease=repository.workspace(b.workspace(),false).lease();
    for(int i=0;i<8;i++)jdbc.update("insert into exam_task(org_id,task_no,name) values(?,?,?)",b.org(),"near-cap"+i,"Existing");
    var held=new CountDownLatch(1);var release=new CountDownLatch(1);
    try(var executor=Executors.newFixedThreadPool(2)) {
      var first=executor.submit(()->repository.transaction(()->{try(var r=quotas.reserve(lease,"tasks",1)){
        jdbc.update("insert into exam_task(org_id,task_no,name) values(?,'last-slot','Last')",b.org());held.countDown();
        try{assertThat(release.await(20,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new IllegalStateException(e);}r.commit();
      }return null;}));
      assertThat(held.await(20,TimeUnit.SECONDS)).isTrue();
      var second=executor.submit(()->repository.transaction(()->{try(var r=quotas.reserve(lease,"tasks",1)){r.commit();}return null;}));
      DemoTestFixture.awaitBlockedQuery(jdbc,"demo_workspace");release.countDown();first.get(30,TimeUnit.SECONDS);
      assertThatThrownBy(()->second.get(30,TimeUnit.SECONDS)).hasCauseInstanceOf(AuthException.class);
      assertThat(jdbc.queryForObject("select count(*) from exam_task where org_id=?",Integer.class,b.org())).isEqualTo(10);
    }finally{release.countDown();}
  }

  private UUID task(DemoTestFixture.Browser b){return jdbc.queryForObject("select id from exam_task where org_id=? limit 1",UUID.class,b.org());}
  private org.springframework.mock.web.MockHttpServletResponse simulate(DemoTestFixture f,DemoTestFixture.Browser b,UUID task,UUID op)throws Exception{return http.perform(f.apply(post("/api/v1/demo/tasks/"+task+"/simulate-publish"),b).contentType("application/json").content("{\"operationId\":\""+op+"\"}")).andReturn().getResponse();}
}
