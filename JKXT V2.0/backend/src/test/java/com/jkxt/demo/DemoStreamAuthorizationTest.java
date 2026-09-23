package com.jkxt.demo;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.jkxt.security.auth.*;
import com.jkxt.scheduling.service.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"jkxt.auth.captcha-debug=false","jkxt.notify.email.enabled=false","jkxt.notify.sms.enabled=false"})
@AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@ActiveProfiles("test") @Import(DemoTestFixture.Time.class)
class DemoStreamAuthorizationTest extends DemoTestFixture.IntegrationBase {
  @LocalServerPort int port;
  @Autowired MockMvc http;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
  @Autowired ScheduleProgressSink sink;
  @Autowired ScheduleStreamAuthorization authorization;
  @Autowired DemoWorkspaceRepository repository;
  @Autowired @org.springframework.beans.factory.annotation.Qualifier("scheduleStreamExecutor") org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor sends;
  @Autowired @org.springframework.beans.factory.annotation.Qualifier("scheduleStreamChecks") ScheduledThreadPoolExecutor checks;
  @Autowired DemoTestFixture.MutableClock clock;
  @AfterEach void retire(){sink.shutdown();DemoTestFixture.retire(jdbc);}

  private AutoCloseable holdSends() throws Exception {
    var entered=new CountDownLatch(4);var release=new CountDownLatch(1);
    for(int i=0;i<4;i++)sends.execute(()->{entered.countDown();try{release.await(20,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
    assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();return release::countDown;
  }
  private ScheduleStreamAuthorization.Subscription capture(DemoTestFixture.Browser browser,UUID run) {
    return authorization.capture(run,sessions.find(browser.cookie().getValue(),PortalPurpose.DEMO).orElseThrow(),browser.org(),repository.workspace(browser.workspace(),false).lease());
  }
  private String readToEof(BufferedReader reader) throws Exception {
    var ended=new CompletableFuture<String>();Thread.ofVirtual().start(()->{try{ended.complete(reader.lines().collect(java.util.stream.Collectors.joining("\n")));}catch(Exception e){ended.completeExceptionally(e);}});
    return ended.get(8,TimeUnit.SECONDS);
  }
  private com.jkxt.scheduling.api.ScheduleDtos.ProgressEvent event(UUID run,String marker) {
    return new com.jkxt.scheduling.api.ScheduleDtos.ProgressEvent(run,"running",marker,42,marker,java.time.Instant.now());
  }

  @Test void replayIsQueuedBeforeConcurrentLivePublishOnActualHttpResponse() throws Exception {
    var fixture=new DemoTestFixture(application);var browser=fixture.enter();UUID run=completedJob(fixture,browser);var captured=capture(browser,run);
    try(var client=HttpClient.newHttpClient()) {
      CompletableFuture<HttpResponse<InputStream>> pending;
      try(var held=holdSends()) {
        pending=client.sendAsync(request(browser,run),HttpResponse.BodyHandlers.ofInputStream());
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(sink.subscriptionCount()).isEqualTo(1));
        // Component enqueue ordering is exercised directly; this is not a late-worker business write.
        sink.publish(event(run,"ordered-live-probe"),captured.owner());
      }
      var response=pending.get(5,TimeUnit.SECONDS);assertThat(response.statusCode()).isEqualTo(200);
      try(var input=response.body();var reader=new BufferedReader(new InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8))) {
        var progress=new ArrayList<Integer>();String last="";
        for(int i=0;i<5;i++){last=frame(reader);assertThat(last).contains("event:progress");String json=last.lines().filter(l->l.startsWith("data:")).findFirst().orElseThrow().substring(5);progress.add(mapper.readTree(json).path("progress").asInt());}
        assertThat(progress).containsExactly(15,35,80,100,42);assertThat(last).contains("ordered-live-probe");
        sink.discardGeneration(captured.owner().lease());assertThat(readToEof(reader)).isEmpty();
      }
    }
    assertThat(sink.subscriptionCount()).isZero();assertThat(checks.getQueue()).isEmpty();
  }

  @Test void queuedLiveFrameIsDiscardedAfterLogoutBeforeFinalSendCheckAndOtherSubscriberSurvives() throws Exception {
    var fixture=new DemoTestFixture(application);var browser=fixture.enter();var other=fixture.enter();UUID run=completedJob(fixture,browser),otherRun=completedJob(fixture,other);
    var captured=capture(browser,run);var otherCaptured=capture(other,otherRun);String unchanged=fixture.fingerprint(other.visitor().id());
    try(var client=HttpClient.newHttpClient()) {
      var response=client.send(request(browser,run),HttpResponse.BodyHandlers.ofInputStream());var otherResponse=client.send(request(other,otherRun),HttpResponse.BodyHandlers.ofInputStream());
      assertThat(response.statusCode()).isEqualTo(200);assertThat(otherResponse.statusCode()).isEqualTo(200);
      try(var input=response.body();var reader=new BufferedReader(new InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8));
          var otherInput=otherResponse.body();var otherReader=new BufferedReader(new InputStreamReader(otherInput,java.nio.charset.StandardCharsets.UTF_8))) {
        for(int i=0;i<4;i++){assertThat(frame(reader)).contains("event:progress");assertThat(frame(otherReader)).contains("event:progress");}
        try(var held=holdSends()) {
          sink.publish(event(run,"must-never-be-sent"),captured.owner());
          assertThat(http.perform(fixture.apply(post("/api/v1/auth/logout"),browser)).andReturn().getResponse().getStatus()).isIn(200,204);
          sink.publish(event(otherRun,"current-visitor-live"),otherCaptured.owner());
        }
        assertThat(readToEof(reader)).doesNotContain("must-never-be-sent","AUTH_UNAUTHENTICATED");
        String current=frame(otherReader);if(current.contains("heartbeat"))current=frame(otherReader);
        assertThat(current).contains("current-visitor-live");assertThat(authorization.allowed(otherCaptured)).isTrue();
        assertThat(sink.subscriptionCount()).isEqualTo(1);assertThat(fixture.fingerprint(other.visitor().id())).isEqualTo(unchanged);
        sink.discardGeneration(otherCaptured.owner().lease());readToEof(otherReader);
      }
    }
    assertThat(checks.getQueue()).isEmpty();
  }

  @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"REDIS_MISSING","REDIS_UNAVAILABLE","DB_UNAVAILABLE","IDLE_EXPIRED"})
  void idleStreamClosesWithoutPublishOrRenewalWhenAuthorityBecomesUnavailable(String failure) throws Exception {
    var fixture=new DemoTestFixture(application);var browser=fixture.enter();UUID run=completedJob(fixture,browser);var captured=capture(browser,run);
    var activity=jdbc.queryForObject("select last_activity_at from demo_workspace where id=?",java.sql.Timestamp.class,browser.workspace());var original=clock.instant();
    // Install the spy behavior before any concurrent stream reader; activate only after real HTTP data.
    var outage=new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
    String sessionId=browser.cookie().getValue();
    org.mockito.Mockito.doAnswer(call->{var unavailable=outage.get();if(unavailable!=null)throw unavailable;return call.callRealMethod();})
        .when(sessions).find(sessionId,PortalPurpose.DEMO);
    try(var client=HttpClient.newHttpClient()) {
      var response=client.send(request(browser,run),HttpResponse.BodyHandlers.ofInputStream());assertThat(response.statusCode()).isEqualTo(200);
      try(var input=response.body();var reader=new BufferedReader(new InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8))) {
        assertThat(frame(reader)).contains("event:progress");assertThat(authorization.allowed(captured)).isTrue();
        switch(failure) {
          case "REDIS_MISSING" -> application.getBean(org.springframework.data.redis.core.StringRedisTemplate.class).delete("jkxt:session:demo:g:"+browser.generation()+":"+browser.cookie().getValue());
          case "REDIS_UNAVAILABLE" -> outage.set(new org.springframework.data.redis.RedisConnectionFailureException("controlled Redis outage"));
          case "DB_UNAVAILABLE" -> outage.set(new org.springframework.jdbc.CannotGetJdbcConnectionException("controlled database outage"));
          case "IDLE_EXPIRED" -> clock.advance(Duration.ofMinutes(30));
          default -> throw new AssertionError(failure);
        }
        assertThat(readToEof(reader)).doesNotContain("AUTH_STATE_UNAVAILABLE","AUTH_UNAUTHENTICATED","DEMO_EXPIRED");
        assertThat(authorization.allowed(captured)).isFalse();
      }
    }finally{clock.set(original);outage.set(null);}
    assertThat(jdbc.queryForObject("select last_activity_at from demo_workspace where id=?",java.sql.Timestamp.class,browser.workspace())).isEqualTo(activity);
    assertThat(sink.subscriptionCount()).isZero();assertThat(checks.getQueue()).isEmpty();
  }

  @Test void boundedQueueOverflowClosesOnlyThatSubscriptionAndReclaimsScheduledHandles() throws Exception {
    var fixture=new DemoTestFixture(application);var browser=fixture.enter();UUID run=completedJob(fixture,browser);var captured=capture(browser,run);
    try(var held=holdSends()) {
      var emitter=sink.stream(captured);assertThat(sink.subscriptionCount()).isEqualTo(1);assertThat(checks.getQueue()).hasSize(2);
      for(int i=0;i<65;i++)sink.publish(event(run,"bounded-"+i),captured.owner());
      assertThat(sink.subscriptionCount()).isZero();assertThat(checks.getQueue()).isEmpty();
      var next=sink.stream(captured);emitter.complete();assertThat(sink.subscriptionCount()).isEqualTo(1);
      sink.discardGeneration(captured.owner().lease());next.complete();assertThat(sink.subscriptionCount()).isZero();assertThat(checks.getQueue()).isEmpty();
    }
  }

  @Test void registeredLifetimeIs120SecondsAndItsExpiryActionClosesTheRealResponse() throws Exception {
    var fixture=new DemoTestFixture(application);var browser=fixture.enter();UUID run=completedJob(fixture,browser);
    try(var client=HttpClient.newHttpClient()) {
      var response=client.send(request(browser,run),HttpResponse.BodyHandlers.ofInputStream());assertThat(response.statusCode()).isEqualTo(200);
      try(var input=response.body();var reader=new BufferedReader(new InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8))) {
        assertThat(frame(reader)).contains("event:progress");
        var lifetime=checks.getQueue().stream().filter(task->((Delayed)task).getDelay(TimeUnit.SECONDS)>110).findFirst().orElseThrow();
        assertThat(((Delayed)lifetime).getDelay(TimeUnit.SECONDS)).isBetween(110L,120L);
        // Invoke the registered expiry action deterministically; this is not a physical 120s timing claim.
        lifetime.run();assertThat(readToEof(reader)).doesNotContain("AUTH_");
      }
    }
    assertThat(sink.subscriptionCount()).isZero();assertThat(checks.getQueue()).isEmpty();
  }

  @Test void realPeerResetReleasesSubscriptionAndScheduledHandlesOnSendFailure() throws Exception {
    var fixture=new DemoTestFixture(application);var browser=fixture.enter();UUID run=completedJob(fixture,browser);var captured=capture(browser,run);
    try(var socket=new Socket("127.0.0.1",port)) {
      socket.setSoTimeout(5000);
      String request="GET /api/v1/schedule-jobs/"+run+"/events HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\nAccept: text/event-stream\r\nX-JKXT-Portal: demo\r\nX-JKXT-Session-Context: "+browser.context()+"\r\nCookie: "+browser.cookie().getName()+"="+browser.cookie().getValue()+"\r\n\r\n";
      socket.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.US_ASCII));socket.getOutputStream().flush();
      var reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),java.nio.charset.StandardCharsets.UTF_8));
      assertThat(reader.readLine()).contains("200");String line;while((line=reader.readLine())!=null && !line.isBlank()){}
      boolean progress=false;for(int i=0;i<12;i++){line=reader.readLine();if(line!=null && line.contains("event:progress")){progress=true;break;}}assertThat(progress).isTrue();
      socket.setSoLinger(true,0);
    }
    org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(8)).untilAsserted(()->{
      sink.publish(event(run,"peer-reset-probe"),captured.owner());assertThat(sink.subscriptionCount()).isZero();
    });
    assertThat(checks.getQueue()).isEmpty();assertThat(authorization.allowed(captured)).isTrue();
  }
  private UUID completedJob(DemoTestFixture fixture,DemoTestFixture.Browser browser) throws Exception {
    UUID task=fixture.smallTask(browser);
    var response=http.perform(fixture.apply(post("/api/v1/exam-tasks/"+task+"/schedule-jobs"),browser)).andReturn().getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
    UUID run=UUID.fromString(mapper.readTree(response.getContentAsByteArray()).path("jobId").asText());
    org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(35)).untilAsserted(()->{
      assertThat(jdbc.queryForObject("select status from schedule_run where id=?",String.class,run)).isEqualTo("succeeded");
      assertThat(jdbc.queryForObject("select released_at is not null from demo_solve_job where run_id=?",Boolean.class,run)).isTrue();
    });return run;
  }
  private HttpRequest request(DemoTestFixture.Browser browser,UUID run) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/schedule-jobs/"+run+"/events"))
        .timeout(Duration.ofSeconds(10)).header("Accept","text/event-stream").header("Origin",DemoTestFixture.ORIGIN)
        .header("X-JKXT-Portal","demo").header("X-JKXT-Session-Context",browser.context())
        .header("Cookie",browser.cookie().getName()+"="+browser.cookie().getValue()).GET().build();
  }
  private String frame(BufferedReader reader) throws Exception {
    var lines=new ArrayList<String>();String line;
    while((line=reader.readLine())!=null){if(line.isBlank())break;lines.add(line);}
    return String.join("\n",lines);
  }
  @Test void committedRealHttpStreamClosesAfterLogoutWithoutRenewingActivity() throws Exception {
    var fixture=new DemoTestFixture(application);var browser=fixture.enter();UUID run=completedJob(fixture,browser);
    var activity=jdbc.queryForObject("select last_activity_at from demo_workspace where id=?",java.sql.Timestamp.class,browser.workspace());
    try(var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build()) {
      var response=client.send(request(browser,run),HttpResponse.BodyHandlers.ofInputStream());assertThat(response.statusCode()).isEqualTo(200);
      try(var input=response.body();var reader=new BufferedReader(new InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8))) {
        assertThat(frame(reader)).contains("event:progress","\"runId\":\""+run+"\"");
        var logout=http.perform(fixture.apply(post("/api/v1/auth/logout"),browser)).andReturn().getResponse();
        assertThat(logout.getStatus()).isIn(200,204);
        var ended=new CompletableFuture<String>();Thread.ofVirtual().start(()->{try{ended.complete(reader.lines().collect(java.util.stream.Collectors.joining("\n")));}catch(Exception e){ended.completeExceptionally(e);}});
        assertThat(ended.get(8,TimeUnit.SECONDS)).doesNotContain("AUTH_UNAUTHENTICATED","SESSION_CONTEXT_CHANGED");
      }
    }
    assertThat(jdbc.queryForObject("select last_activity_at from demo_workspace where id=?",java.sql.Timestamp.class,browser.workspace())).isEqualTo(activity);
    org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(sink.subscriptionCount()).isZero());
  }

  @Test void sameSessionIdWithNewGenerationCannotTakeOverCapturedSubscription() throws Exception {
    var fixture=new DemoTestFixture(application);var browser=fixture.enter();UUID run=completedJob(fixture,browser);
    var captured=authorization.capture(run,sessions.find(browser.cookie().getValue(),PortalPurpose.DEMO).orElseThrow(),browser.org(),repository.workspace(browser.workspace(),false).lease());
    assertThat(authorization.allowed(captured)).isTrue();
    try(var client=HttpClient.newHttpClient()) {
      var response=client.send(request(browser,run),HttpResponse.BodyHandlers.ofInputStream());assertThat(response.statusCode()).isEqualTo(200);
      try(var input=response.body();var reader=new BufferedReader(new InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8))) {
        assertThat(frame(reader)).contains("event:progress");
        var reset=fixture.reset(browser,UUID.randomUUID());assertThat(reset.getResponse().getStatus()).isEqualTo(200);
        var current=fixture.browser(browser.visitor(),browser.operation(),reset,browser.cookie());
        assertThat(current.cookie().getValue()).isEqualTo(browser.cookie().getValue());assertThat(current.generation()).isEqualTo(browser.generation()+1);
        assertThat(authorization.allowed(captured)).isFalse();
        var ended=new CompletableFuture<Boolean>();Thread.ofVirtual().start(()->{try{while(reader.readLine()!=null){}ended.complete(true);}catch(Exception e){ended.completeExceptionally(e);}});
        assertThat(ended.get(8,TimeUnit.SECONDS)).isTrue();
        assertThat(http.perform(fixture.apply(get("/api/v1/schedule-jobs/"+run+"/events"),current)).andReturn().getResponse().getStatus()).isEqualTo(403);
        UUID currentRun=completedJob(fixture,current);
        var currentCapture=authorization.capture(currentRun,sessions.find(current.cookie().getValue(),PortalPurpose.DEMO).orElseThrow(),current.org(),repository.workspace(current.workspace(),false).lease());
        assertThat(authorization.allowed(currentCapture)).isTrue();
      }
    }
  }

  @Test void foreignRunForgedContextAndDeletedRunFailClosedWithCurrentPositive() throws Exception {
    var fixture=new DemoTestFixture(application);var browser=fixture.enter();var foreign=fixture.enter();UUID run=completedJob(fixture,browser);
    var envelope=sessions.find(browser.cookie().getValue(),PortalPurpose.DEMO).orElseThrow();var lease=repository.workspace(browser.workspace(),false).lease();
    var captured=authorization.capture(run,envelope,browser.org(),lease);assertThat(authorization.allowed(captured)).isTrue();
    assertThat(http.perform(fixture.apply(get("/api/v1/schedule-jobs/"+run+"/events"),foreign)).andReturn().getResponse().getStatus()).isEqualTo(403);
    var changed=new SessionEnvelope(envelope.principal(),envelope.audience(),envelope.authVersion(),"forged-context",envelope.csrfToken(),envelope.expiresAt(),envelope.workspaceId(),envelope.generation());
    assertThatThrownBy(()->authorization.capture(run,changed,browser.org(),lease)).isInstanceOf(AuthException.class);
    jdbc.update("delete from schedule_run where id=? and org_id=?",run,browser.org());assertThat(authorization.allowed(captured)).isFalse();
  }
}
