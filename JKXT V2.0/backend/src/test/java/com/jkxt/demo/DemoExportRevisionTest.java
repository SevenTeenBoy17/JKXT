package com.jkxt.demo;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.scheduling.api.ScheduleEditDtos.*;
import com.jkxt.scheduling.service.ScheduleEditService;
import java.time.Duration;
import java.util.UUID;
import java.util.*;
import java.time.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.mock.web.MockHttpServletResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"jkxt.auth.captcha-debug=false","jkxt.notify.email.enabled=false","jkxt.notify.sms.enabled=false"})
@AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@ActiveProfiles("test") @Import(DemoTestFixture.Time.class)
class DemoExportRevisionTest extends DemoTestFixture.IntegrationBase {
  @Autowired MockMvc http; @Autowired ObjectMapper mapper;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @Autowired ScheduleEditService edits;
  @org.springframework.boot.test.mock.mockito.SpyBean DemoScheduleExportSnapshotService snapshots;
  @org.springframework.boot.test.mock.mockito.SpyBean JdbcWorkspaceFence fence;
  @Autowired DemoWorkspaceRepository workspaces;
  @Autowired DemoTestFixture.MutableClock clock;
  DemoScheduleExportSnapshotService snapshotSpy(){return org.springframework.test.util.AopTestUtils.getUltimateTargetObject(snapshots);}
  @AfterEach void retire(){com.jkxt.common.tenant.TenantContext.clear();DemoTestFixture.retire(jdbc);}

  @Test void internalEditAndPreviewCallsRequireActualServerLease() {
    UUID id=UUID.randomUUID();
    for(Runnable operation:java.util.List.<Runnable>of(
        ()->edits.checkConflicts(id,new ConflictCheckRequest(id,id)),
        ()->edits.patch(id,new SchedulePatchRequest(id,id,0,"test")),
        ()->edits.undo(id),()->edits.redo(id))) {
      assertThatThrownBy(operation::run).isInstanceOfSatisfying(com.jkxt.security.auth.AuthException.class,
          failure->assertThat(failure.getCode()).isEqualTo("DEMO_LEASE_REQUIRED"));
    }
  }

