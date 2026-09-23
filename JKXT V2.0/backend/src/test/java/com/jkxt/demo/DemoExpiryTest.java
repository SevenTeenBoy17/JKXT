package com.jkxt.demo;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
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
class DemoExpiryTest extends DemoTestFixture.IntegrationBase {
  @Autowired MockMvc http;
  @Autowired ObjectMapper mapper;
  @Autowired DemoTestFixture.MutableClock clock;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @Autowired org.springframework.context.ApplicationContext application;
  @org.junit.jupiter.api.AfterEach void retire(){DemoTestFixture.retire(jdbc);}
  @Test void startupContextHasAnAbsoluteShortExpiry() throws Exception {
    var visitor=new DemoTestFixture(http,mapper).visitor();
    assertThat(visitor.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(10)));
  }
  @Test void registeredMinuteScheduleInvokesRealCleanupOnlyWhenExplicitlyAdvanced() throws Exception {
    var f=new DemoTestFixture(application);var browser=f.enter();var original=clock.instant();
    var scheduler=application.getBean(DemoTestFixture.ManualScheduler.class);
    assertThat(scheduler.registrations()).hasSize(1);var registration=scheduler.registrations().getFirst();
    assertThat(Duration.between(registration.registered,registration.first)).isEqualTo(Duration.ofSeconds(60));
    assertThat(registration.delay).isEqualTo(Duration.ofSeconds(60));
    try {
      clock.advance(Duration.ofMinutes(30));
      assertThat(jdbc.queryForObject("select state from demo_workspace where id=?",String.class,browser.workspace())).isEqualTo("READY");
      scheduler.fireDue();
      assertThat(jdbc.queryForObject("select state from demo_workspace where id=?",String.class,browser.workspace())).isEqualTo("EXPIRED");
      assertThat(jdbc.queryForObject("select cleanup_state from demo_generation where workspace_id=?",String.class,browser.workspace())).isEqualTo("DONE");
    }finally{clock.set(original);}
  }
  @Test void idleExpiresAtThirtyMinutesEvenIfMeIsPolled() throws Exception {
    var f=new DemoTestFixture(http,mapper);var browser=f.enter();
    try {
      clock.advance(Duration.ofMinutes(29));
      assertThat(http.perform(f.apply(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me"),browser))
          .andReturn().getResponse().getStatus()).isEqualTo(200);
      clock.advance(Duration.ofMinutes(1));
      var expired=http.perform(f.apply(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me"),browser)).andReturn();
      assertThat(expired.getResponse().getStatus()).isEqualTo(401);
      assertThat(mapper.readTree(expired.getResponse().getContentAsString()).path("code").asText()).isEqualTo("DEMO_EXPIRED");
    } finally { clock.set(java.time.Instant.now()); }
  }
  @Test void meReturnsCurrentServerIdleDeadlineWithoutRenewingActivity() throws Exception {
    var f=new DemoTestFixture(application);var browser=f.enter();var original=clock.instant();
    try {
      clock.advance(Duration.ofMinutes(10));var activity=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
      jdbc.update("update demo_workspace set last_activity_at=? where id=?",java.sql.Timestamp.from(activity),browser.workspace());
      for(int request=0;request<2;request++) {
        var result=http.perform(f.apply(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me"),browser)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("idleExpiresAt").asText()).isEqualTo(activity.plus(Duration.ofMinutes(30)).toString());
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();clock.advance(Duration.ofMinutes(5));
      }
      assertThat(jdbc.queryForObject("select last_activity_at from demo_workspace where id=?",java.sql.Timestamp.class,browser.workspace()).toInstant()).isEqualTo(activity);
    }finally{clock.set(original);}
  }
  @Test void cleanupPurgesOnlyExpiredGenerationAndRetainsImmutableTombstones() throws Exception {
    var fixture=new DemoTestFixture(application);var expired=fixture.enter();var other=fixture.enter();
    String otherFingerprint=fixture.fingerprint(other.visitor().id());
    jdbc.update("update demo_workspace set state='EXPIRED' where id=?",expired.workspace());
    var root=java.nio.file.Path.of(application.getBean(DemoProperties.class).getStorageRoot());
    var file=root.resolve(expired.workspace().toString()).resolve("1").resolve("fixture.txt");
    java.nio.file.Files.createDirectories(file.getParent());java.nio.file.Files.writeString(file,"synthetic task4 fixture");
    var result=application.getBean(DemoCleanupService.class).sweep(clock.instant());
    assertThat(jdbc.queryForObject("select count(*) from organization where id=?",Integer.class,expired.org())).isZero();
    assertThat(java.nio.file.Files.exists(file)).isFalse();
    assertThat(jdbc.queryForObject("select cleanup_state from demo_generation where workspace_id=? and generation=1",String.class,expired.workspace())).isEqualTo("DONE");
    assertThat(fixture.fingerprint(other.visitor().id())).isEqualTo(otherFingerprint);
    assertThat(result.cleaned()).isPositive();
  }
  @Test void resetAndBootstrapPollingCannotRenewTheTwoHourAbsoluteDeadline() throws Exception {
    var f=new DemoTestFixture(application);var browser=f.enter();var original=clock.instant();
    var absolute=jdbc.queryForObject("select expires_at from demo_workspace where id=?",java.sql.Timestamp.class,browser.workspace()).toInstant();
    try {
      for(int n=1;n<=7;n++) {
        clock.advance(Duration.ofMinutes(15));
        // Simulate later Task5's explicitly interactive activity; background requests never perform this write.
        jdbc.update("update demo_workspace set last_activity_at=? where id=?",java.sql.Timestamp.from(clock.instant()),browser.workspace());
      }
      var reset=f.reset(browser,java.util.UUID.randomUUID());assertThat(reset.getResponse().getStatus()).isEqualTo(200);
      assertThat(jdbc.queryForObject("select expires_at from demo_workspace where id=?",java.sql.Timestamp.class,browser.workspace()).toInstant()).isEqualTo(absolute);
      clock.set(absolute);
      var after=f.browser(browser.visitor(),browser.operation(),reset,browser.cookie());
      var expired=f.reset(after,java.util.UUID.randomUUID());assertThat(expired.getResponse().getStatus()).isEqualTo(401);
      assertThat(mapper.readTree(expired.getResponse().getContentAsString()).path("code").asText()).isEqualTo("DEMO_EXPIRED");
      application.getBean(DemoCleanupService.class).sweep(clock.instant());
      assertThat(jdbc.queryForObject("select state from demo_workspace where id=?",String.class,browser.workspace())).isEqualTo("EXPIRED");
    }finally{clock.set(original);}
  }
  @Test void recognizedExpiredSessionReportsExpiryEvenAfterRedisPayloadHasExpired() throws Exception {
    var f=new DemoTestFixture(application);var browser=f.enter();var original=clock.instant();
    try {
      clock.advance(Duration.ofHours(2));
      application.getBean(org.springframework.data.redis.core.StringRedisTemplate.class).delete("jkxt:session:demo:g:1:"+browser.cookie().getValue());
      var expired=http.perform(f.apply(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me"),browser)).andReturn();
      assertThat(expired.getResponse().getStatus()).isEqualTo(401);
      assertThat(mapper.readTree(expired.getResponse().getContentAsString()).path("code").asText()).isEqualTo("DEMO_EXPIRED");
    }finally{clock.set(original);}
  }
  @Test void deliberateNewEntryAfterIdleExpiryGetsANewWorkspaceWithoutRevivingOldCookie() throws Exception {
    var f=new DemoTestFixture(application);var browser=f.enter();var original=clock.instant();
    try {
      clock.advance(Duration.ofMinutes(30));
      var newEntry=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/demo/visitor")
          .header("Origin",DemoTestFixture.ORIGIN).cookie(browser.visitor().cookie()).contentType("application/json").content("{}")).andReturn();
      assertThat(newEntry.getResponse().getStatus()).isEqualTo(200);
      var json=mapper.readTree(newEntry.getResponse().getContentAsString());
      var visitor=new DemoTestFixture.Visitor(newEntry.getResponse().getCookie("JKXT_DEMO_VISITOR"),json.path("startupContext").asText(),
          java.util.UUID.fromString(json.path("visitorId").asText()),java.time.Instant.parse(json.path("expiresAt").asText()));
      var operation=java.util.UUID.randomUUID();var start=f.start(visitor,operation,"START");
      assertThat(start.getResponse().getStatus()).isEqualTo(200);
      var replacement=f.browser(visitor,operation,start,null);
      try {
        assertThat(replacement.workspace()).isNotEqualTo(browser.workspace());
        assertThat(replacement.cookie().getValue()).isNotEqualTo(browser.cookie().getValue());
        assertThat(jdbc.queryForObject("select state from demo_workspace where id=?",String.class,browser.workspace())).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select count(*) from demo_workspace where visitor_id=? and state<>'EXPIRED'",Integer.class,visitor.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from demo_workspace where state<>'EXPIRED' and expires_at>? and last_activity_at>?",Integer.class,
            java.sql.Timestamp.from(clock.instant()),java.sql.Timestamp.from(clock.instant().minus(Duration.ofMinutes(30))))).isEqualTo(1);
      }finally{jdbc.update("update demo_workspace set state='EXPIRED' where id=?",replacement.workspace());}
      assertThat(http.perform(f.apply(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me"),browser)).andReturn().getResponse().getStatus()).isEqualTo(401);
    }finally{clock.set(original);}
  }
  @Test void supersededGenerationIsCleanedWhileCurrentWorkspaceRemainsReady() throws Exception {
    var f=new DemoTestFixture(application);var browser=f.enter();var reset=f.reset(browser,java.util.UUID.randomUUID());
    assertThat(reset.getResponse().getStatus()).isEqualTo(200);
    var current=f.browser(browser.visitor(),browser.operation(),reset,browser.cookie());
    application.getBean(DemoCleanupService.class).sweep(clock.instant());
    assertThat(jdbc.queryForObject("select count(*) from organization where id=?",Integer.class,browser.org())).isZero();
    assertThat(jdbc.queryForObject("select count(*) from teacher where org_id=?",Integer.class,current.org())).isEqualTo(120);
    assertThat(http.perform(f.apply(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me"),current)).andReturn().getResponse().getStatus()).isEqualTo(200);
  }
  @Test void symlinkFailureDoesNotDeleteBusinessRowsAndFreshWorkerRetriesPersistedFailure() throws Exception {
    var f=new DemoTestFixture(application);var target=f.enter();var other=f.enter();String fingerprint=f.fingerprint(other.visitor().id());
    var root=java.nio.file.Path.of(application.getBean(DemoProperties.class).getStorageRoot());
    var otherRoot=root.resolve(other.workspace().toString()).resolve("1");java.nio.file.Files.createDirectories(otherRoot);
    java.nio.file.Files.writeString(otherRoot.resolve("keep.txt"),"other visitor");fingerprint=f.fingerprint(other.visitor().id());
    var workspace=root.resolve(target.workspace().toString());java.nio.file.Files.createDirectories(workspace);
    var link=workspace.resolve("1");java.nio.file.Files.createSymbolicLink(link,otherRoot);
    jdbc.update("update demo_workspace set state='EXPIRED' where id=?",target.workspace());
    var cleanup=application.getBean(DemoCleanupService.class);
    try(var log=new DemoTestFixture.CleanupLog()) {
    cleanup.sweep(clock.instant());
    assertThat(jdbc.queryForObject("select count(*) from organization where id=?",Integer.class,target.org())).isEqualTo(1);
    assertThat(jdbc.queryForObject("select cleanup_state from demo_generation where workspace_id=? and generation=1",String.class,target.workspace())).isEqualTo("FAILED");
    assertThat(f.fingerprint(other.visitor().id())).isEqualTo(fingerprint);
    assertThat(log.messages()).anySatisfy(message->assertThat(message).contains("reason=UNSAFE_PATH","workspace="+target.workspace(),"generation=1"));
    assertThat(String.join("\n",log.messages())).doesNotContain(root.toString(),target.visitor().cookie().getValue(),target.cookie().getValue(),"other visitor","keep.txt");
    assertThat(log.hasThrowable()).isFalse();
    java.nio.file.Files.delete(link);
    var fresh=new DemoCleanupService(application.getBean(DemoWorkspaceRepository.class),application.getBean(DemoDeploymentGuard.class),
        application.getBean(javax.sql.DataSource.class),application.getBean(DemoProperties.class),application.getBean(com.jkxt.common.config.DeployProperties.class),sessions,
        application.getBean(DemoSolveJobService.class),application.getBean(com.jkxt.scheduling.service.ScheduleProgressSink.class));
    fresh.sweep(clock.instant());
    assertThat(jdbc.queryForObject("select cleanup_state from demo_generation where workspace_id=? and generation=1",String.class,target.workspace())).isEqualTo("DONE");
    assertThat(f.fingerprint(other.visitor().id())).isEqualTo(fingerprint);
    }finally{java.nio.file.Files.deleteIfExists(link);}
  }
  @Test void weakCrossOrgReferenceStopsRootCascadeAndPreservesBothVisitors() throws Exception {
    var f=new DemoTestFixture(application);var target=f.enter();var other=f.enter();
    var room=jdbc.queryForObject("select id from classroom where org_id=? order by code limit 1",java.util.UUID.class,target.org());
    var otherClass=jdbc.queryForObject("select id from school_class where org_id=? order by code limit 1",java.util.UUID.class,other.org());
    var originalRoom=jdbc.queryForObject("select default_classroom_id from school_class where id=?",java.util.UUID.class,otherClass);
    jdbc.update("update school_class set default_classroom_id=? where id=?",room,otherClass);
    String fingerprint=f.fingerprint(other.visitor().id());jdbc.update("update demo_workspace set state='EXPIRED' where id=?",target.workspace());
    var cleanup=application.getBean(DemoCleanupService.class);cleanup.sweep(clock.instant());
    assertThat(jdbc.queryForObject("select count(*) from organization where id=?",Integer.class,target.org())).isEqualTo(1);
    assertThat(f.fingerprint(other.visitor().id())).isEqualTo(fingerprint);
    jdbc.update("update school_class set default_classroom_id=? where id=?",originalRoom,otherClass);cleanup.sweep(clock.instant());
    assertThat(jdbc.queryForObject("select count(*) from organization where id=?",Integer.class,target.org())).isZero();
  }
  @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"pending","running"})
  void persistedOrphanJobsRemainRetryableAfterWorkerRestartWithoutTouchingOtherVisitor(String state) throws Exception {
    var f=new DemoTestFixture(application);var target=f.enter();var other=f.enter();String fingerprint=f.fingerprint(other.visitor().id());
    var task=jdbc.queryForObject("select id from exam_task where org_id=?",java.util.UUID.class,target.org());
    var run=java.util.UUID.randomUUID();
    jdbc.update("insert into schedule_run(id,org_id,exam_task_id,status,started_at) values(?,?,?,?,?)",run,target.org(),task,state,java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(1))));
    jdbc.update("update demo_workspace set state='EXPIRED' where id=?",target.workspace());
    application.getBean(DemoCleanupService.class).sweep(clock.instant());
    var fresh=new DemoCleanupService(application.getBean(DemoWorkspaceRepository.class),application.getBean(DemoDeploymentGuard.class),
        application.getBean(javax.sql.DataSource.class),application.getBean(DemoProperties.class),application.getBean(com.jkxt.common.config.DeployProperties.class),sessions,
        application.getBean(DemoSolveJobService.class),application.getBean(com.jkxt.scheduling.service.ScheduleProgressSink.class));
    fresh.sweep(clock.instant());
    assertThat(jdbc.queryForObject("select cleanup_state from demo_generation where workspace_id=?",String.class,target.workspace())).isEqualTo("FAILED");
    assertThat(jdbc.queryForObject("select count(*) from schedule_run where org_id=? and status=?",Integer.class,target.org(),state)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from organization where id=?",Integer.class,target.org())).isEqualTo(1);
    assertThat(f.fingerprint(other.visitor().id())).isEqualTo(fingerprint);
  }
  @Test void failedAllocationCannotPinQuotaBeyondUnchangedIdleDeadline() throws Exception {
    var f=new DemoTestFixture(application);var visitor=f.visitor();var original=clock.instant();var op=java.util.UUID.randomUUID();
    org.mockito.Mockito.doThrow(new IllegalStateException("controlled seed failure")).when(seed).initialize(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt());
    try {
      assertThat(f.start(visitor,op,"START").getResponse().getStatus()).isEqualTo(503);
      var workspace=jdbc.queryForObject("select id from demo_workspace where visitor_id=?",java.util.UUID.class,visitor.id());
      var orgId=jdbc.queryForObject("select org_id from demo_workspace where id=?",java.util.UUID.class,workspace);
      var activity=jdbc.queryForObject("select last_activity_at from demo_workspace where id=?",java.sql.Timestamp.class,workspace);
      clock.advance(Duration.ofMinutes(9));assertThat(f.start(visitor,op,"START").getResponse().getStatus()).isEqualTo(503);
      assertThat(jdbc.queryForObject("select last_activity_at from demo_workspace where id=?",java.sql.Timestamp.class,workspace)).isEqualTo(activity);
      clock.set(activity.toInstant().plus(Duration.ofMinutes(30)));application.getBean(DemoCleanupService.class).sweep(clock.instant());
      assertThat(jdbc.queryForObject("select state from demo_workspace where id=?",String.class,workspace)).isEqualTo("EXPIRED");
      assertThat(jdbc.queryForObject("select count(*) from organization where id=?",Integer.class,orgId)).isZero();
    }finally{clock.set(original);}
  }
  @Test void delayedStartKeepsTheSameVisitorForTheWholeLiveWorkspace() throws Exception {
    var f=new DemoTestFixture(application);var visitor=f.visitor();var original=clock.instant();
    try {
      clock.advance(Duration.ofMinutes(9));var operation=java.util.UUID.randomUUID();var first=f.start(visitor,operation,"START");
      assertThat(first.getResponse().getStatus()).isEqualTo(200);var browser=f.browser(visitor,operation,first,null);
      var absolute=jdbc.queryForObject("select expires_at from demo_workspace where id=?",java.sql.Timestamp.class,browser.workspace());
      clock.advance(Duration.ofMinutes(112));
      // Simulate Task5 foreground activity, not a renewal caused by lifecycle requests.
      jdbc.update("update demo_workspace set last_activity_at=? where id=?",java.sql.Timestamp.from(clock.instant()),browser.workspace());
      var refreshed=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/demo/visitor")
          .header("Origin",DemoTestFixture.ORIGIN).cookie(visitor.cookie()).contentType("application/json").content("{}")).andReturn();
      assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);
      assertThat(mapper.readTree(refreshed.getResponse().getContentAsString()).path("visitorId").asText()).isEqualTo(visitor.id().toString());
      assertThat(refreshed.getResponse().getCookie("JKXT_DEMO_SESSION")).isNull();
      assertThat(jdbc.queryForObject("select expires_at from demo_workspace where id=?",java.sql.Timestamp.class,browser.workspace())).isEqualTo(absolute);
    }finally{clock.set(original);}
  }
  @Test void authenticatedStartThatWaitsPastStartupExpiryCannotCommitAllocation() throws Exception {
    var f=new DemoTestFixture(application);var visitor=f.visitor();var original=clock.instant();
    org.mockito.Mockito.doAnswer(call->{clock.advance(Duration.ofMinutes(10));return call.callRealMethod();})
        .when(seed).allocateIdentity(org.mockito.ArgumentMatchers.any());
    try {
      var response=f.start(visitor,java.util.UUID.randomUUID(),"START");
      assertThat(response.getResponse().getStatus()).isEqualTo(403);
      assertThat(response.getResponse().getCookie("JKXT_DEMO_SESSION")).isNull();
      assertThat(jdbc.queryForObject("select count(*) from demo_workspace where visitor_id=?",Integer.class,visitor.id())).isZero();
    }finally{clock.set(original);}
  }
  @Test void startActuallyBlockedOnAllocationLockRejectsExpiredCapturedProofWithoutAllocating() throws Exception {
    var f=new DemoTestFixture(application);var visitor=f.visitor();var original=clock.instant();var operation=java.util.UUID.randomUUID();
    var repository=application.getBean(DemoWorkspaceRepository.class);
    var locked=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
    try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var blocker=executor.submit(()->repository.transaction(()->{repository.allocationLock();locked.countDown();
        try{assertThat(release.await(25,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new RuntimeException(e);}return null;}));
      assertThat(locked.await(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var request=executor.submit(()->f.start(visitor,operation,"START"));
      DemoTestFixture.awaitBlockedQuery(jdbc,"pg_advisory_xact_lock(1246443598)");assertThat(request.isDone()).isFalse();
      assertThat(jdbc.queryForObject("select count(*) from demo_workspace where visitor_id=?",Integer.class,visitor.id())).isZero();
      clock.advance(Duration.ofMinutes(10));release.countDown();blocker.get(30,java.util.concurrent.TimeUnit.SECONDS);
      var result=request.get(30,java.util.concurrent.TimeUnit.SECONDS);assertThat(result.getResponse().getStatus()).isEqualTo(403);
      assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
      assertThat(jdbc.queryForObject("select count(*) from demo_workspace where visitor_id=?",Integer.class,visitor.id())).isZero();
      assertThat(jdbc.queryForObject("select count(*) from demo_operation where operation_id=?",Integer.class,operation)).isZero();
    }finally{release.countDown();clock.set(original);}
  }
  @Test void scheduledResourceGuardFailureHasSafeReasonAndRemainsRetryable() throws Exception {
    var f=new DemoTestFixture(application);var target=f.enter();var other=f.enter();String fingerprint=f.fingerprint(other.visitor().id());
    var properties=application.getBean(DemoProperties.class);String identity=properties.getEnvironmentId();
    String invalidIdentity=java.util.UUID.randomUUID().toString();var cleanup=application.getBean(DemoCleanupService.class);
    jdbc.update("update demo_workspace set state='EXPIRED' where id=?",target.workspace());
    try(var log=new DemoTestFixture.CleanupLog()) {
      properties.setEnvironmentId(invalidIdentity);cleanup.scheduledSweep();
      assertThat(jdbc.queryForObject("select count(*) from organization where id=?",Integer.class,target.org())).isEqualTo(1);
      assertThat(jdbc.queryForObject("select cleanup_state from demo_generation where workspace_id=?",String.class,target.workspace())).isEqualTo("PENDING");
      assertThat(log.messages()).anySatisfy(message->assertThat(message).contains("reason=RESOURCE_ATTESTATION","scope=sweep"));
      assertThat(String.join("\n",log.messages())).doesNotContain(identity,invalidIdentity,properties.getStorageRoot(),target.cookie().getValue(),target.visitor().cookie().getValue());
      assertThat(log.hasThrowable()).isFalse();assertThat(f.fingerprint(other.visitor().id())).isEqualTo(fingerprint);
    }finally{properties.setEnvironmentId(identity);}
    cleanup.sweep(clock.instant());
    assertThat(jdbc.queryForObject("select cleanup_state from demo_generation where workspace_id=?",String.class,target.workspace())).isEqualTo("DONE");
    assertThat(f.fingerprint(other.visitor().id())).isEqualTo(fingerprint);
  }
  @Test void generationPublicationRechecksDeadlineAtActualCommit() throws Exception {
    var f=new DemoTestFixture(application);var visitor=f.visitor();var original=clock.instant();
    org.mockito.Mockito.doAnswer(call->{var e=call.callRealMethod();
      org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
        @Override public int getOrder(){return Integer.MIN_VALUE;}
        @Override public void beforeCommit(boolean readOnly){clock.advance(Duration.ofMinutes(30));}
      });return e;
    }).when(sessions).prepareDemo(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.isNull());
    try {
      var response=f.start(visitor,java.util.UUID.randomUUID(),"START");assertThat(response.getResponse().getStatus()).isIn(401,403);
      var orgId=jdbc.queryForObject("select org_id from demo_workspace where visitor_id=?",java.util.UUID.class,visitor.id());
      assertThat(jdbc.queryForObject("select state from demo_workspace where visitor_id=?",String.class,visitor.id())).isNotEqualTo("READY");
      assertThat(jdbc.queryForObject("select count(*) from teacher where org_id=?",Integer.class,orgId)).isZero();
      assertThat(response.getResponse().getCookie("JKXT_DEMO_SESSION")).isNull();
    }finally{clock.set(original);}
  }
  @Test void oldGenerationRedisIndexLeftByInterruptedCleanupIsRemovedWithoutRevokingCurrentSession() throws Exception {
    var f=new DemoTestFixture(application);var before=f.enter();var captured=sessions.find(before.cookie().getValue(),com.jkxt.security.auth.PortalPurpose.DEMO).orElseThrow();
    var redis=application.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);
    String oldKey="jkxt:session:demo:g:1:"+before.cookie().getValue(),oldIndex="jkxt:user-sessions:v2:"+captured.principal().userId(),payload=redis.opsForValue().get(oldKey);
    assertThat(f.reset(before,java.util.UUID.randomUUID()).getResponse().getStatus()).isEqualTo(200);
    // Exact orphan left if an after-commit Redis unlink failed; the DB selector already points to generation2.
    redis.opsForValue().set(oldKey,payload,Duration.ofHours(2));redis.opsForSet().add(oldIndex,oldKey);
    application.getBean(DemoCleanupService.class).sweep(clock.instant());
    assertThat(redis.hasKey(oldKey)).isFalse();assertThat(redis.opsForSet().members(oldIndex)).doesNotContain(oldKey);
    assertThat(http.perform(f.apply(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me"),before)).andReturn().getResponse().getStatus()).isEqualTo(200);
  }
  @Test void moreThanOneHundredBlockedGenerationsCannotStarveLaterEligibleGeneration() throws Exception {
    var f=new DemoTestFixture(application);var other=f.enter();String fingerprint=f.fingerprint(other.visitor().id());
    var repository=application.getBean(DemoWorkspaceRepository.class);var workspace=java.util.UUID.randomUUID();
    var identities=new java.util.ArrayList<java.util.UUID>();
    repository.transaction(()->{
      for(int generation=1;generation<=102;generation++) {
        var orgId=java.util.UUID.randomUUID();var user=seed.allocateIdentity(orgId);identities.add(orgId);
        if(generation==1)jdbc.update("insert into demo_workspace(id,visitor_id,org_id,generation,state,created_at,expires_at,last_activity_at) values(?,?,?,1,'EXPIRED',?,?,?)",
            workspace,java.util.UUID.randomUUID(),orgId,java.sql.Timestamp.from(clock.instant()),java.sql.Timestamp.from(clock.instant().plus(Duration.ofHours(2))),java.sql.Timestamp.from(clock.instant()));
        jdbc.update("insert into demo_generation(workspace_id,generation,org_id,user_id,cleanup_state) values(?,?,?,?,'PENDING')",workspace,generation,orgId,user);
      }return null;
    });
    var root=java.nio.file.Path.of(application.getBean(DemoProperties.class).getStorageRoot());var owned=root.resolve(workspace.toString());
    java.nio.file.Files.createDirectories(owned);var links=new java.util.ArrayList<java.nio.file.Path>();
    try {
      for(int generation=1;generation<=101;generation++){var link=owned.resolve(Integer.toString(generation));java.nio.file.Files.createSymbolicLink(link,root.resolve(".jkxt-demo-environment"));links.add(link);}
      application.getBean(DemoCleanupService.class).sweep(clock.instant());
      assertThat(jdbc.queryForObject("select count(*) from organization where id=?",Integer.class,identities.get(101))).isZero();
      assertThat(jdbc.queryForObject("select count(*) from demo_generation where workspace_id=? and cleanup_state='FAILED'",Integer.class,workspace)).isEqualTo(101);
      assertThat(jdbc.queryForObject("select cleanup_state from demo_generation where workspace_id=? and generation=102",String.class,workspace)).isEqualTo("DONE");
      assertThat(f.fingerprint(other.visitor().id())).isEqualTo(fingerprint);
    }finally{for(var link:links)java.nio.file.Files.deleteIfExists(link);}
  }
}
