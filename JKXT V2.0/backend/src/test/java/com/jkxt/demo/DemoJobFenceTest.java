package com.jkxt.demo;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import com.jkxt.scheduling.solver.*;

@SpringBootTest(properties={"jkxt.auth.captcha-debug=false","jkxt.notify.email.enabled=false","jkxt.notify.sms.enabled=false"})
@AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@ActiveProfiles("test") @Import(DemoTestFixture.Time.class)
class DemoJobFenceTest extends DemoTestFixture.IntegrationBase {
  @Autowired MockMvc http;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @SpyBean CpSatScheduleSolver solver;
  @Autowired DemoSolveJobService jobs;
  @SpyBean DemoWorkspaceRepository repository;
  @Autowired com.jkxt.scheduling.service.ScheduleJobService scheduling;
  @Autowired @org.springframework.beans.factory.annotation.Qualifier("demoScheduleExecutor") org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor;
  @AfterEach void retire(){DemoTestFixture.retire(jdbc);}

  @Test void secondAdmissionCannotOverlapAnAcknowledgedRunningWorker() throws Exception {
    var fixture=new DemoTestFixture(application);var browser=fixture.enter();
    UUID task=fixture.smallTask(browser), otherTask=fixture.smallTask(browser);
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var exited=new CountDownLatch(1);
    doAnswer(call->{entered.countDown();try {assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return call.callRealMethod();}finally{exited.countDown();}})
        .when(solver).solve(any(ScheduleFixture.class),any(SchedulePolicy.class),any(CpSatScheduleSolver.SolveControl.class));
    try {
      assertThat(http.perform(fixture.apply(post("/api/v1/exam-tasks/"+task+"/schedule-jobs"),browser)).andReturn().getResponse().getStatus()).isEqualTo(200);
      assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
      var second=http.perform(fixture.apply(post("/api/v1/exam-tasks/"+otherTask+"/schedule-jobs"),browser)).andReturn().getResponse();
      assertThat(second.getStatus()).isEqualTo(409);
      assertThat(jdbc.queryForObject("select count(*) from schedule_run where org_id=?",Integer.class,browser.org())).isEqualTo(1);
    } finally {release.countDown();assertThat(exited.await(10,TimeUnit.SECONDS)).isTrue();}
  }

  private DemoSolveJobService.OwnedJob owned(DemoTestFixture.Browser browser) {
    return jobs.outstanding().stream().filter(j->j.lease().workspaceId().equals(browser.workspace())).findFirst().orElseThrow();
  }