  @Test void initialSuccessfulOutputHasExactVersionAndRequiresEditToken() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();UUID task=f.smallTask(b);
    var start=http.perform(f.apply(post("/api/v1/exam-tasks/"+task+"/schedule-jobs"),b)).andReturn().getResponse();
    assertThat(start.getStatus()).isEqualTo(200);
    UUID run=UUID.fromString(mapper.readTree(start.getContentAsByteArray()).path("jobId").asText());
    org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(()->
        assertThat(jdbc.queryForObject("select status from schedule_run where id=?",String.class,run)).isEqualTo("succeeded"));
    var read=http.perform(f.apply(get("/api/v1/exam-tasks/"+task+"/assignments"),b)).andReturn().getResponse();
    assertThat(read.getStatus()).isEqualTo(200);var displayed=mapper.readTree(read.getContentAsByteArray());
    assertThat(displayed.path("stats").has("exportRevision")).isTrue();
    assertThat(displayed.path("stats").path("exportRevision").asLong()).isZero();
    assertThat(displayed.path("stats").path("outputToken").asText()).isEqualTo("\""+run+":0\"");
    var row=displayed.path("rows").get(0);
    var rejected=http.perform(f.apply(patch("/api/v1/tasks/"+task+"/schedule"),b).contentType("application/json")
        .content(mapper.writeValueAsString(java.util.Map.of("assignmentId",row.path("assignmentId").asText(),"teacherId",row.path("teacherId").asText(),"expectedRevision",0))))
        .andReturn().getResponse();
    assertThat(rejected.getStatus()).isEqualTo(428);
    assertThat(rejected.getContentAsString()).contains("DEMO_OUTPUT_TOKEN_REQUIRED");
  }

  record Flow(DemoTestFixture fixture,DemoTestFixture.Browser browser,UUID task,UUID run,JsonNode row,UUID spare) {}
  Flow flow(String role) throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();UUID task=f.smallTask(b),run=f.solve(b,task);
    JsonNode row=java.util.stream.StreamSupport.stream(f.assignments(b,task).path("rows").spliterator(),false)
        .filter(r->r.path("role").asText().equals(role)).findFirst().orElseThrow();
    UUID spare=jdbc.queryForObject("select id from teacher where org_id=? and id not in(select teacher_id from schedule_assignment where schedule_run_id=?) order by teacher_no limit 1",UUID.class,b.org(),run);
    var flow=new Flow(f,b,task,run,row,spare);
    updateTeacher(flow,spare,validTeacher(flow,spare,role));
    return flow;
  }
  JsonNode display(Flow f) throws Exception {return f.fixture().assignments(f.browser(),f.task());}
  MockHttpServletResponse mutate(Flow f,String action,UUID teacher,String token) throws Exception {
    var request=action.equals("patch")?patch("/api/v1/tasks/"+f.task()+"/schedule"):post("/api/v1/tasks/"+f.task()+"/schedule/"+action);
    if(token!=null)request.header("If-Match",token);
    if(action.equals("patch"))request.contentType("application/json").content(mapper.writeValueAsString(Map.of(
        "assignmentId",f.row().path("assignmentId").asText(),"teacherId",teacher.toString(),
        "expectedRevision",display(f).path("stats").path("revisionCursor").asInt())));
    return http.perform(f.fixture().apply(request,f.browser())).andReturn().getResponse();
  }
  JsonNode success(Flow f,String action,UUID teacher) throws Exception {
    var before=display(f);long version=before.path("stats").path("exportRevision").asLong();
    var response=mutate(f,action,teacher,before.path("stats").path("outputToken").asText());
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);var result=mapper.readTree(response.getContentAsByteArray());
    assertThat(result.path("exportRevision").asLong()).isEqualTo(version+1);
    assertThat(result.path("outputToken").asText()).isEqualTo("\""+f.run()+":"+(version+1)+"\"");
    assertThat(display(f).path("stats").path("outputToken").asText()).isEqualTo(result.path("outputToken").asText());
    assertThat(f.fixture().storedSnapshot(f.browser(),f.run(),version+1).path("rows")).isEqualTo(display(f).path("rows"));
    var exactRow=java.util.stream.StreamSupport.stream(f.fixture().storedSnapshot(f.browser(),f.run(),version+1).path("rows").spliterator(),false)
        .filter(row->row.path("assignmentId").asText().equals(f.row().path("assignmentId").asText())).findFirst().orElseThrow();
    assertThat(result.path("assignment")).isEqualTo(exactRow);
    if(action.equals("patch")){assertThat(result.path("teacherId")).isEqualTo(exactRow.path("teacherId"));assertThat(result.path("teacherName")).isEqualTo(exactRow.path("teacherName"));}
    return result;
  }
  Map<String,Object> validTeacher(Flow f,UUID teacher,String role) {
    var row=jdbc.queryForMap("select teacher_no,name from teacher where org_id=? and id=?",f.browser().org(),teacher);
    var body=new LinkedHashMap<String,Object>();body.put("teacherNo",row.get("teacher_no"));body.put("name",row.get("name"));
    body.put("status","active");body.put("qualifications",role.equals("main")?List.of("INVIGILATOR","CHIEF"):List.of("INVIGILATOR"));
    body.put("leaveWindows",List.of());body.put("dutyWindows",List.of());body.put("isHomeroom",false);return body;
  }
  void updateTeacher(Flow f,UUID teacher,Map<String,Object> body) throws Exception {
    var response=http.perform(f.fixture().apply(put("/api/v1/teachers/"+teacher),f.browser()).contentType("application/json")
        .content(mapper.writeValueAsString(body))).andReturn().getResponse();
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
  }
  JsonNode preview(Flow f,UUID teacher) throws Exception {
    var response=http.perform(f.fixture().apply(post("/api/v1/tasks/"+f.task()+"/schedule/check-conflicts"),f.browser()).contentType("application/json")
        .content(mapper.writeValueAsString(Map.of("assignmentId",f.row().path("assignmentId").asText(),"teacherId",teacher)))).andReturn().getResponse();
    assertThat(response.getStatus()).isEqualTo(200);return mapper.readTree(response.getContentAsByteArray());
  }
  void rejected(Flow f,String action,UUID teacher,String token,int status,String code) throws Exception {
    String before=f.fixture().fingerprint(f.browser().visitor().id());
    var response=mutate(f,action,teacher,token);assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
    assertThat(response.getContentAsString()).contains(code);
    assertThat(f.fixture().fingerprint(f.browser().visitor().id())).isEqualTo(before);
  }
  @Test void immutableBranchVersionsAndAbaTokensAndExactHistory() throws Exception {
    var f=flow("main");String initial=display(f).path("stats").path("outputToken").asText();
    var a=success(f,"patch",f.spare());assertThat(a.path("revisionCursor").asInt()).isEqualTo(1);
    var historical=f.fixture().storedSnapshot(f.browser(),f.run(),1);
    assertThat(success(f,"undo",f.spare()).path("revisionCursor").asInt()).isZero();
    rejected(f,"patch",f.spare(),initial,412,"DEMO_OUTPUT_TOKEN_STALE");
    UUID other=jdbc.queryForObject("select id from teacher where org_id=? and id<>? and id not in(select teacher_id from schedule_assignment where schedule_run_id=?) order by teacher_no limit 1",UUID.class,f.browser().org(),f.spare(),f.run());
    updateTeacher(f,other,validTeacher(f,other,"main"));
    var b=success(f,"patch",other);assertThat(b.path("revisionCursor").asInt()).isEqualTo(1);assertThat(b.path("exportRevision").asLong()).isEqualTo(3);
    var changed=validTeacher(f,f.spare(),"main");changed.put("name","后来更名的教师");updateTeacher(f,f.spare(),changed);
    assertThat(f.fixture().storedSnapshot(f.browser(),f.run(),1)).isEqualTo(historical);
    assertThat(display(f).path("rows")).isEqualTo(f.fixture().storedSnapshot(f.browser(),f.run(),3).path("rows"));
    assertArtifacts(f,1,historical.path("rows"));assertArtifacts(f,3,display(f).path("rows"));
    assertThat(success(f,"undo",other).path("revisionCursor").asInt()).isZero();
    assertThat(success(f,"redo",other).path("revisionCursor").asInt()).isEqualTo(1);
    assertArtifacts(f,5,display(f).path("rows"));
    assertThat(jdbc.queryForObject("select count(*) from demo_schedule_export_snapshot where run_id=?",Integer.class,f.run())).isEqualTo(6);
    assertThatThrownBy(()->jdbc.update("update demo_schedule_export_snapshot set schema_version=1 where run_id=?",f.run()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }
  @ParameterizedTest @CsvSource({"patch,main","undo,main","redo,main","patch,assistant","undo,assistant","redo,assistant"})
  void everyDirectionRechecksIsolatedCurrentHardPredicates(String direction,String role) throws Exception {
    var f=flow(role);UUID original=UUID.fromString(f.row().path("teacherId").asText());
    UUID target=direction.equals("undo")?original:f.spare();
    if(!direction.equals("patch"))success(f,"patch",f.spare());
    if(direction.equals("redo"))success(f,"undo",f.spare());
    String token=display(f).path("stats").path("outputToken").asText();
    var base=validTeacher(f,target,role);LocalDate date=LocalDate.parse(f.row().path("examDate").asText());
    LocalDateTime start=date.atTime(LocalTime.parse(f.row().path("startTime").asText())),end=date.atTime(LocalTime.parse(f.row().path("endTime").asText()));
    var changes=new LinkedHashMap<String,Map<String,Object>>();
    changes.put("H2_TEACHER_UNAVAILABLE",Map.of("status","disabled"));
    changes.put("H2_TEACHER_INVIGILATOR_REQUIRED",Map.of("qualifications",List.of()));
    changes.put("CHIEF_ONLY",Map.of("qualifications",List.of("CHIEF")));
    if(role.equals("main"))changes.put("H2_TEACHER_CHIEF_REQUIRED",Map.of("qualifications",List.of("INVIGILATOR")));
    changes.put("H2_TEACHER_QUALIFICATION_EXPIRED",Map.of("qualificationExpiry",date.minusDays(1).toString()));
    changes.put("H2_TEACHER_LEAVE_WINDOW",Map.of("leaveWindows",List.of(Map.of("start",start.toString(),"end",end.toString()))));
    changes.put("H2_TEACHER_DUTY_WINDOW",Map.of("dutyWindows",List.of(Map.of("start",start.plusMinutes(1).toString(),"end",end.toString()))));
    changes.put("ADJACENT_DUTY",Map.of("dutyWindows",List.of(Map.of("start",start.toString(),"end",start.plusMinutes(30).toString()),Map.of("start",start.plusMinutes(30).toString(),"end",end.toString()))));
    for(var test:changes.entrySet()) {
      var body=new LinkedHashMap<>(base);body.putAll(test.getValue());updateTeacher(f,target,body);
      String predicate=test.getKey().equals("CHIEF_ONLY")?"H2_TEACHER_INVIGILATOR_REQUIRED":test.getKey().equals("ADJACENT_DUTY")?"H2_TEACHER_DUTY_WINDOW":test.getKey();
      var check=preview(f,target);assertThat(check.path("allowed").asBoolean()).isFalse();assertThat(check.path("conflicts").get(0).path("code").asText()).isEqualTo(predicate);
      rejected(f,direction,target,token,409,"SCHEDULE_CONFLICT");
    }
    updateTeacher(f,target,base);
    UUID otherAssignment=jdbc.queryForObject("select id from schedule_assignment where schedule_run_id=? and id<>? limit 1",UUID.class,f.run(),UUID.fromString(f.row().path("assignmentId").asText()));
    UUID otherTeacher=jdbc.queryForObject("select teacher_id from schedule_assignment where id=?",UUID.class,otherAssignment);
    jdbc.update("update schedule_assignment set teacher_id=? where id=?",target,otherAssignment);
    assertThat(preview(f,target).path("conflicts").get(0).path("code").asText()).isEqualTo("H1_TEACHER_TIME_OVERLAP");
    rejected(f,direction,target,token,409,"SCHEDULE_CONFLICT");
    jdbc.update("update schedule_assignment set teacher_id=? where id=?",otherTeacher,otherAssignment);
    UUID klass=jdbc.queryForObject("select id from school_class where org_id=? and homeroom_teacher_id is distinct from ? order by code limit 1",UUID.class,f.browser().org(),target);
    jdbc.update("insert into exam_class(org_id,exam_id,class_id) values(?,?,?) on conflict do nothing",f.browser().org(),UUID.fromString(f.row().path("examId").asText()),klass);
    var ownHomeroom=new LinkedHashMap<>(base);ownHomeroom.put("homeroomClassId",klass);ownHomeroom.put("isHomeroom",true);updateTeacher(f,target,ownHomeroom);
    assertThat(preview(f,target).path("conflicts").get(0).path("code").asText()).isEqualTo("H3_HOMEROOM_AVOID");rejected(f,direction,target,token,409,"SCHEDULE_CONFLICT");
    updateTeacher(f,target,base);Object oldHomeroom=jdbc.queryForObject("select homeroom_teacher_id from school_class where id=?",UUID.class,klass);
    jdbc.update("update school_class set homeroom_teacher_id=? where id=?",target,klass);
    assertThat(preview(f,target).path("conflicts").get(0).path("code").asText()).isEqualTo("H3_HOMEROOM_AVOID");rejected(f,direction,target,token,409,"SCHEDULE_CONFLICT");
    jdbc.update("update school_class set homeroom_teacher_id=? where id=?",oldHomeroom,klass);
    var valid=new LinkedHashMap<>(base);valid.put("qualificationExpiry",date.toString());
    valid.put("leaveWindows",List.of(Map.of("start",end.toString(),"end",end.plusHours(1).toString())));
    valid.put("dutyWindows",List.of(Map.of("start",start.toString(),"end",end.toString())));updateTeacher(f,target,valid);
    var allowed=preview(f,target);assertThat(allowed.path("allowed").asBoolean()).as(allowed.toString()).isTrue();
    UUID unrelated=jdbc.queryForObject("select id from school_class where org_id=? and id<>? order by code limit 1",UUID.class,f.browser().org(),klass);
    var later=new LinkedHashMap<>(valid);later.put("qualificationExpiry",date.plusDays(1).toString());later.put("homeroomClassId",unrelated);later.put("isHomeroom",true);updateTeacher(f,target,later);
    jdbc.update("update schedule_assignment set teacher_id=?,start_time=?,end_time=? where id=?",target,java.sql.Time.valueOf(start.minusHours(1).toLocalTime()),java.sql.Time.valueOf(start.toLocalTime()),otherAssignment);
    assertThat(preview(f,target).path("allowed").asBoolean()).as("later expiry, unrelated homeroom and endpoint-adjacent assignment remain eligible").isTrue();
    jdbc.update("update schedule_assignment set teacher_id=?,start_time=?,end_time=? where id=?",otherTeacher,java.sql.Time.valueOf(start.toLocalTime()),java.sql.Time.valueOf(end.toLocalTime()),otherAssignment);
    updateTeacher(f,target,valid);
    success(f,direction,target);
  }
  @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"patch","undo","redo"})
  void tokenGrammarAndMissingTokenNeverChangeAnything(String action) throws Exception {
    var f=flow("main");if(!action.equals("patch"))success(f,"patch",f.spare());if(action.equals("redo"))success(f,"undo",f.spare());
    rejected(f,action,f.spare(),null,428,"DEMO_OUTPUT_TOKEN_REQUIRED");
    var current=display(f).path("stats");String exact=current.path("outputToken").asText();long version=current.path("exportRevision").asLong();
    for(String bad:List.of("*","W/"+exact,"\""+UUID.randomUUID()+":"+version+"\"","\""+f.run()+":9999\"",exact+",\"other\"","malformed"))
      rejected(f,action,f.spare(),bad,412,"DEMO_OUTPUT_TOKEN_STALE");
  }
  @Test void previewIsAdvisoryAndSnapshotFailureRollsBackWholeMutation() throws Exception {
    var f=flow("main");assertThat(preview(f,f.spare()).path("allowed").asBoolean()).isTrue();
    var body=validTeacher(f,f.spare(),"main");body.put("qualifications",List.of());updateTeacher(f,f.spare(),body);
    rejected(f,"patch",f.spare(),display(f).path("stats").path("outputToken").asText(),409,"SCHEDULE_CONFLICT");
    updateTeacher(f,f.spare(),validTeacher(f,f.spare(),"main"));String before=f.fixture().fingerprint(f.browser().visitor().id());
    org.mockito.Mockito.doAnswer(call->{
      assertThat(jdbc.queryForObject("select teacher_id from schedule_assignment where id=?",UUID.class,UUID.fromString(f.row().path("assignmentId").asText()))).isEqualTo(f.spare());
      throw new IllegalStateException("TEST_SNAPSHOT_STORAGE_FAILURE");
    }).when(snapshotSpy()).append(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    try {
      try {var response=mutate(f,"patch",f.spare(),display(f).path("stats").path("outputToken").asText());assertThat(response.getStatus()).isEqualTo(500);}
      catch(jakarta.servlet.ServletException expected){assertThat(expected).hasRootCauseMessage("TEST_SNAPSHOT_STORAGE_FAILURE");}
    }finally{org.mockito.Mockito.reset(snapshotSpy());}
    assertThat(f.fixture().fingerprint(f.browser().visitor().id())).isEqualTo(before);
  }
  byte[] export(Flow f,long version,String format) throws Exception {
    var response=http.perform(f.fixture().apply(get("/api/v1/exam-tasks/"+f.task()+"/export").param("format",format)
        .param("runId",f.run().toString()).param("revision",Long.toString(version)),f.browser())).andReturn().getResponse();
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);return response.getContentAsByteArray();
  }
  void assertArtifacts(Flow f,long version,JsonNode rows) throws Exception {
    var metadata=com.jkxt.tasks.ExamTaskExportArtifactIntegrationTest.assertWorkbook(export(f,version,"xlsx"),rows);
    assertThat(metadata.get("任务")).isEqualTo(f.task().toString());assertThat(metadata.get("运行")).isEqualTo(f.run().toString());
    assertThat(metadata.get("导出版本")).isEqualTo(Long.toString(version));assertThat(metadata.get("代次")).isEqualTo(Long.toString(f.browser().generation()));
    assertThat(metadata.get("快照时间")).isEqualTo(jdbc.queryForObject("select snapshot_at from demo_schedule_export_snapshot where run_id=? and export_revision=?",java.sql.Timestamp.class,f.run(),version).toInstant().toString());
    com.jkxt.tasks.ExamTaskExportArtifactIntegrationTest.assertPdf(export(f,version,"pdf"),rows,metadata,false);
  }
  @Test void simultaneousEditsWaitAtWorkspaceThenRefreshRunAndExactExportSeesWholeCommittedVersion() throws Exception {
    var f=flow("main");var initial=display(f);String token=initial.path("stats").path("outputToken").asText();
    var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
    var pool=java.util.concurrent.Executors.newFixedThreadPool(3);
    try {
      var blocker=pool.submit(()->workspaces.transaction(()->{workspaces.workspace(f.browser().workspace(),true);held.countDown();
        try{assertThat(release.await(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new RuntimeException(e);}return null;}));
      assertThat(held.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var first=pool.submit(()->mutate(f,"patch",f.spare(),token));var second=pool.submit(()->mutate(f,"patch",f.spare(),token));
      org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(()->
          assertThat(jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%demo_workspace%'",Integer.class)).isGreaterThanOrEqualTo(2));
      assertArtifacts(f,0,initial.path("rows"));release.countDown();blocker.get(5,java.util.concurrent.TimeUnit.SECONDS);
      var results=List.of(first.get(15,java.util.concurrent.TimeUnit.SECONDS),second.get(15,java.util.concurrent.TimeUnit.SECONDS));
      assertThat(results.stream().map(MockHttpServletResponse::getStatus)).containsExactlyInAnyOrder(200,412);
      assertThat(results.stream().filter(r->r.getStatus()==412).findFirst().orElseThrow().getContentAsString()).contains("DEMO_OUTPUT_TOKEN_STALE");
      assertThat(display(f).path("stats").path("exportRevision").asLong()).isEqualTo(1);
      assertThat(jdbc.queryForObject("select count(*) from schedule_revision where schedule_run_id=?",Integer.class,f.run())).isEqualTo(1);
      assertThat(jdbc.queryForObject("select count(*) from demo_schedule_export_snapshot where run_id=?",Integer.class,f.run())).isEqualTo(2);
      assertArtifacts(f,0,initial.path("rows"));assertArtifacts(f,1,display(f).path("rows"));
    }finally{release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
  }
  @Test void capturedLeaseCannotCommitAfterRealResetBeforeFenceAcquisition() throws Exception {
    var f=flow("main");var other=f.fixture().enter();String untouched=f.fixture().fingerprint(other.visitor().id());
    var before=f.fixture().storedSnapshot(f.browser(),f.run(),0);String token=display(f).path("stats").path("outputToken").asText();
    var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
    var pool=java.util.concurrent.Executors.newSingleThreadExecutor(r->new Thread(r,"task6c-stale-captured"));
    org.mockito.Mockito.doAnswer(call->{if(Thread.currentThread().getName().equals("task6c-stale-captured")) {
      entered.countDown();assertThat(release.await(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}return call.callRealMethod();
    }).when(fence).commit(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    try {
      var pending=pool.submit(()->mutate(f,"patch",f.spare(),token));assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      assertThat(f.fixture().reset(f.browser(),UUID.randomUUID()).getResponse().getStatus()).isEqualTo(200);
      release.countDown();var response=pending.get(10,java.util.concurrent.TimeUnit.SECONDS);assertThat(response.getStatus()).isEqualTo(409);
      assertThat(response.getContentAsString()).contains("DEMO_STALE_GENERATION");
      assertThat(f.fixture().storedSnapshot(f.browser(),f.run(),0)).isEqualTo(before);
      assertThat(jdbc.queryForObject("select count(*) from demo_schedule_export_snapshot where run_id=?",Integer.class,f.run())).isEqualTo(1);
      assertThat(jdbc.queryForObject("select revision_cursor from schedule_run where id=?",Integer.class,f.run())).isZero();
      assertThat(f.fixture().fingerprint(other.visitor().id())).isEqualTo(untouched);
    }finally{org.mockito.Mockito.reset(fence);release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
  }
  @Test void callerManagedRunLoadedBeforePriorCommitIsRefreshedAfterWaitingForRunLock() throws Exception {
    var f=flow("main");var repository=application.getBean(com.jkxt.scheduling.repo.ScheduleRunRepository.class);
    var loaded=new java.util.concurrent.CountDownLatch(1);var proceed=new java.util.concurrent.CountDownLatch(1);
    var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
    var token=new java.util.concurrent.atomic.AtomicReference<String>();var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var contender=pool.submit(()->workspaces.transaction(()->{
        var cached=repository.findByOrgIdAndId(f.browser().org(),f.run()).orElseThrow();assertThat(cached.getRevisionCursor()).isZero();loaded.countDown();
        try {
          assertThat(proceed.await(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
          // Same managed entity still has cursor 0; the real HTTP entry joins this outer transaction.
          assertThat(cached.getRevisionCursor()).isZero();var response=mutate(f,"patch",f.spare(),token.get());
          assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);return response;
        }catch(Exception failure){throw new RuntimeException(failure);}
      }));
      assertThat(loaded.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      token.set(success(f,"patch",f.spare()).path("outputToken").asText());
      var locker=pool.submit(()->workspaces.transaction(()->{
        jdbc.queryForObject("select id from schedule_run where id=? for update",UUID.class,f.run());held.countDown();
        try{assertThat(release.await(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}catch(InterruptedException failure){throw new RuntimeException(failure);}return null;
      }));
      assertThat(held.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();proceed.countDown();
      DemoTestFixture.awaitBlockedQuery(jdbc,"schedule_run");release.countDown();locker.get(5,java.util.concurrent.TimeUnit.SECONDS);
      var response=contender.get(10,java.util.concurrent.TimeUnit.SECONDS);assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
      var result=mapper.readTree(response.getContentAsByteArray());assertThat(result.path("revisionCursor").asInt()).isEqualTo(2);assertThat(result.path("exportRevision").asInt()).isEqualTo(2);
      assertThat(display(f).path("stats").path("revisionCursor").asInt()).isEqualTo(2);
    }finally{proceed.countDown();release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
  }
  @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"patch","undo","redo","preview"})
  void callerManagedTeacherCannotRestoreObsoleteQualification(String action) throws Exception {
    var f=flow("main");UUID original=UUID.fromString(f.row().path("teacherId").asText());
    if(action.equals("undo")||action.equals("redo"))success(f,"patch",f.spare());if(action.equals("redo"))success(f,"undo",f.spare());
    UUID target=action.equals("undo")?original:f.spare();String token=display(f).path("stats").path("outputToken").asText();
    var teachers=application.getBean(com.jkxt.masterdata.repo.TeacherRepository.class);
    var tx=new org.springframework.transaction.support.TransactionTemplate(application.getBean(org.springframework.transaction.PlatformTransactionManager.class));
    var pool=java.util.concurrent.Executors.newSingleThreadExecutor();var before=new java.util.concurrent.atomic.AtomicReference<String>();
    try {
      tx.execute(status->{
        var cached=teachers.findByOrgIdAndId(f.browser().org(),target).orElseThrow();assertThat(cached.getQualifications()).contains("INVIGILATOR","CHIEF");
        try {
          var invalid=validTeacher(f,target,"main");invalid.put("qualifications",java.util.List.of());
          pool.submit(()->{updateTeacher(f,target,invalid);return null;}).get(10,java.util.concurrent.TimeUnit.SECONDS);
          assertThat(cached.getQualifications()).contains("INVIGILATOR","CHIEF");
          assertThat(jdbc.queryForObject("select qualifications::text from teacher where id=?",String.class,target)).isEqualTo("[]");
          before.set(f.fixture().fingerprint(f.browser().visitor().id()));
          if(action.equals("preview")) {
            var preview=preview(f,target);assertThat(preview.path("allowed").asBoolean()).isFalse();
            assertThat(preview.path("conflicts").toString()).contains("H2_TEACHER_INVIGILATOR_REQUIRED");
            assertThat(f.fixture().fingerprint(f.browser().visitor().id())).isEqualTo(before.get());
          }else rejected(f,action,target,token,409,"SCHEDULE_CONFLICT");
          return null;
        }catch(Exception failure){throw new RuntimeException(failure);}finally{status.setRollbackOnly();}
      });
      assertThat(f.fixture().fingerprint(f.browser().visitor().id())).isEqualTo(before.get());
    }finally{pool.shutdownNow();assertThat(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
  }
  @Test void callerManagedAssignmentUsesActualPriorTeacherWhenRecordingNextHistory() throws Exception {
    var f=flow("main");UUID original=UUID.fromString(f.row().path("teacherId").asText());
    var assignments=application.getBean(com.jkxt.scheduling.repo.ScheduleAssignmentRepository.class);
    var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      workspaces.transaction(()->{
        var cached=assignments.findByOrgIdAndId(f.browser().org(),UUID.fromString(f.row().path("assignmentId").asText())).orElseThrow();
        assertThat(cached.getTeacherId()).isEqualTo(original);
        try {
          var first=pool.submit(()->success(f,"patch",f.spare())).get(10,java.util.concurrent.TimeUnit.SECONDS);
          assertThat(cached.getTeacherId()).isEqualTo(original);
          var response=mutate(f,"patch",original,first.path("outputToken").asText());
          assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);return null;
        }catch(Exception failure){throw new RuntimeException(failure);}
      });
      assertThat(jdbc.queryForObject("select before_state->>'teacherId' from schedule_revision where schedule_run_id=? and revision_no=2",String.class,f.run())).isEqualTo(f.spare().toString());
      assertThat(jdbc.queryForObject("select after_state->>'teacherId' from schedule_revision where schedule_run_id=? and revision_no=2",String.class,f.run())).isEqualTo(original.toString());
      assertThat(jdbc.queryForObject("select teacher_id from schedule_assignment where id=?",UUID.class,UUID.fromString(f.row().path("assignmentId").asText()))).isEqualTo(original);
      var committedRows=f.fixture().storedSnapshot(f.browser(),f.run(),2).path("rows");
      var committed=java.util.stream.StreamSupport.stream(committedRows.spliterator(),false)
          .filter(row->row.path("assignmentId").asText().equals(f.row().path("assignmentId").asText())).findFirst().orElseThrow();
      assertThat(committed.path("teacherId").asText()).isEqualTo(original.toString());
      assertThat(display(f).path("rows")).isEqualTo(committedRows);
      assertThat(display(f).path("stats").path("revisionCursor").asInt()).isEqualTo(2);
      assertThat(display(f).path("stats").path("exportRevision").asInt()).isEqualTo(2);
    }finally{pool.shutdownNow();assertThat(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
  }
  @Test void immutableSnapshotsArePurgedOnlyForRetiredGenerationWithLiveAndOtherSnapshotsPreserved() throws Exception {
    var f=new DemoTestFixture(application);var old=f.enter();UUID oldTask=f.smallTask(old),oldRun=f.solve(old,oldTask);
    var snapshot=f.storedSnapshot(old,oldRun,0);
    assertThatThrownBy(()->jdbc.update("update demo_schedule_export_snapshot set payload=payload where run_id=?",oldRun))
        .isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("Immutable demo export snapshot");
    assertThat(f.storedSnapshot(old,oldRun,0)).isEqualTo(snapshot);
    var other=f.enter();UUID otherTask=f.smallTask(other),otherRun=f.solve(other,otherTask);
    var reset=f.reset(old,UUID.randomUUID());assertThat(reset.getResponse().getStatus()).isEqualTo(200);
    var current=f.browser(old.visitor(),old.operation(),reset,old.cookie());UUID task=f.smallTask(current),run=f.solve(current,task);
    var live=f.storedSnapshot(current,run,0);String untouched=f.fingerprint(other.visitor().id());
    application.getBean(DemoCleanupService.class).sweep(clock.instant());
    assertThat(jdbc.queryForObject("select count(*) from demo_schedule_export_snapshot where run_id=?",Integer.class,oldRun)).isZero();
    assertThat(jdbc.queryForObject("select cleanup_state from demo_generation where workspace_id=? and generation=?",String.class,old.workspace(),old.generation())).isEqualTo("DONE");
    assertThat(f.storedSnapshot(current,run,0)).isEqualTo(live);assertThat(f.fingerprint(other.visitor().id())).isEqualTo(untouched);
    assertThat(f.storedSnapshot(other,otherRun,0).path("rows").size()).isEqualTo(2);
  }
  @Test void mutationResponseUsesCommittedSnapshotLabelsEvenWhenCallerHasCachedClassroom() throws Exception {
    var f=flow("main");UUID room=UUID.fromString(f.row().path("roomId").asText());
    var rooms=application.getBean(com.jkxt.masterdata.repo.ClassroomRepository.class);
    var read=http.perform(f.fixture().apply(get("/api/v1/classrooms/"+room),f.browser())).andReturn().getResponse();
    assertThat(read.getStatus()).isEqualTo(200);var current=mapper.readTree(read.getContentAsByteArray());var body=mapper.createObjectNode();
    for(String field:List.of("code","name","building","floor","capacity","examCapacity","roomType","status","features"))body.set(field,current.path(field));
    String changed="SNP-"+UUID.randomUUID().toString().substring(0,8);body.put("code",changed);body.put("name","已提交的新考场名称");
    var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      workspaces.transaction(()->{
        var cached=rooms.findByOrgIdAndId(f.browser().org(),room).orElseThrow();String oldCode=cached.getCode();
        try {
          var updated=pool.submit(()->http.perform(f.fixture().apply(put("/api/v1/classrooms/"+room),f.browser()).contentType("application/json")
              .content(mapper.writeValueAsString(body))).andReturn().getResponse()).get(10,java.util.concurrent.TimeUnit.SECONDS);
          assertThat(updated.getStatus()).as(updated.getContentAsString()).isEqualTo(200);
          assertThat(cached.getCode()).isEqualTo(oldCode).isNotEqualTo(changed);
          var result=success(f,"patch",f.spare());assertThat(result.path("assignment").path("roomCode").asText()).isEqualTo(changed);
          assertThat(result.path("assignment").path("roomName").asText()).isEqualTo("已提交的新考场名称");return null;
        }catch(Exception failure){throw new RuntimeException(failure);}
      });
      assertThat(display(f).path("rows")).isEqualTo(f.fixture().storedSnapshot(f.browser(),f.run(),1).path("rows"));
    }finally{pool.shutdownNow();assertThat(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
  }
  @Test void expiryAtOuterCommitRollsBackAssignmentsHistoryAuditAndSnapshot() throws Exception {
    var f=flow("main");String token=display(f).path("stats").path("outputToken").asText();String before=f.fixture().fingerprint(f.browser().visitor().id());Instant original=clock.instant();
    org.mockito.Mockito.doAnswer(call->{Object value=call.callRealMethod();
      org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
        @Override public int getOrder(){return Integer.MIN_VALUE;}
        @Override public void beforeCommit(boolean readOnly){clock.advance(Duration.ofMinutes(31));}
      });return value;
    }).when(snapshotSpy()).append(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    try{var response=mutate(f,"patch",f.spare(),token);assertThat(response.getStatus()).isEqualTo(401);assertThat(response.getContentAsString()).contains("DEMO_EXPIRED");}
    finally{org.mockito.Mockito.reset(snapshotSpy());clock.set(original);}
    assertThat(f.fixture().fingerprint(f.browser().visitor().id())).isEqualTo(before);
  }
  @Test void exactPairsNeverFallbackAcrossTaskRunVisitorGenerationOrActiveRerun() throws Exception {
    var f=flow("main");var initial=display(f);String oldToken=initial.path("stats").path("outputToken").asText();
    for(String format:List.of("xlsx","pdf")) {
      for(Map<String,String> query:List.of(Map.of("format",format),Map.of("format",format,"runId",f.run().toString()),
          Map.of("format",format,"revision","0"),Map.of("format",format,"runId",f.run().toString(),"revision","999"),
          Map.of("format",format,"runId",UUID.randomUUID().toString(),"revision","0"))) {
        var request=get("/api/v1/exam-tasks/"+f.task()+"/export");query.forEach(request::param);
        var response=http.perform(f.fixture().apply(request,f.browser())).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(query.containsKey("runId")&&query.containsKey("revision")?404:400);
      }
    }
    UUID otherTask=f.fixture().smallTask(f.browser()),otherRun=f.fixture().solve(f.browser(),otherTask);
    var mismatch=http.perform(f.fixture().apply(get("/api/v1/exam-tasks/"+f.task()+"/export").param("runId",otherRun.toString()).param("revision","0"),f.browser())).andReturn().getResponse();
    assertThat(mismatch.getStatus()).isEqualTo(404);assertThat(mismatch.getContentAsString()).contains("SCHEDULE_EXPORT_VERSION_UNAVAILABLE");
    var other=f.fixture().enter();String untouched=f.fixture().fingerprint(other.visitor().id());
    assertThat(http.perform(f.fixture().apply(get("/api/v1/exam-tasks/"+f.task()+"/export").param("runId",f.run().toString()).param("revision","0"),other)).andReturn().getResponse().getStatus()).isEqualTo(403);
    UUID rerun=f.fixture().solve(f.browser(),f.task());assertThat(rerun).isNotEqualTo(f.run());
    assertThat(display(f).path("runId").asText()).isEqualTo(rerun.toString());
    rejected(f,"patch",f.spare(),oldToken,412,"DEMO_OUTPUT_TOKEN_STALE");assertArtifacts(f,0,initial.path("rows"));
    var reset=f.fixture().reset(f.browser(),UUID.randomUUID());assertThat(reset.getResponse().getStatus()).isEqualTo(200);
    var current=f.fixture().browser(f.browser().visitor(),UUID.randomUUID(),reset,f.browser().cookie());
    var stale=http.perform(f.fixture().apply(get("/api/v1/exam-tasks/"+f.task()+"/export").param("runId",f.run().toString()).param("revision","0"),current)).andReturn().getResponse();
    assertThat(stale.getStatus()).isEqualTo(403);assertThat(f.fixture().fingerprint(other.visitor().id())).isEqualTo(untouched);
  }
}
