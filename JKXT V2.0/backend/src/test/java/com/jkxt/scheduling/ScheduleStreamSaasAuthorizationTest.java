package com.jkxt.scheduling;

import static org.assertj.core.api.Assertions.*;
import com.jkxt.security.PortalHttpFixture;
import com.jkxt.security.auth.*;
import com.jkxt.scheduling.service.*;
import com.jkxt.scheduling.api.ScheduleDtos.ProgressEvent;
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
import org.springframework.test.context.ActiveProfiles;

/** Real portal login and HTTP stream, with only uniquely owned SQL stream fixtures. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="jkxt.deploy.mode=saas")
@AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@ActiveProfiles("test")
class ScheduleStreamSaasAuthorizationTest {
  @LocalServerPort int port;
  @Autowired org.springframework.context.ApplicationContext application;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @Autowired SessionService sessions;
  @Autowired ScheduleStreamAuthorization authorization;
  @Autowired ScheduleProgressSink sink;
  @Autowired SessionAuthenticationFilter authenticationFilter;
  private PortalHttpFixture auth;
  private final List<UUID> organizations=new ArrayList<>();
  @BeforeEach void prepare(){auth=new PortalHttpFixture(application);}
  @AfterEach void cleanup(){
    sink.shutdown();
    for(UUID org:organizations)jdbc.update("update organization set status='active' where id=?",org);
    auth.close();
    for(UUID org:organizations)jdbc.update("delete from organization where id=?",org);
  }
  private UUID organization(){
    UUID id=UUID.randomUUID();jdbc.update("insert into organization(id,name,slug) values(?,?,?)",id,"Owned stream fixture","stream-"+id);organizations.add(id);return id;
  }
  private UUID run(UUID org){
    UUID task=UUID.randomUUID(),run=UUID.randomUUID();
    jdbc.update("insert into exam_task(id,org_id,task_no,name) values(?,?,?,?)",task,org,"stream-"+task.toString().substring(0,8),"Owned stream fixture");
    jdbc.update("insert into schedule_run(id,org_id,exam_task_id,status,stage,progress) values(?,?,?,'succeeded','fixture-ready',100)",run,org,task);
    sink.publish(new ProgressEvent(run,"succeeded","fixture-ready",100,"owned-stream-positive",java.time.Instant.now()));
    return run;
  }
  private HttpRequest request(PortalHttpFixture.AuthenticatedSession session,UUID run,UUID selected){
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/schedule-jobs/"+run+"/events"))
        .timeout(Duration.ofSeconds(10)).header("Accept","text/event-stream")
        .header("X-JKXT-Portal",session.purpose().name().toLowerCase(Locale.ROOT))
        .header("X-JKXT-Session-Context",session.context())
        .header("Cookie",session.cookie().getName()+"="+session.cookie().getValue());
    if(selected!=null)request.header("X-Tenant-Id",selected.toString());
    return request.GET().build();
  }
  private String frame(BufferedReader reader)throws Exception{
    var result=new ArrayList<String>();String line;while((line=reader.readLine())!=null){if(line.isBlank())break;result.add(line);}return String.join("\n",result);
  }
  private String eof(BufferedReader reader)throws Exception{
    var ended=new CompletableFuture<String>();Thread.ofVirtual().start(()->{try{ended.complete(reader.lines().collect(java.util.stream.Collectors.joining("\n")));}catch(Exception failure){ended.completeExceptionally(failure);}});
    return ended.get(8,TimeUnit.SECONDS);
  }
  private void currentHttpPositive(PortalHttpFixture.AuthenticatedSession session,UUID run,UUID selected)throws Exception{
    try(var client=HttpClient.newHttpClient()){
      var response=client.send(request(session,run,selected),HttpResponse.BodyHandlers.ofInputStream());
      assertThat(response.statusCode()).isEqualTo(200);
      try(var reader=new BufferedReader(new InputStreamReader(response.body(),java.nio.charset.StandardCharsets.UTF_8))){
        assertThat(frame(reader)).contains("event:progress","owned-stream-positive");
      }
    }
  }
  private void filterFailureBoundary(jakarta.servlet.DispatcherType dispatch,boolean committed,Throwable failure,boolean swallowed)throws Exception{
    var request=new org.springframework.mock.web.MockHttpServletRequest("GET","/api/v1/health");
    request.setDispatcherType(dispatch);
    var response=new org.springframework.mock.web.MockHttpServletResponse();response.setCommitted(committed);
    var calls=new java.util.concurrent.atomic.AtomicInteger();
    org.assertj.core.api.ThrowableAssert.ThrowingCallable invoke=()->authenticationFilter.doFilter(request,response,(req,res)->{
      calls.incrementAndGet();com.jkxt.common.tenant.TenantContext.set(UUID.randomUUID());
      req.setAttribute(AuthRequestAttributes.PRINCIPAL,new Object());
      req.setAttribute(AuthRequestAttributes.ENVELOPE,new Object());
      req.setAttribute(AuthRequestAttributes.DEMO_LEASE,new Object());
      if(failure instanceof IOException value)throw value;
      if(failure instanceof jakarta.servlet.ServletException value)throw value;
      if(failure instanceof RuntimeException value)throw value;
      if(failure instanceof Error value)throw value;
      throw new AssertionError("unsupported fixture throwable");
    });
    if(swallowed)assertThatCode(invoke).doesNotThrowAnyException();else assertThatThrownBy(invoke).isSameAs(failure);
    assertThat(calls).hasValue(1);
    assertThat(com.jkxt.common.tenant.TenantContext.get()).isEmpty();
    assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()).isNull();
    assertThat(request.getAttribute(AuthRequestAttributes.PRINCIPAL)).isNull();
    assertThat(request.getAttribute(AuthRequestAttributes.ENVELOPE)).isNull();
    assertThat(request.getAttribute(AuthRequestAttributes.DEMO_LEASE)).isNull();
  }
  private void committedFilterPolicyControls()throws Exception{
    var denied=new org.springframework.security.access.AccessDeniedException("fixture denial");
    var unauthenticated=new org.springframework.security.authentication.BadCredentialsException("fixture authentication denial");
    filterFailureBoundary(jakarta.servlet.DispatcherType.ASYNC,true,denied,true);
    filterFailureBoundary(jakarta.servlet.DispatcherType.ERROR,true,new jakarta.servlet.ServletException(denied),true);
    filterFailureBoundary(jakarta.servlet.DispatcherType.ASYNC,true,unauthenticated,true);
    filterFailureBoundary(jakarta.servlet.DispatcherType.ERROR,true,new jakarta.servlet.ServletException(unauthenticated),true);
    for(Throwable unrelated:List.of(new RuntimeException(denied),new jakarta.servlet.ServletException(new RuntimeException(denied)),
        new jakarta.servlet.ServletException("unrelated"),new IOException("transport failure"),new AssertionError("fixture error")))
      filterFailureBoundary(jakarta.servlet.DispatcherType.ASYNC,true,unrelated,false);
    filterFailureBoundary(jakarta.servlet.DispatcherType.ASYNC,false,denied,false);
    filterFailureBoundary(jakarta.servlet.DispatcherType.REQUEST,true,denied,false);
    var cycleA=new jakarta.servlet.ServletException();var cycleB=new jakarta.servlet.ServletException();
    cycleA.initCause(cycleB);cycleB.initCause(cycleA);
    filterFailureBoundary(jakarta.servlet.DispatcherType.ERROR,true,cycleA,false);
    Throwable deep=denied;for(int i=0;i<9;i++)deep=new jakarta.servlet.ServletException(deep);
    filterFailureBoundary(jakarta.servlet.DispatcherType.ASYNC,true,deep,false);
  }
  @Test void schoolStreamClosesWhenCurrentOwnedRolePermissionIsRemoved()throws Exception{
    committedFilterPolicyControls();
    UUID org=organization(),run=run(org),role=UUID.randomUUID();
    jdbc.update("insert into role(id,org_id,code,name,permissions) values(?,?,'ADMIN','Owned stream role','[\"tenant:read\",\"schedule:manage\"]'::jsonb)",role,org);
    var account=auth.account("ADMIN");jdbc.update("update app_user set org_id=? where id=?",org,account.id());
    var session=auth.login(PortalPurpose.SCHOOL,account);
    var captured=authorization.capture(run,sessions.find(session.cookie().getValue(),session.purpose()).orElseThrow(),org,null);
    assertThat(authorization.allowed(captured)).isTrue();
    try(var client=HttpClient.newHttpClient()){
      var response=client.send(request(session,run,null),HttpResponse.BodyHandlers.ofInputStream());assertThat(response.statusCode()).isEqualTo(200);
      try(var input=response.body();var reader=new BufferedReader(new InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8))){
        assertThat(frame(reader)).contains("event:progress","owned-stream-positive");
        jdbc.update("update role set permissions='[]'::jsonb where id=? and org_id=?",role,org);
        var denied=client.send(request(session,run,null),HttpResponse.BodyHandlers.ofString());
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.headers().firstValue("Content-Type")).hasValueSatisfying(value->assertThat(value).startsWith("application/json"));
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(denied.body()).path("code").asText()).isEqualTo("AUTH_FORBIDDEN");
        assertThat(eof(reader)).doesNotContain("AUTH_SESSION_INVALID","SCHEDULE_STREAM_FORBIDDEN");assertThat(authorization.allowed(captured)).isFalse();
      }
    }
    assertThat(sink.subscriptionCount()).isZero();
    jdbc.update("update role set permissions='[\"tenant:read\",\"schedule:manage\"]'::jsonb where id=? and org_id=?",role,org);
    var renewed=auth.login(PortalPurpose.SCHOOL,account);
    assertThat(authorization.allowed(authorization.capture(run,sessions.find(renewed.cookie().getValue(),renewed.purpose()).orElseThrow(),org,null))).isTrue();
    currentHttpPositive(renewed,run,null);
  }
  @Test void platformStreamCapturesEffectiveSelectedTenantAndClosesWhenThatTenantIsDeactivated()throws Exception{
    UUID selected=organization(),run=run(selected);var session=auth.login(PortalPurpose.PLATFORM,auth.superAdmin());
    var envelope=sessions.find(session.cookie().getValue(),session.purpose()).orElseThrow();
    assertThat(envelope.principal().orgId()).isNotEqualTo(selected);
    var captured=authorization.capture(run,envelope,selected,null);assertThat(authorization.allowed(captured)).isTrue();
    try(var client=HttpClient.newHttpClient()){
      var response=client.send(request(session,run,selected),HttpResponse.BodyHandlers.ofInputStream());assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.headers().firstValue("X-Tenant-Id")).contains(selected.toString());
      try(var input=response.body();var reader=new BufferedReader(new InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8))){
        assertThat(frame(reader)).contains("event:progress","owned-stream-positive");
        jdbc.update("update organization set status='suspended' where id=?",selected);
        assertThat(eof(reader)).doesNotContain("TENANT_UNAVAILABLE");assertThat(authorization.allowed(captured)).isFalse();
      }
    }
    assertThat(sink.subscriptionCount()).isZero();
    jdbc.update("update organization set status='active' where id=?",selected);
    assertThat(authorization.allowed(authorization.capture(run,envelope,selected,null))).isTrue();
    currentHttpPositive(session,run,selected);
  }
}