  @Test void simultaneousDifferentTaskAdmissionsHaveOneDurableWorkspaceWinner() throws Exception {
    var fixture=new DemoTestFixture(application);var browser=fixture.enter();UUID first=fixture.smallTask(browser),second=fixture.smallTask(browser);
    var ready=new CountDownLatch(2);var go=new CountDownLatch(1);var nativeEntered=new CountDownLatch(1);var release=new CountDownLatch(1);
    doAnswer(call->{nativeEntered.countDown();assertThat(release.await(15,TimeUnit.SECONDS)).isTrue();return call.callRealMethod();})
        .when(solver).solve(any(ScheduleFixture.class),any(SchedulePolicy.class),any(CpSatScheduleSolver.SolveControl.class));
    var pool=Executors.newFixedThreadPool(2);DemoSolveJobService.OwnedJob winner=null;
    try {
      var left=pool.submit(()->{ready.countDown();assertThat(go.await(5,TimeUnit.SECONDS)).isTrue();return start(fixture,browser,first);});
      var right=pool.submit(()->{ready.countDown();assertThat(go.await(5,TimeUnit.SECONDS)).isTrue();return start(fixture,browser,second);});
      assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();go.countDown();assertThat(java.util.List.of(left.get(10,TimeUnit.SECONDS),right.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
      assertThat(nativeEntered.await(5,TimeUnit.SECONDS)).isTrue();winner=owned(browser);
      assertThat(jdbc.queryForObject("select count(*) from schedule_run where org_id=?",Integer.class,browser.org())).isEqualTo(1);
      assertThat(jdbc.queryForObject("select count(*) from demo_solve_job where workspace_id=? and released_at is null",Integer.class,browser.workspace())).isEqualTo(1);
    }finally{go.countDown();release.countDown();if(winner!=null)awaitReleased(winner);pool.shutdownNow();}
  }
  private void awaitReleased(DemoSolveJobService.OwnedJob job) {
    org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(()->
        assertThat(jdbc.queryForObject("select released_at is not null from demo_solve_job where reservation_id=?",Boolean.class,job.reservationId())).isTrue());
  }
  private int start(DemoTestFixture fixture,DemoTestFixture.Browser browser,UUID task) throws Exception {
    return http.perform(fixture.apply(post("/api/v1/exam-tasks/"+task+"/schedule-jobs"),browser)).andReturn().getResponse().getStatus();
  }

  private DemoSolveJobService.OwnedJob admitWithoutSubmit(DemoTestFixture.Browser browser,UUID task) {
    return admitWithoutSubmit(browser,task,jobs);
  }
  private DemoSolveJobService.OwnedJob admitWithoutSubmit(DemoTestFixture.Browser browser,UUID task,DemoSolveJobService owner) {
    return admitWithoutSubmit(browser,task,owner,null);
  }
  private DemoSolveJobService.OwnedJob admitWithoutSubmit(DemoTestFixture.Browser browser,UUID task,DemoSolveJobService owner,SchedulePolicy override) {
    return owner.admit(repository.workspace(browser.workspace(),false).lease(),()->{
      Object prepared=org.springframework.test.util.ReflectionTestUtils.invokeMethod(scheduling,"prepareTaskRun",browser.org(),task,null,null);
      UUID run=org.springframework.test.util.ReflectionTestUtils.invokeMethod(prepared,"runId");
      ScheduleFixture input=org.springframework.test.util.ReflectionTestUtils.invokeMethod(prepared,"fixture");
      SchedulePolicy policy=org.springframework.test.util.ReflectionTestUtils.invokeMethod(prepared,"policy");
      return new DemoSolveJobService.Admission(run,task,input,override==null?policy:override);
    });
  }

  @Test void unknownBootRetainsOccupancyAndCannotBeAcknowledgedOrReapedByThisCoordinator() throws Exception {
    var fixture=new DemoTestFixture(application);var browser=fixture.enter();UUID task=fixture.smallTask(browser),other=fixture.smallTask(browser);
    var owner=new DemoSolveJobService(repository,application.getBean(WorkspaceFence.class),application.getBean(com.jkxt.common.config.DeployProperties.class),
        application.getBean(com.fasterxml.jackson.databind.ObjectMapper.class),application.getBean("demoSolveControlScheduler",ScheduledExecutorService.class));
    var job=admitWithoutSubmit(browser,task,owner);
    try {
      assertThat(jobs.sameBoot(job)).isFalse();scheduling.reconcileDemo();assertThat(jobs.state(job)).isEqualTo("QUEUED");
      assertThatThrownBy(()->jobs.workerExited(job)).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(()->jobs.remainingNanos(job)).isInstanceOf(com.jkxt.common.web.ApiException.class);
      var response=http.perform(fixture.apply(post("/api/v1/exam-tasks/"+other+"/schedule-jobs"),browser)).andReturn().getResponse();
      assertThat(response.getStatus()).isEqualTo(409);assertThat(response.getContentAsString()).contains("DEMO_SOLVE_OWNER_UNKNOWN");
      assertThat(jdbc.queryForObject("select released_at is null from demo_solve_job where reservation_id=?",Boolean.class,job.reservationId())).isTrue();
      verify(solver,never()).solve(any(ScheduleFixture.class),any(SchedulePolicy.class),any(CpSatScheduleSolver.SolveControl.class));
    }finally{owner.cancel(job,"TEST_OWNER_QUEUED_CANCEL");}
    // The actual admitting coordinator can prove queued cancellation; no process-death inference.
    assertThat(jobs.state(job)).isEqualTo("CANCELLED");awaitReleased(job);
  }

  @Test @Timeout(90) void cleanupWaitsForExactOwnedExitThenPurgesBusinessRowsButKeepsTombstone() throws Exception {
    var fixture=new DemoTestFixture(application);var browser=fixture.enter();var other=fixture.enter();String otherBefore=fixture.fingerprint(other.visitor().id());UUID task=fixture.smallTask(browser);
    String marker=fixture.markSolveInputs(browser,task);
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    doAnswer(call->{entered.countDown();release.await();return call.callRealMethod();})
        .when(solver).solve(any(ScheduleFixture.class),any(SchedulePolicy.class),any(CpSatScheduleSolver.SolveControl.class));
    DemoSolveJobService.OwnedJob job=null;
    try {
      assertThat(start(fixture,browser,task)).isEqualTo(200);assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();job=owned(browser);
      var before=jdbc.queryForMap("select * from demo_solve_job where reservation_id=?",job.reservationId());
      assertThat(before.get("fixture_json").toString()).contains("teachers","examRooms",marker+"-teacher",marker+"-exam","2035-01-02");assertThat(before.get("policy_json")).isNotNull();
      jdbc.update("update demo_workspace set state='EXPIRED' where id=?",browser.workspace());
      application.getBean(DemoCleanupService.class).sweep(repository.now());
      assertThat(jdbc.queryForObject("select count(*) from schedule_run where id=?",Integer.class,job.runId())).isEqualTo(1);
      assertThat(jdbc.queryForObject("select released_at is null from demo_solve_job where reservation_id=?",Boolean.class,job.reservationId())).isTrue();
      release.countDown();awaitReleased(job);
      UUID legacy=UUID.randomUUID();
      // A released pre-migration-shaped row is a cleanup fixture, not another native-exit claim.
      jdbc.update("""
          insert into demo_solve_job(reservation_id,workspace_id,generation,org_id,run_id,task_id,owner_boot_token,
          admitted_tick,admitted_at,deadline_at,lease_expires_at,fixture_json,policy_json,state,terminal_reason,released_at)
          select ?,workspace_id,generation,org_id,?,task_id,owner_boot_token,admitted_tick,admitted_at,deadline_at,
          lease_expires_at,?::jsonb,?::jsonb,'FAILED','LEGACY_RELEASED',released_at from demo_solve_job where reservation_id=?
          """,legacy,UUID.randomUUID(),before.get("fixture_json").toString(),before.get("policy_json").toString(),job.reservationId());
      var legacyBefore=jdbc.queryForMap("select * from demo_solve_job where reservation_id=?",legacy);
      application.getBean(DemoCleanupService.class).sweep(repository.now());
      assertThat(jdbc.queryForObject("select count(*) from schedule_run where id=?",Integer.class,job.runId())).isZero();
      assertThat(jdbc.queryForObject("select count(*) from demo_solve_job where reservation_id=? and released_at is not null",Integer.class,job.reservationId())).isEqualTo(1);
      assertThat(fixture.fingerprint(other.visitor().id())).isEqualTo(otherBefore);
      var after=jdbc.queryForMap("select * from demo_solve_job where reservation_id=?",job.reservationId());
      for(String key:java.util.List.of("reservation_id","workspace_id","generation","org_id","run_id","task_id","owner_boot_token","admitted_tick","admitted_at","deadline_at","lease_expires_at"))
        assertThat(after.get(key)).as("retained exact identity %s",key).isEqualTo(before.get(key));
      assertThat(after.get("fixture_json")).as("full roster and exam input must be removed").isNull();
      assertThat(after.get("policy_json")).as("policy input must be removed").isNull();
      assertThat(after.get("payload_scrubbed_at")).isNotNull();
      var legacyAfter=jdbc.queryForMap("select * from demo_solve_job where reservation_id=?",legacy);
      for(var entry:legacyBefore.entrySet())if(!java.util.Set.of("fixture_json","policy_json","payload_scrubbed_at").contains(entry.getKey()))
        assertThat(legacyAfter.get(entry.getKey())).as("legacy provenance %s",entry.getKey()).isEqualTo(entry.getValue());
      assertThat(legacyAfter.get("fixture_json")).isNull();assertThat(legacyAfter.get("policy_json")).isNull();assertThat(legacyAfter.get("payload_scrubbed_at")).isNotNull();
    }finally{release.countDown();if(job!=null)awaitReleased(job);}
  }

  @Test void delayedExitRecoveryRepairsCurrentBusinessBeforeReleasing() throws Exception {
    var fixture=new DemoTestFixture(application);var browser=fixture.enter();UUID task=fixture.smallTask(browser);
    var notices=jdbc.queryForList("select to_jsonb(n)::text from notification n where org_id=? order by id",String.class,browser.org());
    var unavailable=new java.util.concurrent.atomic.AtomicBoolean();var phases=java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
    var control=new java.util.concurrent.atomic.AtomicReference<CpSatScheduleSolver.SolveControl>();
    var first=new java.util.concurrent.atomic.AtomicBoolean(true);
    doAnswer(call->{
      var names=StackWalker.getInstance().walk(frames->frames.filter(f->f.getClassName().equals(DemoSolveJobService.class.getName())).map(StackWalker.StackFrame::getMethodName).toList());
      if(unavailable.get() && !names.isEmpty()){phases.addAll(names);throw new org.springframework.jdbc.CannotGetJdbcConnectionException("controlled owned lifecycle DB failure");}
      return call.callRealMethod();
    }).when(repository).transaction(any(java.util.function.Supplier.class));
    doAnswer(call->{control.set(call.getArgument(2));var result=call.callRealMethod();if(first.getAndSet(false))unavailable.set(true);return result;})
        .when(solver).solve(any(ScheduleFixture.class),any(SchedulePolicy.class),any(CpSatScheduleSolver.SolveControl.class));
    DemoSolveJobService.OwnedJob job=null;
    try {
      assertThat(start(fixture,browser,task)).isEqualTo(200);
      var outerExited=new CompletableFuture<Boolean>();executor.execute(()->outerExited.complete(true));assertThat(outerExited.get(15,TimeUnit.SECONDS)).isTrue();
      job=owned(browser);assertThat(control.get().nativeReturned()).isTrue();
      assertThat(phases).contains("nativeExited","write","cancel","acknowledgeExit");
      var pending=jdbc.queryForMap("select state,released_at,native_exited_at from demo_solve_job where reservation_id=?",job.reservationId());
      assertThat(pending.get("state")).isEqualTo("RUNNING");assertThat(pending.get("released_at")).isNull();assertThat(pending.get("native_exited_at")).isNull();
      assertThat(jdbc.queryForObject("select status from schedule_run where id=?",String.class,job.runId())).isEqualTo("running");
      assertThat(jdbc.queryForObject("select status from exam_task where id=?",String.class,task)).isEqualTo("scheduling");
      var logs=jdbc.queryForObject("select logs::text from schedule_run where id=?",String.class,job.runId());
      var buffered=bufferedProgress(job.runId());
      unavailable.set(false);jobs.retryExited();scheduling.reconcileDemo();awaitReleased(job);
      var response=http.perform(fixture.apply(get("/api/v1/schedule-jobs/"+job.runId()),browser)).andReturn().getResponse();assertThat(response.getStatus()).isEqualTo(200);
      var snapshot=fixture.mapper.readTree(response.getContentAsByteArray());
      assertThat(snapshot.path("status").asText()).isIn("failed","cancelled");assertThat(snapshot.path("errorCode").asText()).isNotBlank();
      assertThat(jdbc.queryForObject("select status from exam_task where id=?",String.class,task)).isEqualTo("failed");
      assertThat(jdbc.queryForObject("select native_exited_at from demo_solve_job where reservation_id=?",java.sql.Timestamp.class,job.reservationId())).isNotNull();
      assertThat(jdbc.queryForObject("select count(*) from schedule_assignment where schedule_run_id=?",Integer.class,job.runId())).isZero();
      assertThat(jdbc.queryForObject("select logs::text from schedule_run where id=?",String.class,job.runId())).isEqualTo(logs);
      assertThat(bufferedProgress(job.runId())).isEqualTo(buffered);
      assertThat(jdbc.queryForList("select to_jsonb(n)::text from notification n where org_id=? order by id",String.class,browser.org())).isEqualTo(notices);
      var released=jdbc.queryForObject("select to_jsonb(j)::text from demo_solve_job j where reservation_id=?",String.class,job.reservationId());
      jobs.retryExited();jobs.workerExited(job);assertThat(jdbc.queryForObject("select to_jsonb(j)::text from demo_solve_job j where reservation_id=?",String.class,job.reservationId())).isEqualTo(released);
      var retry=http.perform(fixture.apply(post("/api/v1/exam-tasks/"+task+"/schedule-jobs"),browser)).andReturn().getResponse();assertThat(retry.getStatus()).isEqualTo(200);
      UUID retried=UUID.fromString(fixture.mapper.readTree(retry.getContentAsByteArray()).path("jobId").asText());
      org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(()->assertThat(jdbc.queryForObject("select exists(select 1 from demo_solve_job where run_id=? and state='SUCCEEDED' and released_at is not null)",Boolean.class,retried)).isTrue());
      String success=jdbc.queryForObject("select to_jsonb(r)::text from schedule_run r where id=?",String.class,retried);
      jobs.retryExited();jobs.workerExited(job);assertThat(jdbc.queryForObject("select to_jsonb(r)::text from schedule_run r where id=?",String.class,retried)).isEqualTo(success);
    }finally{unavailable.set(false);jobs.retryExited();}
  }

  @SuppressWarnings("unchecked") private java.util.List<?> bufferedProgress(UUID run) {
    var sink=application.getBean(com.jkxt.scheduling.service.ScheduleProgressSink.class);
    Object lock=org.springframework.test.util.ReflectionTestUtils.getField(sink,"coordinationLock");
    synchronized(lock){
      var buffers=(java.util.Map<com.jkxt.scheduling.service.ScheduleStreamAuthorization.Owner,java.util.List<?>>)org.springframework.test.util.ReflectionTestUtils.getField(sink,"buffers");
      return buffers.entrySet().stream().filter(e->e.getKey().runId().equals(run)).flatMap(e->e.getValue().stream()).toList();
    }
  }

  private final class LifecycleFault {
    final java.util.concurrent.atomic.AtomicBoolean all=new java.util.concurrent.atomic.AtomicBoolean();
    final java.util.concurrent.atomic.AtomicBoolean ackOnly=new java.util.concurrent.atomic.AtomicBoolean();
    final java.util.concurrent.atomic.AtomicBoolean loseReply=new java.util.concurrent.atomic.AtomicBoolean();
    final java.util.concurrent.atomic.AtomicBoolean replyLost=new java.util.concurrent.atomic.AtomicBoolean();
    void install() {
      doAnswer(call->{
        var names=StackWalker.getInstance().walk(frames->frames.filter(f->f.getClassName().equals(DemoSolveJobService.class.getName())).map(StackWalker.StackFrame::getMethodName).toList());
        boolean ack=names.contains("acknowledgeExit");
        if((all.get()&&!names.isEmpty())||(ackOnly.get()&&ack))throw new org.springframework.jdbc.CannotGetJdbcConnectionException("controlled lifecycle persistence unavailable");
        Object result=call.callRealMethod();
        if(ack&&loseReply.compareAndSet(true,false)) {
          ackOnly.set(true);replyLost.set(true);
          throw new org.springframework.jdbc.CannotGetJdbcConnectionException("controlled reply loss after committed exit acknowledgement");
        }
        return result;
      }).when(repository).transaction(any(java.util.function.Supplier.class));
    }
    void restore(){all.set(false);ackOnly.set(false);loseReply.set(false);}
  }
  private void awaitOuterRunnable() throws Exception {
    var sentinel=new CompletableFuture<Boolean>();executor.execute(()->sentinel.complete(true));assertThat(sentinel.get(15,TimeUnit.SECONDS)).isTrue();
  }
  private boolean hasRetainedExit(DemoSolveJobService.OwnedJob job) {
    return ((java.util.Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(jobs,"exited")).containsKey(job.reservationId());
  }
  private String runRow(UUID run){return jdbc.queryForObject("select to_jsonb(r)::text from schedule_run r where id=?",String.class,run);}

  @Test @Timeout(60) void retainedExitAfterResetCannotRepairStaleBusinessOrTouchNewGeneration() throws Exception {
    var fixture=new DemoTestFixture(application);var browser=fixture.enter();var unrelated=fixture.enter();
    String otherBefore=fixture.fingerprint(unrelated.visitor().id());UUID task=fixture.smallTask(browser);
    var fault=new LifecycleFault();fault.install();var first=new java.util.concurrent.atomic.AtomicBoolean(true);
    var control=new java.util.concurrent.atomic.AtomicReference<CpSatScheduleSolver.SolveControl>();
    doAnswer(call->{control.set(call.getArgument(2));var result=call.callRealMethod();if(first.getAndSet(false)){fault.all.set(true);fault.ackOnly.set(true);}return result;})
        .when(solver).solve(any(ScheduleFixture.class),any(SchedulePolicy.class),any(CpSatScheduleSolver.SolveControl.class));
    try {
      assertThat(start(fixture,browser,task)).isEqualTo(200);awaitOuterRunnable();var old=owned(browser);
      assertThat(control.get().nativeReturned()).isTrue();assertThat(hasRetainedExit(old)).isTrue();assertThat(jobs.state(old)).isEqualTo("RUNNING");
      fault.all.set(false); // Keep only delayed acknowledgements unavailable while the real reset executes.
      var reset=fixture.reset(browser,UUID.randomUUID());assertThat(reset.getResponse().getStatus()).isEqualTo(200);
      var current=fixture.browser(browser.visitor(),browser.operation(),reset,browser.cookie());
      String oldBusiness=runRow(old.runId()),newGeneration=fixture.fingerprint(current.visitor().id());
      assertThat(jdbc.queryForObject("select released_at is null from demo_solve_job where reservation_id=?",Boolean.class,old.reservationId())).isTrue();
      fault.restore();jobs.retryExited();awaitReleased(old);
      assertThat(runRow(old.runId())).isEqualTo(oldBusiness);assertThat(fixture.fingerprint(current.visitor().id())).isEqualTo(newGeneration);
      assertThat(fixture.fingerprint(unrelated.visitor().id())).isEqualTo(otherBefore);assertThat(hasRetainedExit(old)).isFalse();
      assertThat(jdbc.queryForObject("select native_exited_at is not null and fixture_json is null and policy_json is null from demo_solve_job where reservation_id=?",Boolean.class,old.reservationId())).isTrue();
      assertThat(jdbc.queryForObject("select count(*) from schedule_assignment where schedule_run_id=?",Integer.class,old.runId())).isZero();
      assertThat(start(fixture,current,fixture.smallTask(current))).isEqualTo(200);awaitOuterRunnable();
      assertThat(jdbc.queryForObject("select count(*) from demo_solve_job where workspace_id=? and generation=? and state='SUCCEEDED' and released_at is not null",Integer.class,current.workspace(),current.generation())).isEqualTo(1);
    }finally{fault.restore();jobs.retryExited();}
  }

  @Test @Timeout(60) void committedSuccessReplyLossRetainsProofAndOldRetryCannotReleaseNewOwner() throws Exception {
    var fixture=new DemoTestFixture(application);var browser=fixture.enter();UUID task=fixture.smallTask(browser);
    var fault=new LifecycleFault();fault.loseReply.set(true);fault.install();
    var old=admitWithoutSubmit(browser,task);var before=jdbc.queryForMap("select * from demo_solve_job where reservation_id=?",old.reservationId());
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var holdNext=new java.util.concurrent.atomic.AtomicBoolean();
    var control=new java.util.concurrent.atomic.AtomicReference<CpSatScheduleSolver.SolveControl>();
    doAnswer(call->{if(holdNext.get()){control.set(call.getArgument(2));entered.countDown();release.await();}return call.callRealMethod();})
        .when(solver).solve(any(ScheduleFixture.class),any(SchedulePolicy.class),any(CpSatScheduleSolver.SolveControl.class));
    DemoSolveJobService.OwnedJob newer=null;
    try {
      scheduling.reconcileDemo();awaitOuterRunnable();assertThat(fault.replyLost.get()).isTrue();assertThat(hasRetainedExit(old)).isTrue();
      assertThat(jobs.state(old)).isEqualTo("SUCCEEDED");assertMinimalTombstone(old,before);
      String success=runRow(old.runId());var tombstone=jdbc.queryForMap("select * from demo_solve_job where reservation_id=?",old.reservationId());
      assertThat(tombstone.get("native_exited_at")).isNotNull();
      var reset=fixture.reset(browser,UUID.randomUUID());assertThat(reset.getResponse().getStatus()).isEqualTo(200);
      var current=fixture.browser(browser.visitor(),browser.operation(),reset,browser.cookie());holdNext.set(true);
      assertThat(start(fixture,current,fixture.smallTask(current))).isEqualTo(200);assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();newer=owned(current);
      fault.restore();jobs.retryExited();jobs.retryExited();jobs.workerExited(old);
      assertThat(hasRetainedExit(old)).isFalse();assertThat(runRow(old.runId())).isEqualTo(success);
      assertThat(jdbc.queryForMap("select * from demo_solve_job where reservation_id=?",old.reservationId())).isEqualTo(tombstone);
      assertThat(jobs.state(newer)).isEqualTo("RUNNING");assertThat(control.get().stopReason()).isNull();
      assertThat(jdbc.queryForObject("select released_at is null from demo_solve_job where reservation_id=?",Boolean.class,newer.reservationId())).isTrue();
      release.countDown();awaitReleased(newer);assertThat(jobs.state(newer)).isEqualTo("SUCCEEDED");
    }finally{fault.restore();release.countDown();if(newer!=null)awaitReleased(newer);jobs.retryExited();}
  }

  private void assertMinimalTombstone(DemoSolveJobService.OwnedJob job,java.util.Map<String,Object> before) {
    var after=jdbc.queryForMap("select * from demo_solve_job where reservation_id=?",job.reservationId());
    for(String key:java.util.List.of("reservation_id","workspace_id","generation","org_id","run_id","task_id","owner_boot_token","admitted_tick","admitted_at","deadline_at","lease_expires_at"))
      assertThat(after.get(key)).as("exact retained owner %s",key).isEqualTo(before.get(key));
    assertThat(after.get("fixture_json")).isNull();assertThat(after.get("policy_json")).isNull();
    assertThat(after.get("released_at")).isNotNull();assertThat(after.get("payload_scrubbed_at")).isNotNull();
    assertThatThrownBy(()->jdbc.update("delete from demo_solve_job where reservation_id=?",job.reservationId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(()->jdbc.update("update demo_solve_job set owner_boot_token=? where reservation_id=?",UUID.randomUUID(),job.reservationId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(()->jdbc.update("update demo_solve_job set fixture_json=?::jsonb,policy_json=?::jsonb,payload_scrubbed_at=null where reservation_id=?",before.get("fixture_json").toString(),before.get("policy_json").toString(),job.reservationId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(jdbc.queryForMap("select * from demo_solve_job where reservation_id=?",job.reservationId())).isEqualTo(after);
  }

  @Test @Timeout(60) void queuedRecoveryRetainsMarkedInputsUntilActualOwnerExitThenScrubsIrreversibly() throws Exception {
    var fixture=new DemoTestFixture(application);var browser=fixture.enter();UUID task=fixture.smallTask(browser);String marker=fixture.markSolveInputs(browser,task);
    var blockQueue=new CountDownLatch(1);var releaseQueue=new CountDownLatch(1);var entered=new CountDownLatch(1);var releaseWorker=new CountDownLatch(1);
    executor.execute(()->{blockQueue.countDown();try{releaseQueue.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
    assertThat(blockQueue.await(5,TimeUnit.SECONDS)).isTrue();
    doAnswer(call->{entered.countDown();releaseWorker.await();return call.callRealMethod();})
        .when(solver).solve(any(ScheduleFixture.class),any(SchedulePolicy.class),any(CpSatScheduleSolver.SolveControl.class));
    var policy=new SchedulePolicy(false,SchedulePolicy.SameGrade.NONE,true);var job=admitWithoutSubmit(browser,task,jobs,policy);
    try {
      var before=jdbc.queryForMap("select * from demo_solve_job where reservation_id=?",job.reservationId());
      assertThat(before.get("fixture_json").toString()).contains(marker+"-teacher",marker+"-exam","2035-01-02");
      var recovered=owned(browser);assertThat(recovered.fixture()).isEqualTo(job.fixture());assertThat(recovered.policy()).isEqualTo(policy);
      assertThatThrownBy(()->jdbc.update("update demo_solve_job set fixture_json='{}'::jsonb where reservation_id=?",job.reservationId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
      assertThatThrownBy(()->jdbc.update("update demo_solve_job set fixture_json=null,policy_json=null,payload_scrubbed_at=now() where reservation_id=?",job.reservationId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
      scheduling.reconcileDemo();releaseQueue.countDown();assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
      assertThat(jobs.state(job)).isEqualTo("RUNNING");assertThat(jdbc.queryForObject("select fixture_json::text from demo_solve_job where reservation_id=?",String.class,job.reservationId())).isEqualTo(before.get("fixture_json").toString());
      releaseWorker.countDown();awaitReleased(job);assertThat(jobs.state(job)).isEqualTo("SUCCEEDED");assertMinimalTombstone(job,before);
      assertThat(jdbc.queryForObject("select native_exited_at from demo_solve_job where reservation_id=?",java.sql.Timestamp.class,job.reservationId())).isNotNull();
    }finally{releaseQueue.countDown();releaseWorker.countDown();awaitReleased(job);}
  }

  @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
  void infeasibleAndExceptionReleaseOnlyTheirOwnerWithoutAssignments(boolean exception) throws Exception {
    var fixture=new DemoTestFixture(application);var browser=fixture.enter();UUID task=fixture.smallTask(browser);
    if(exception)doThrow(new IllegalStateException("controlled solver failure")).when(solver).solve(any(ScheduleFixture.class),any(SchedulePolicy.class),any(CpSatScheduleSolver.SolveControl.class));
    else jdbc.update("update teacher set qualifications='[]'::jsonb where org_id=?",browser.org());
    var job=admitWithoutSubmit(browser,task);scheduling.reconcileDemo();awaitReleased(job);
    assertThat(jobs.state(job)).isEqualTo(exception?"FAILED":"INFEASIBLE");
    assertThat(jdbc.queryForObject("select status from schedule_run where id=?",String.class,job.runId())).isEqualTo(exception?"failed":"infeasible");
    assertThat(jdbc.queryForObject("select count(*) from schedule_assignment where schedule_run_id=?",Integer.class,job.runId())).isZero();
    assertThat(jdbc.queryForObject("select native_exited_at from demo_solve_job where reservation_id=?",java.sql.Timestamp.class,job.reservationId())).isNull();
    jobs.workerExited(job);assertThat(jobs.state(job)).isEqualTo(exception?"FAILED":"INFEASIBLE");
  }

  @Test void actualNativeCallHasOneThirtySecondAdmissionBudgetAndRetainsSlotUntilReturn() throws Exception {
    var fixture=new DemoTestFixture(application);var browser=fixture.enter();UUID task=fixture.smallTask(browser);
    var nativeEntered=new CountDownLatch(1);var releaseNative=new CountDownLatch(1);var nativeReturned=new CountDownLatch(1);
    var calls=new java.util.concurrent.atomic.AtomicInteger();var remaining=new java.util.concurrent.atomic.AtomicReference<Double>();
    var captured=new java.util.concurrent.atomic.AtomicReference<CpSatScheduleSolver.SolveControl>();
    var real=new CpSatScheduleSolver(){
      @Override protected com.google.ortools.sat.CpSolverStatus runNative(com.google.ortools.sat.CpSolver nativeSolver,com.google.ortools.sat.CpModel model) {
        calls.incrementAndGet();remaining.set(nativeSolver.getParameters().getMaxTimeInSeconds());
        try{return nativeSolver.solve(model,new com.google.ortools.sat.CpSolverSolutionCallback(){
          @Override public void onSolutionCallback(){nativeEntered.countDown();try{assertThat(releaseNative.await(40,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new RuntimeException(e);}}
        });}finally{nativeReturned.countDown();}
      }
    };
    doAnswer(call->{captured.set(call.getArgument(2));return real.solve(call.getArgument(0),call.getArgument(1),call.getArgument(2));})
        .when(solver).solve(any(ScheduleFixture.class),any(SchedulePolicy.class),any(CpSatScheduleSolver.SolveControl.class));
    DemoSolveJobService.OwnedJob job=null;
    try {
      assertThat(start(fixture,browser,task)).isEqualTo(200);assertThat(nativeEntered.await(10,TimeUnit.SECONDS)).isTrue();job=owned(browser);var owner=job;
      assertThat(remaining.get()).isPositive().isLessThanOrEqualTo(30.0);
      org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(35)).untilAsserted(()->assertThat(captured.get().stopReason()).isEqualTo("DEMO_SOLVE_TIMEOUT"));
      long elapsed=System.nanoTime()-job.admittedTick();assertThat(elapsed).isGreaterThanOrEqualTo(DemoSolveJobService.BUDGET_NANOS).isLessThan(java.time.Duration.ofSeconds(36).toNanos());
      assertThat(captured.get().stopDeliveries()).isPositive();assertThat(nativeReturned.getCount()).isEqualTo(1);
      assertThat(captured.get().nativeReturned()).isFalse();
      assertThat(jdbc.queryForObject("select released_at is null and native_exited_at is null from demo_solve_job where reservation_id=?",Boolean.class,job.reservationId())).isTrue();
      assertThatThrownBy(()->jobs.workerExited(owner)).isInstanceOf(IllegalStateException.class);
      releaseNative.countDown();assertThat(nativeReturned.await(5,TimeUnit.SECONDS)).isTrue();awaitReleased(job);
      assertThat(calls.get()).isEqualTo(1);assertThat(captured.get().nativeReturned()).isTrue();
      var row=jdbc.queryForMap("select state,terminal_reason,native_exited_at,released_at from demo_solve_job where reservation_id=?",job.reservationId());
      assertThat(row.get("state")).isEqualTo("CANCELLED");assertThat(row.get("terminal_reason")).isEqualTo("DEMO_SOLVE_TIMEOUT");assertThat(row.get("native_exited_at")).isNotNull();
      assertThat(jdbc.queryForObject("select error_code from schedule_run where id=?",String.class,job.runId())).isEqualTo("DEMO_SOLVE_TIMEOUT");
      assertThat(jdbc.queryForObject("select count(*) from schedule_assignment where schedule_run_id=?",Integer.class,job.runId())).isZero();
      long deliveries=captured.get().stopDeliveries();jobs.workerExited(job);assertThat(captured.get().stopDeliveries()).isEqualTo(deliveries);
      System.out.printf("TASK6B_NATIVE_30 admittedToStopNanos=%d remainingAtNative=%f calls=%d stopDeliveries=%d actualReturn=true outerReleased=true%n",elapsed,remaining.get(),calls.get(),deliveries);
    }finally{releaseNative.countDown();if(job!=null)awaitReleased(job);}
  }

  @Test void preWrapperCancellationReachesRealNativeCallAndCannotRetryOrReleaseEarly() throws Exception {
    var fixture=new DemoTestFixture(application);var browser=fixture.enter();UUID task=fixture.smallTask(browser);
    var beforeWrapper=new CountDownLatch(1);var enterNative=new CountDownLatch(1);var returned=new CountDownLatch(1);var calls=new java.util.concurrent.atomic.AtomicInteger();
    var captured=new java.util.concurrent.atomic.AtomicReference<CpSatScheduleSolver.SolveControl>();
    var real=new CpSatScheduleSolver(){
      @Override protected com.google.ortools.sat.CpSolverStatus runNative(com.google.ortools.sat.CpSolver nativeSolver,com.google.ortools.sat.CpModel model) {
        calls.incrementAndGet();beforeWrapper.countDown();try{assertThat(enterNative.await(15,TimeUnit.SECONDS)).isTrue();return nativeSolver.solve(model);}
        catch(InterruptedException e){throw new RuntimeException(e);}finally{returned.countDown();}
      }
    };
    doAnswer(call->{captured.set(call.getArgument(2));return real.solve(call.getArgument(0),call.getArgument(1),call.getArgument(2));})
        .when(solver).solve(any(ScheduleFixture.class),any(SchedulePolicy.class),any(CpSatScheduleSolver.SolveControl.class));
    DemoSolveJobService.OwnedJob job=null;
    try {
      assertThat(start(fixture,browser,task)).isEqualTo(200);assertThat(beforeWrapper.await(10,TimeUnit.SECONDS)).isTrue();job=owned(browser);
      jobs.cancel(job,"DEMO_RESET");assertThat(jobs.state(job)).isEqualTo("CANCEL_REQUESTED");
      org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3)).untilAsserted(()->assertThat(captured.get().stopDeliveries()).isGreaterThan(1));
      assertThat(returned.getCount()).isEqualTo(1);assertThat(jdbc.queryForObject("select released_at is null from demo_solve_job where reservation_id=?",Boolean.class,job.reservationId())).isTrue();
      enterNative.countDown();assertThat(returned.await(5,TimeUnit.SECONDS)).isTrue();awaitReleased(job);
      assertThat(calls.get()).isEqualTo(1);assertThat(captured.get().nativeReturned()).isTrue();
      assertThat(jdbc.queryForObject("select count(*) from schedule_assignment where schedule_run_id=?",Integer.class,job.runId())).isZero();
    }finally{enterNative.countDown();if(job!=null)awaitReleased(job);}
  }

  @Test void successfulResetReplayAndLateOldExitCannotCancelOrFreeNewGenerationJob() throws Exception {
    var fixture=new DemoTestFixture(application);var browser=fixture.enter();UUID first=fixture.smallTask(browser);
    var old=admitWithoutSubmit(browser,first);scheduling.reconcileDemo();awaitReleased(old);
    UUID operation=UUID.randomUUID();var reset=fixture.reset(browser,operation);assertThat(reset.getResponse().getStatus()).isEqualTo(200);
    var current=fixture.browser(browser.visitor(),browser.operation(),reset,browser.cookie());var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    var captured=new java.util.concurrent.atomic.AtomicReference<CpSatScheduleSolver.SolveControl>();
    doAnswer(call->{captured.set(call.getArgument(2));entered.countDown();assertThat(release.await(15,TimeUnit.SECONDS)).isTrue();return call.callRealMethod();})
        .when(solver).solve(any(ScheduleFixture.class),any(SchedulePolicy.class),any(CpSatScheduleSolver.SolveControl.class));
    DemoSolveJobService.OwnedJob newer=null;
    try {
      assertThat(start(fixture,current,fixture.smallTask(current))).isEqualTo(200);assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();newer=owned(current);
      assertThat(fixture.reset(current,operation).getResponse().getStatus()).isEqualTo(200);
      jobs.workerExited(old);jobs.cancel(old,"DEMO_RESET");
      assertThat(jobs.state(newer)).isEqualTo("RUNNING");assertThat(captured.get().stopReason()).isNull();
      assertThat(jdbc.queryForObject("select released_at is null from demo_solve_job where reservation_id=?",Boolean.class,newer.reservationId())).isTrue();
      release.countDown();awaitReleased(newer);assertThat(jobs.state(newer)).isEqualTo("SUCCEEDED");
    }finally{release.countDown();if(newer!=null)awaitReleased(newer);}
  }

  @Test void durableQueuedRecoveryDispatchesOnceAndClearsWrongPooledTenant() throws Exception {
    var fixture=new DemoTestFixture(application);var browser=fixture.enter();UUID task=fixture.smallTask(browser);
    var dirty=new CountDownLatch(1);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    executor.execute(()->{com.jkxt.common.tenant.TenantContext.set(UUID.randomUUID());dirty.countDown();});assertThat(dirty.await(5,TimeUnit.SECONDS)).isTrue();
    doAnswer(call->{assertThat(com.jkxt.common.tenant.TenantContext.get()).isEmpty();entered.countDown();
      assertThat(release.await(15,TimeUnit.SECONDS)).isTrue();return call.callRealMethod();})
        .when(solver).solve(any(ScheduleFixture.class),any(SchedulePolicy.class),any(CpSatScheduleSolver.SolveControl.class));
    var job=admitWithoutSubmit(browser,task);
    try {
      scheduling.reconcileDemo();scheduling.reconcileDemo();assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
      assertThat(jobs.state(job)).isEqualTo("RUNNING");scheduling.reconcileDemo();
      verify(solver,times(1)).solve(any(ScheduleFixture.class),any(SchedulePolicy.class),any(CpSatScheduleSolver.SolveControl.class));
    }finally{release.countDown();awaitReleased(job);}
    var clean=new CompletableFuture<Boolean>();executor.execute(()->clean.complete(com.jkxt.common.tenant.TenantContext.get().isEmpty()));
    assertThat(clean.get(5,TimeUnit.SECONDS)).isTrue();assertThat(jobs.state(job)).isEqualTo("SUCCEEDED");
  }

  @Test void realExecutorRejectionCompensatesOnlyTheCommittedQueuedOwner() throws Exception {
    var fixture=new DemoTestFixture(application);var browser=fixture.enter();UUID task=fixture.smallTask(browser);
    var occupied=new CountDownLatch(1);var release=new CountDownLatch(1);var drained=new CountDownLatch(20);
    executor.execute(()->{occupied.countDown();try{release.await(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
    assertThat(occupied.await(5,TimeUnit.SECONDS)).isTrue();
    try {
      for(int i=0;i<20;i++)executor.execute(drained::countDown);
      assertThat(start(fixture,browser,task)).isEqualTo(200);
      var row=jdbc.queryForMap("select * from demo_solve_job where workspace_id=?",browser.workspace());
      assertThat(row.get("state")).isEqualTo("FAILED");assertThat(row.get("terminal_reason")).isEqualTo("EXECUTOR_REJECTED");assertThat(row.get("released_at")).isNotNull();
      assertThat(row.get("fixture_json")).isNull();assertThat(row.get("policy_json")).isNull();assertThat(row.get("payload_scrubbed_at")).isNotNull();assertThat(row.get("native_exited_at")).isNull();
      assertThat(jdbc.queryForObject("select status from schedule_run where id=?",String.class,row.get("run_id"))).isEqualTo("failed");
      verify(solver,never()).solve(any(ScheduleFixture.class),any(SchedulePolicy.class),any(CpSatScheduleSolver.SolveControl.class));
    }finally{release.countDown();assertThat(drained.await(5,TimeUnit.SECONDS)).isTrue();}
  }

  @Test void redisRateKeyLossCannotRemoveDurableWorkspaceOccupancy() throws Exception {
    var fixture=new DemoTestFixture(application);var browser=fixture.enter();UUID task=fixture.smallTask(browser),other=fixture.smallTask(browser);
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    doAnswer(call->{entered.countDown();assertThat(release.await(15,TimeUnit.SECONDS)).isTrue();return call.callRealMethod();})
        .when(solver).solve(any(ScheduleFixture.class),any(SchedulePolicy.class),any(CpSatScheduleSolver.SolveControl.class));
    DemoSolveJobService.OwnedJob job=null;
    try {
      assertThat(start(fixture,browser,task)).isEqualTo(200);assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();job=owned(browser);
      application.getBean(org.springframework.data.redis.core.StringRedisTemplate.class).delete(DemoQuotaService.rateKey("solve",browser.workspace().toString()));
      assertThat(start(fixture,browser,other)).isEqualTo(409);
      assertThat(jdbc.queryForObject("select count(*) from demo_solve_job where workspace_id=? and released_at is null",Integer.class,browser.workspace())).isEqualTo(1);
    }finally{release.countDown();if(job!=null)awaitReleased(job);}
  }

  @Test void teacherEligibilityIsRecheckedAfterActualNativeReturnBeforePersistence() throws Exception {
    var fixture=new DemoTestFixture(application);var browser=fixture.enter();UUID task=fixture.smallTask(browser);
    var computed=new CountDownLatch(1);var release=new CountDownLatch(1);var result=new java.util.concurrent.atomic.AtomicReference<ScheduleSolverResult>();
    doAnswer(call->{var solved=(ScheduleSolverResult)call.callRealMethod();result.set(solved);computed.countDown();assertThat(release.await(15,TimeUnit.SECONDS)).isTrue();return solved;})
        .when(solver).solve(any(ScheduleFixture.class),any(SchedulePolicy.class),any(CpSatScheduleSolver.SolveControl.class));
    DemoSolveJobService.OwnedJob job=null;
    try {
      assertThat(start(fixture,browser,task)).isEqualTo(200);assertThat(computed.await(10,TimeUnit.SECONDS)).isTrue();job=owned(browser);
      assertThat(result.get().status()).isEqualTo("succeeded");UUID teacher=result.get().assignments().getFirst().teacherId();
      String number=jdbc.queryForObject("select teacher_no from teacher where id=? and org_id=?",String.class,teacher,browser.org());
      var file=DemoBusinessFlowIntegrationTest.workbook(new String[]{"teacherNo","name","qualifications","qualificationExpiry"},java.util.List.<String[]>of(new String[]{number,"资格撤销合成教师","",""}));
      assertThat(http.perform(fixture.apply(multipart("/api/v1/import/teachers/commit").file(file),browser)).andReturn().getResponse().getStatus()).isEqualTo(200);
      release.countDown();awaitReleased(job);
      assertThat(jdbc.queryForObject("select error_code from schedule_run where id=?",String.class,job.runId())).isEqualTo("DEMO_SOLVE_INPUT_CHANGED");
      assertThat(jdbc.queryForObject("select count(*) from schedule_assignment where schedule_run_id=?",Integer.class,job.runId())).isZero();
    }finally{release.countDown();if(job!=null)awaitReleased(job);}
  }

  @Test void stopSignalDoesNotWaitForWorkspaceLockAndDoesNotReleaseRunningWorker() throws Exception {
    var fixture=new DemoTestFixture(application);var browser=fixture.enter();UUID task=fixture.smallTask(browser);
    var entered=new CountDownLatch(1);var releaseWorker=new CountDownLatch(1);var locked=new CountDownLatch(1);var unlock=new CountDownLatch(1);
    var control=new java.util.concurrent.atomic.AtomicReference<CpSatScheduleSolver.SolveControl>();
    doAnswer(call->{control.set(call.getArgument(2));entered.countDown();assertThat(releaseWorker.await(20,TimeUnit.SECONDS)).isTrue();return call.callRealMethod();})
        .when(solver).solve(any(ScheduleFixture.class),any(SchedulePolicy.class),any(CpSatScheduleSolver.SolveControl.class));
    var pool=Executors.newFixedThreadPool(2);DemoSolveJobService.OwnedJob job=null;
    try {
      assertThat(start(fixture,browser,task)).isEqualTo(200);assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();job=owned(browser);var captured=job;
      var holder=pool.submit(()->repository.transaction(()->{repository.workspace(browser.workspace(),true);locked.countDown();try{assertThat(unlock.await(15,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new RuntimeException(e);}return null;}));
      assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();var cancellation=pool.submit(()->jobs.cancel(captured,"DEMO_RESET"));
      org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(()->assertThat(control.get().stopReason()).isEqualTo("DEMO_RESET"));
      DemoTestFixture.awaitBlockedQuery(jdbc,"demo_workspace");assertThat(cancellation.isDone()).isFalse();
      assertThat(jdbc.queryForObject("select released_at is null from demo_solve_job where reservation_id=?",Boolean.class,job.reservationId())).isTrue();
      unlock.countDown();holder.get(5,TimeUnit.SECONDS);cancellation.get(5,TimeUnit.SECONDS);
    }finally{unlock.countDown();releaseWorker.countDown();if(job!=null)awaitReleased(job);pool.shutdownNow();}
  }

  @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
  void resetFencesLateSuccessAndKeepsOccupancyUntilOuterWorkerExits(boolean lateFailure) throws Exception {
    var fixture=new DemoTestFixture(application);var browser=fixture.enter();var unrelated=fixture.enter();
    String otherBefore=fixture.fingerprint(unrelated.visitor().id());UUID task=fixture.smallTask(browser);
    var noticeBefore=jdbc.queryForList("select to_jsonb(n)::text from notification n where org_id=? order by id",String.class,browser.org());
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    doAnswer(call->{entered.countDown();assertThat(release.await(20,TimeUnit.SECONDS)).isTrue();
      if(lateFailure)throw new IllegalStateException("controlled late worker failure");
      // A deliberately uncooperative Java worker returns a real native result after cancellation.
      return new CpSatScheduleSolver().solve(call.getArgument(0),call.getArgument(1));})
        .when(solver).solve(any(ScheduleFixture.class),any(SchedulePolicy.class),any(CpSatScheduleSolver.SolveControl.class));
    DemoSolveJobService.OwnedJob old=null;
    try {
      assertThat(start(fixture,browser,task)).isEqualTo(200);assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();old=owned(browser);
      var logs=jdbc.queryForObject("select logs::text from schedule_run where id=?",String.class,old.runId());
      var reset=fixture.reset(browser,UUID.randomUUID());assertThat(reset.getResponse().getStatus()).isEqualTo(200);
      var current=fixture.browser(browser.visitor(),browser.operation(),reset,browser.cookie());
      assertThat(jobs.state(old)).isEqualTo("CANCEL_REQUESTED");
      assertThat(jdbc.queryForObject("select released_at is null from demo_solve_job where reservation_id=?",Boolean.class,old.reservationId())).isTrue();
      assertThat(start(fixture,current,fixture.smallTask(current))).isEqualTo(409);
      release.countDown();awaitReleased(old);
      assertThat(jdbc.queryForObject("select logs::text from schedule_run where id=?",String.class,old.runId())).isEqualTo(logs);
      assertThat(jdbc.queryForObject("select count(*) from schedule_assignment where schedule_run_id=?",Integer.class,old.runId())).isZero();
      assertThat(jdbc.queryForList("select to_jsonb(n)::text from notification n where org_id=? order by id",String.class,browser.org())).isEqualTo(noticeBefore);
      assertThat(fixture.fingerprint(unrelated.visitor().id())).isEqualTo(otherBefore);
      jobs.workerExited(old); // delayed duplicate acknowledgment cannot alter the next occupancy.
    }finally{release.countDown();if(old!=null)awaitReleased(old);}
  }

  @Test void queuedCancellationPreventsNativeClaimAndReleasesExactlyOnce() throws Exception {
    var fixture=new DemoTestFixture(application);var browser=fixture.enter();UUID task=fixture.smallTask(browser);
    var blocked=new CountDownLatch(1);var release=new CountDownLatch(1);var drained=new CountDownLatch(1);
    executor.execute(()->{blocked.countDown();try{release.await(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
    assertThat(blocked.await(5,TimeUnit.SECONDS)).isTrue();
    try {
      assertThat(start(fixture,browser,task)).isEqualTo(200);var job=owned(browser);
      var before=jdbc.queryForMap("select * from demo_solve_job where reservation_id=?",job.reservationId());
      assertThat(jobs.state(job)).isEqualTo("QUEUED");jobs.cancel(job,"DEMO_RESET");awaitReleased(job);
      release.countDown();executor.execute(drained::countDown);assertThat(drained.await(10,TimeUnit.SECONDS)).isTrue();
      assertThat(jobs.state(job)).isEqualTo("CANCELLED");assertThat(jobs.claim(job)).isNull();
      verify(solver,never()).solve(any(ScheduleFixture.class),any(SchedulePolicy.class),any(CpSatScheduleSolver.SolveControl.class));
      assertThat(jdbc.queryForObject("select count(*) from schedule_assignment where schedule_run_id=?",Integer.class,job.runId())).isZero();
      assertThat(jdbc.queryForObject("select native_exited_at from demo_solve_job where reservation_id=?",java.sql.Timestamp.class,job.reservationId())).isNull();
      assertMinimalTombstone(job,before);
    }finally{release.countDown();}
  }

  @Test void admissionRollbackLeavesNeitherRunNorOccupancyAndOuterTransactionIsRejected() throws Exception {
    var fixture=new DemoTestFixture(application);var browser=fixture.enter();var lease=repository.workspace(browser.workspace(),false).lease();
    var template=new org.springframework.transaction.support.TransactionTemplate(application.getBean(org.springframework.transaction.PlatformTransactionManager.class));
    UUID task=fixture.smallTask(browser),run=UUID.randomUUID();
    assertThatThrownBy(()->jobs.admit(lease,()->{
      jdbc.update("insert into schedule_run(id,org_id,exam_task_id,status) values(?,?,?,'pending')",run,browser.org(),task);
      throw new IllegalStateException("controlled rollback");
    })).hasMessage("controlled rollback");
    assertThat(jdbc.queryForObject("select count(*) from schedule_run where id=?",Integer.class,run)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from demo_solve_job where workspace_id=?",Integer.class,browser.workspace())).isZero();
    assertThatThrownBy(()->template.execute(s->jobs.admit(lease,()->{throw new AssertionError("must reject before mutation");})))
        .isInstanceOf(com.jkxt.common.web.ApiException.class).hasMessageContaining("独立提交");
  }

  @Test void internalDemoCallWithoutCapturedLeaseFailsClosed() throws Exception {
    var fixture=new DemoTestFixture(application);var browser=fixture.enter();UUID task=fixture.smallTask(browser);
    com.jkxt.common.tenant.TenantContext.set(browser.org());
    try{assertThatThrownBy(()->scheduling.startTaskRun(task)).isInstanceOf(com.jkxt.common.web.ApiException.class).hasMessageContaining("服务器会话");}
    finally{com.jkxt.common.tenant.TenantContext.clear();}
  }
}
