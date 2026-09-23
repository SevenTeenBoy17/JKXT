package com.jkxt.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.assertj.core.api.Assertions.*;

/** HTTP bootstrap helpers never supply a server identity or repair negative requests. */
public final class DemoTestFixture {
  public static abstract class IntegrationBase {
    @org.springframework.boot.test.mock.mockito.SpyBean protected DemoSeedService seed;
    @org.springframework.boot.test.mock.mockito.SpyBean protected com.jkxt.security.auth.SessionService sessions;
    @org.springframework.boot.test.mock.mockito.SpyBean protected com.jkxt.security.auth.AuthService auth;
    @org.springframework.beans.factory.annotation.Autowired protected org.springframework.context.ApplicationContext application;
  }
  public static final String ORIGIN="http://localhost:8080";
  final MockMvc http;
  final ObjectMapper mapper;
  private org.springframework.context.ApplicationContext application;
  private final Map<UUID,Browser> established=new HashMap<>();
  private final Map<UUID,String> simulatedIps=new java.util.concurrent.ConcurrentHashMap<>();
  private static String freshIp(){String id=UUID.randomUUID().toString().replace("-","");return "2001:db8:"+String.join(":",id.substring(0,24).split("(?<=\\G.{4})"));}
  public String clientIp(Visitor visitor){return simulatedIps.computeIfAbsent(visitor.id(),ignored->freshIp());}
  private static final ThreadLocal<List<UUID>> created=ThreadLocal.withInitial(ArrayList::new);
  public DemoTestFixture(MockMvc http,ObjectMapper mapper) { this.http=http;this.mapper=mapper; }
  public DemoTestFixture(org.springframework.context.ApplicationContext application) {
    this(application.getBean(MockMvc.class),application.getBean(ObjectMapper.class));this.application=application;
  }
  public record Visitor(Cookie cookie,String context,UUID id,Instant expiresAt) {}
  public Visitor visitor() throws Exception {
    return visitor(freshIp());
  }
  /** Explicit peer address; this does not manufacture or trust any forwarded header. */
  public Visitor visitor(String ip) throws Exception {
    var result=http.perform(post("/api/v1/demo/visitor").with(r->{r.setRemoteAddr(ip);return r;}).header("Origin",ORIGIN).contentType("application/json").content("{}"))
        .andReturn();
    assertThat(result.getResponse().getStatus()).as("public demo visitor bootstrap").isEqualTo(200);
    var json=mapper.readTree(result.getResponse().getContentAsString());
    var visitor=new Visitor(result.getResponse().getCookie("JKXT_DEMO_VISITOR"),json.path("startupContext").asText(),
        UUID.fromString(json.path("visitorId").asText()),Instant.parse(json.path("expiresAt").asText()));
    simulatedIps.put(visitor.id(),ip);created.get().add(visitor.id());return visitor;
  }
  public MvcResult start(Visitor visitor,UUID operation,String intent) throws Exception {
    return http.perform(post("/api/v1/demo/sessions").with(r->{r.setRemoteAddr(clientIp(visitor));return r;}).header("Origin",ORIGIN)
        .header("X-JKXT-Startup-Context",visitor.context()).cookie(visitor.cookie()).contentType("application/json")
        .content(mapper.writeValueAsString(Map.of("operationId",operation,"intent",intent)))).andReturn();
  }
  public record Browser(Visitor visitor,UUID operation,Cookie cookie,String context,String csrf,UUID workspace,UUID org,long generation) {}
  public Browser enter() throws Exception {
    var visitor=visitor();var operation=UUID.randomUUID();
    var response=start(visitor,operation,"START");
    assertThat(response.getResponse().getStatus()).isEqualTo(200);
    return browser(visitor,operation,response,null);
  }
  /** Real Quick creation/import with one exam; the solver still reads the entire owned roster. */
  public UUID smallTask(Browser browser) throws Exception {
    var created=http.perform(apply(post("/api/v1/exam-tasks"),browser).contentType("application/json")
        .content("{\"name\":\"合成单场排考\",\"source\":\"quick\"}")).andReturn().getResponse();
    assertThat(created.getStatus()).isEqualTo(200);
    UUID task=UUID.fromString(mapper.readTree(created.getContentAsByteArray()).path("id").asText());
    var jdbc=application.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
    String room=jdbc.queryForObject("select code from classroom where org_id=? order by code limit 1",String.class,browser.org());
    var file=DemoBusinessFlowIntegrationTest.workbook(new String[]{"subjectName","grade","examDate","startTime","endTime","roomCode","seatDemand","supervisorsPerRoom"},
        List.<String[]>of(new String[]{"语文","高一","2028-06-01","09:00","10:30",room,"20","2"}));
    var imported=http.perform(apply(multipart("/api/v1/import/exams/commit").file(file).param("taskId",task.toString()),browser)).andReturn().getResponse();
    assertThat(imported.getStatus()).isEqualTo(200);return task;
  }
  public Browser browser(Visitor visitor,UUID operation,MvcResult result,Cookie prior) throws Exception {
    var json=mapper.readTree(result.getResponse().getContentAsString());
    Cookie cookie=result.getResponse().getCookie("JKXT_DEMO_SESSION");
    var browser=new Browser(visitor,operation,cookie==null?prior:cookie,json.path("sessionContext").asText(),json.path("csrfToken").asText(),
        UUID.fromString(json.path("workspaceId").asText()),UUID.fromString(json.path("org").path("id").asText()),json.path("demo").path("generation").asLong());
    established.put(browser.workspace(),browser);return browser;
  }
  /** Real HTTP solver admission and actual owned completion, including worker-slot release. */
  public UUID solve(Browser browser,UUID task) throws Exception {
    var response=http.perform(apply(post("/api/v1/exam-tasks/"+task+"/schedule-jobs"),browser)).andReturn().getResponse();
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    UUID run=UUID.fromString(mapper.readTree(response.getContentAsByteArray()).path("runId").asText());
    var jdbc=application.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
    org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(()->
        assertThat(jdbc.queryForObject("select status from schedule_run where id=?",String.class,run)).isEqualTo("succeeded"));
    org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(()->
        assertThat(jdbc.queryForObject("select released_at is not null from demo_solve_job where run_id=?",Boolean.class,run)).isTrue());
    return run;
  }
  public com.fasterxml.jackson.databind.JsonNode assignments(Browser browser,UUID task) throws Exception {
    var response=http.perform(apply(get("/api/v1/exam-tasks/"+task+"/assignments"),browser)).andReturn().getResponse();
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    return mapper.readTree(response.getContentAsByteArray());
  }
  public com.fasterxml.jackson.databind.JsonNode storedSnapshot(Browser browser,UUID run,long revision) throws Exception {
    var jdbc=application.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
    return mapper.readTree(jdbc.queryForObject("select payload::text from demo_schedule_export_snapshot where workspace_id=? and generation=? and org_id=? and run_id=? and export_revision=?",
        String.class,browser.workspace(),browser.generation(),browser.org(),run,revision));
  }
  /** Unique synthetic retention markers in actual owned DB inputs, not fabricated solver output. */
  public String markSolveInputs(Browser browser,UUID task) {
    String marker="ret-"+UUID.randomUUID().toString().replace("-","").substring(0,24);var jdbc=application.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
    jdbc.update("update teacher set name=?,leave_windows='[{\"start\":\"2035-01-02T09:00:00\",\"end\":\"2035-01-02T10:00:00\"}]'::jsonb where id=(select id from teacher where org_id=? order by teacher_no limit 1)",marker+"-teacher",browser.org());
    jdbc.update("update exam set subject_name=? where org_id=? and exam_task_id=?",marker+"-exam",browser.org(),task);
    return marker;
  }
  public MockHttpServletRequestBuilder apply(MockHttpServletRequestBuilder request,Browser browser) {
    return applySession(request,browser).cookie(browser.visitor().cookie());
  }
  /** Explicit business-only credential helper for visitor-Cookie negative requests. */
  public MockHttpServletRequestBuilder applySession(MockHttpServletRequestBuilder request,Browser browser) {
    return request.with(r->{r.setRemoteAddr(clientIp(browser.visitor()));return r;}).header("Origin",ORIGIN).header("X-JKXT-Portal","demo").header("X-JKXT-Session-Context",browser.context())
        .header("X-JKXT-CSRF",browser.csrf()).cookie(browser.cookie());
  }
  public static void awaitBlockedQuery(org.springframework.jdbc.core.JdbcTemplate jdbc,String fragment) {
    org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(()->
        assertThat(jdbc.queryForObject("select count(*) from pg_stat_activity where pid<>pg_backend_pid() and datname=current_database() and usename=current_user and wait_event_type='Lock' and query like ?",Integer.class,"%"+fragment+"%")).isPositive());
  }
  public static final class CleanupLog implements AutoCloseable {
    private final ch.qos.logback.classic.Logger logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(DemoCleanupService.class);
    private final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> events=new ch.qos.logback.core.read.ListAppender<>();
    public CleanupLog(){events.start();logger.addAppender(events);}
    public List<String> messages(){return events.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();}
    public boolean hasThrowable(){return events.list.stream().anyMatch(e->e.getThrowableProxy()!=null);}
    @Override public void close(){logger.detachAppender(events);events.stop();}
  }
  public MvcResult reset(Browser browser,UUID operation) throws Exception {
    return http.perform(apply(post("/api/v1/demo/workspace/reset"),browser).contentType("application/json")
        .content(mapper.writeValueAsString(Map.of("operationId",operation,"confirmed",true)))).andReturn();
  }
  public DemoWorkspaceService.WorkspaceGrant reset(UUID workspaceId,long generation) {
    var browser=established.get(workspaceId);
    if(browser==null || application==null)throw new IllegalStateException("Fixture has no authenticated visitor for this workspace");
    var repository=application.getBean(DemoWorkspaceRepository.class);var w=repository.workspace(workspaceId,false);
    if(!w.visitor().equals(browser.visitor().id()))throw new IllegalStateException("Fixture ownership changed");
    return application.getBean(DemoWorkspaceService.class).reset(new DemoLease(workspaceId,browser.org(),generation,w.expires()),UUID.randomUUID().toString());
  }
  /** Independent database row and controlled-file digest, not the lifecycle service's counters. */
  public String fingerprint(UUID visitorId) throws Exception {
    if(application==null)throw new IllegalStateException("Application-backed fixture required");
    var jdbc=application.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
    var orgs=jdbc.queryForList("select g.org_id from demo_generation g join demo_workspace w on w.id=g.workspace_id where w.visitor_id=?",UUID.class,visitorId);
    var fragments=new TreeSet<String>();
    var tables=jdbc.queryForList("select table_name from information_schema.columns where table_schema='public' and column_name='org_id'",String.class);
    for(String table:tables) {
      if(!table.matches("[a-z_]+") || (table.startsWith("demo_") && !table.equals("demo_schedule_export_snapshot")))continue;
      for(UUID org:orgs)jdbc.query("select to_jsonb(t)::text from \""+table+"\" t where org_id=?",rs->{while(rs.next())fragments.add(table+":"+rs.getString(1));return null;},org);
    }
    for(UUID org:orgs)jdbc.query("select to_jsonb(t)::text from organization t where id=?",rs->{while(rs.next())fragments.add("organization:"+rs.getString(1));return null;},org);
    var workspaceIds=jdbc.queryForList("select id from demo_workspace where visitor_id=?",UUID.class,visitorId);
    var root=java.nio.file.Path.of(application.getBean(DemoProperties.class).getStorageRoot());
    for(UUID workspace:workspaceIds) {
      var path=root.resolve(workspace.toString());
      if(java.nio.file.Files.exists(path))try(var files=java.nio.file.Files.walk(path)) {
        for(var file:files.filter(p->java.nio.file.Files.isRegularFile(p,java.nio.file.LinkOption.NOFOLLOW_LINKS)).toList())fragments.add(root.relativize(file)+":"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(file))));
      }
    }
    return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(String.join("\n",fragments).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }
  public static void retire(org.springframework.jdbc.core.JdbcTemplate jdbc) {
    for(UUID visitor:created.get())jdbc.update("update demo_workspace set state='EXPIRED',cleanup_after=now() where visitor_id=?",visitor);
    created.remove();
  }
  public static final class MutableClock extends Clock {
    private final AtomicReference<Instant> instant=new AtomicReference<>(Instant.now());
    public void set(Instant value) { instant.set(value); }
    public void advance(Duration amount) { instant.updateAndGet(v->v.plus(amount)); }
    @Override public Instant instant() { return instant.get(); }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId ignored) { return this; }
  }
  @TestConfiguration public static class Time implements org.springframework.scheduling.annotation.SchedulingConfigurer {
    @Bean @Primary MutableClock demoTestClock() { return new MutableClock(); }
    @Bean ManualScheduler taskScheduler(MutableClock clock) { return new ManualScheduler(clock); }
    @Override public void configureTasks(org.springframework.scheduling.config.ScheduledTaskRegistrar tasks) {
      // Bypass the default router's physical Clock as well as its asynchronous executor.
      tasks.setTaskScheduler(taskScheduler(demoTestClock()));
    }
  }
  /** Captures the real @Scheduled callback without an uncontrolled physical-clock thread. */
  public static final class ManualScheduler implements org.springframework.scheduling.TaskScheduler {
    private final Clock clock;private final List<Registration> registrations=new ArrayList<>();
    public static final class Registration {
      final Runnable action;final Instant registered,first;final Duration delay;Instant next;final ManualFuture future;
      Registration(Runnable action,Instant registered,Instant first,Duration delay){this.action=action;this.registered=registered;this.first=first;this.next=first;this.delay=delay;this.future=new ManualFuture(action);}
    }
    private static final class ManualFuture extends java.util.concurrent.FutureTask<Void> implements java.util.concurrent.ScheduledFuture<Void> {
      ManualFuture(Runnable task){super(task,null);}
      public long getDelay(java.util.concurrent.TimeUnit unit){return 0;}
      public int compareTo(java.util.concurrent.Delayed other){return 0;}
    }
    ManualScheduler(Clock clock){this.clock=clock;}
    @Override public Clock getClock(){return clock;}
    public List<Registration> registrations(){return List.copyOf(registrations);}
    public void fireDue(){for(var task:registrations)if(!task.future.isCancelled() && !clock.instant().isBefore(task.next)){task.next=clock.instant().plus(task.delay);task.action.run();}}
    @Override public java.util.concurrent.ScheduledFuture<?> scheduleWithFixedDelay(Runnable task,Instant start,Duration delay){var registration=new Registration(task,clock.instant(),start,delay);registrations.add(registration);return registration.future;}
    @Override public java.util.concurrent.ScheduledFuture<?> scheduleWithFixedDelay(Runnable task,Duration delay){return scheduleWithFixedDelay(task,clock.instant(),delay);}
    @Override public java.util.concurrent.ScheduledFuture<?> schedule(Runnable task,org.springframework.scheduling.Trigger trigger){throw new UnsupportedOperationException("Unexpected test scheduling mode");}
    @Override public java.util.concurrent.ScheduledFuture<?> schedule(Runnable task,Instant start){throw new UnsupportedOperationException("Unexpected test scheduling mode");}
    @Override public java.util.concurrent.ScheduledFuture<?> scheduleAtFixedRate(Runnable task,Instant start,Duration period){throw new UnsupportedOperationException("Unexpected test scheduling mode");}
    @Override public java.util.concurrent.ScheduledFuture<?> scheduleAtFixedRate(Runnable task,Duration period){throw new UnsupportedOperationException("Unexpected test scheduling mode");}
  }
}
