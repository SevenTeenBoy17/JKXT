package com.jkxt.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiException;
import com.jkxt.examops.service.ExamPlanService;
import com.jkxt.importer.service.ImportCommitService;
import com.jkxt.examops.api.ExamPlanDtos.CreateExamPlanRequest;
import com.jkxt.examops.api.ExamPlanDtos.CreateRevisionRequest;
import com.jkxt.scheduling.api.LegacyPublicationDtos.SubmitPublicationRequest;
import com.jkxt.scheduling.service.LegacyPublicationService;
import com.jkxt.scheduling.service.ScheduleEditService;
import com.jkxt.examops.service.DutyAssignmentSelfService;
import com.jkxt.security.auth.AuthRequestAttributes;
import com.jkxt.security.auth.SessionPrincipal;
import java.util.List;
import java.util.UUID;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/** Projection and approval semantics with real storage; authentication itself is tested separately. */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LegacyPublicationProjectionIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired ExamPlanService plans;
  @Autowired LegacyPublicationService legacy;
  @Autowired ImportCommitService imports;
  @Autowired ScheduleEditService edits;
  @Autowired DutyAssignmentSelfService self;
  @Autowired com.jkxt.tasks.service.ExamTaskService tasks;
  @Autowired com.jkxt.examops.service.LeaveRequestService leaves;
  UUID org, task, run, exam, teacher, actor, reviewer;
  SessionPrincipal principal;

  @BeforeEach
  void fixture() {
    org = UUID.randomUUID(); task = UUID.randomUUID(); run = UUID.randomUUID();
    exam = UUID.randomUUID(); teacher = UUID.randomUUID(); actor = UUID.randomUUID(); reviewer = UUID.randomUUID();
    UUID room = UUID.randomUUID();
    jdbc.update("insert into organization(id,name,slug) values (?,'Legacy projection',?)", org, "projection-" + org);
    for (UUID user : List.of(actor, reviewer)) {
      jdbc.update("""
          insert into app_user(id,org_id,username,display_name,password_hash,role,must_change_password)
          values (?,?,?,'Reviewer','test-only-unused','ADMIN',false)
          """, user, org, user.toString());
    }
    TenantContext.set(org);
    principal = new SessionPrincipal("test", actor, org, "publisher", "Publisher", "ADMIN", List.of("schedule:manage"));
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
        principal, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("schedule:manage"))));
    jdbc.update("""
        insert into teacher(id,org_id,teacher_no,name,gender,qualifications,max_daily_sessions)
        values (?,?,'T1','Chief','unknown','["INVIGILATOR","CHIEF"]'::jsonb,4)
        """, teacher, org);
    jdbc.update("insert into classroom(id,org_id,code,name,capacity,exam_capacity) values (?,?,'R1','Room',30,30)", room, org);
    jdbc.update("insert into exam_task(id,org_id,task_no,name,status) values (?,?,?,'Legacy exam','scheduled')", task, org, task.toString());
    jdbc.update("""
        insert into exam(id,org_id,exam_task_id,subject_name,exam_date,start_time,end_time,settings)
        values (?,?,?,'Math',date '2043-04-04',time '08:00',time '09:00',
          '{"seatDemand":20,"supervisorsPerRoom":1}'::jsonb)
        """, exam, org, task);
    jdbc.update("insert into exam_room(org_id,exam_id,classroom_id) values (?,?,?)", org, exam, room);
    jdbc.update("insert into schedule_run(id,org_id,exam_task_id,status) values (?,?,?,'succeeded')", run, org, task);
    jdbc.update("""
        insert into schedule_assignment(org_id,schedule_run_id,exam_id,classroom_id,teacher_id,role,exam_date,start_time,end_time)
        values (?,?,?,?,?,'main',date '2043-04-04',time '08:00',time '09:00')
        """, org, run, exam, room, teacher);
  }

  @AfterEach
  void clear() { TenantContext.clear(); SecurityContextHolder.clearContext(); }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void newlyApprovedLeaveBlocksLegacyPublicationBeforeOrAfterPlanApproval(boolean planAlreadyApproved) {
    var projection = legacy.submit(task, new SubmitPublicationRequest(run, 0L), actor);
    if (planAlreadyApproved) plans.approve(projection.planId(), projection.revisionId(), reviewer, "Reviewed", null);
    var start = java.time.Instant.parse("2043-04-04T00:00:00Z");
    var leave = leaves.create(new com.jkxt.examops.api.LeaveRequestDtos.CreateLeaveRequest(
        teacher, start, start.plusSeconds(3600), "New leave after submission"), actor, UUID.randomUUID().toString());
    assertThat(leaves.decide(leave.id(), new com.jkxt.examops.api.LeaveRequestDtos.DecideLeaveRequest("APPROVED", "Reviewed"), reviewer)
        .substitutionCaseCount()).isZero();
    if (!planAlreadyApproved) plans.approve(projection.planId(), projection.revisionId(), reviewer, "Reviewed", null);
    assertThat(legacy.get(task).sourceCurrent()).isTrue(); // The live safety check must not rely on source fingerprints.
    assertThatThrownBy(() -> edits.publish(task)).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.getCode()).isEqualTo("PLAN_NOT_READY");
      assertThat(e.getMessage()).contains("TEACHER_UNAVAILABLE");
    });
    assertThat(jdbc.queryForObject("select status from exam_task where id=?", String.class, task)).isEqualTo("scheduled");
    assertThat(jdbc.queryForObject("select count(*) from notification where exam_task_id=?", Integer.class, task)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from exam_plan_outbox where revision_id=?", Integer.class, projection.revisionId())).isZero();
  }

  @Test
  void legacyExportsExplainTheirScopeWithoutMovingTheExistingSheetRows() throws Exception {
    try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(tasks.export(task, "xlsx")))) {
      assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("考试日期");
      assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(1);
      assertThat(workbook.getSheet("使用说明")).isNotNull();
      assertThat(workbook.getSheet("使用说明").getRow(0).getCell(0).getStringCellValue()).isEqualTo("仅监考安排，未含考生座位名单");
    }
    var reader = new com.lowagie.text.pdf.PdfReader(tasks.export(task, "pdf"));
    try {
      // OpenPDF's general extractor returns an empty page for its own built-in CJK CMap.
      // Parse actual PDF text operators and require the declared Unicode encoding, not raw byte presence.
      var resources = reader.getPageN(1).getAsDict(com.lowagie.text.pdf.PdfName.RESOURCES);
      var font = resources.getAsDict(com.lowagie.text.pdf.PdfName.FONT).getAsDict(new com.lowagie.text.pdf.PdfName("F1"));
      assertThat(font.getAsName(com.lowagie.text.pdf.PdfName.ENCODING).toString()).isEqualTo("/UniGB-UCS2-H");
      var parser = new com.lowagie.text.pdf.PdfContentParser(new com.lowagie.text.pdf.PRTokeniser(reader.getPageContent(1)));
      var operands = new java.util.ArrayList<com.lowagie.text.pdf.PdfObject>();
      var text = new StringBuilder();
      while (!parser.parse(operands).isEmpty()) {
        if ("Tj".equals(operands.getLast().toString())) {
          text.append(new String(((com.lowagie.text.pdf.PdfString) operands.getFirst()).getBytes(), StandardCharsets.UTF_16BE));
        }
      }
      assertThat(text.toString()).contains("监考安排表", "仅监考安排，未含考生座位名单", "Math");
    } finally { reader.close(); }
  }

  @Test
  void oldPublishCannotBypassApproval() {
    assertThatThrownBy(() -> edits.publish(task)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.getCode()).isEqualTo("LEGACY_APPROVAL_REQUIRED"));
    var projected = legacy.submit(task, new SubmitPublicationRequest(run, 0L), actor);
    assertThatThrownBy(() -> edits.publish(task)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.getCode()).isEqualTo("LEGACY_APPROVAL_REQUIRED"));
    assertThat(jdbc.queryForObject("select count(*) from notification where exam_task_id=?", Integer.class, task)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from exam_plan_outbox where revision_id=?", Integer.class, projected.revisionId())).isZero();
  }

  @Test
  void oldPublishUsesApprovedProjectionAndRemainsIdempotentButRejectsChangedSource() {
    var projected = legacy.submit(task, new SubmitPublicationRequest(run, 0L), actor);
    plans.approve(projected.planId(), projected.revisionId(), reviewer, "reviewed", null);
    var first = edits.publish(task);
    assertThat(first.status()).isEqualTo("published");
    assertThat(plans.revision(projected.planId(), projected.revisionId()).status()).isEqualTo("PUBLISHED");
    var publishedAt = jdbc.queryForObject("select published_at from exam_task where id=?", java.sql.Timestamp.class, task).toInstant();
    var second = edits.publish(task);
    assertThat(second.alreadyPublished()).isTrue();
    assertThat(second.publishedAt()).isEqualTo(publishedAt);
    assertThat(jdbc.queryForObject("select count(*) from notification where exam_task_id=?", Integer.class, task)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select payload->>'scopeNotice' from notification where exam_task_id=?", String.class, task))
        .isEqualTo("仅监考安排，未含考生座位名单");
    assertThat(jdbc.queryForObject("select count(*) from exam_plan_outbox where revision_id=?", Integer.class, projected.revisionId())).isEqualTo(1);
    jdbc.update("update exam set settings=jsonb_set(settings,'{seatDemand}','21'::jsonb) where id=?", exam);
    assertThatThrownBy(() -> edits.publish(task)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.getCode()).isEqualTo("LEGACY_PROJECTION_STALE"));
  }

  @Test
  void historicalPublishedTaskWithoutProjectionIsOnlyAnIdempotentRead() {
    jdbc.update("update exam_task set status='published',published_at=now() where id=?", task);
    var existing = edits.publish(task);
    assertThat(existing.alreadyPublished()).isTrue();
    assertThat(jdbc.queryForObject("select count(*) from notification where exam_task_id=?", Integer.class, task)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from legacy_exam_task_plan_link where exam_task_id=?", Integer.class, task)).isZero();
  }

  @Test
  void teacherOnlySeesAndRespondsToCurrentPublishedRevision() {
    jdbc.update("update teacher set app_user_id=? where id=?", actor, teacher);
    var first = legacy.submit(task, new SubmitPublicationRequest(run, 0L), actor);
    plans.approve(first.planId(), first.revisionId(), reviewer, "first", null);
    plans.publish(first.planId(), first.revisionId(), actor, null);
    UUID oldDuty = (UUID) self.assignments(actor).getFirst().get("id");
    jdbc.update("update exam set exam_date=date '2043-04-05' where id=?", exam);
    jdbc.update("update schedule_assignment set exam_date=date '2043-04-05' where schedule_run_id=?", run);
    var next = legacy.submit(task, new SubmitPublicationRequest(run, 0L), actor);
    plans.approve(next.planId(), next.revisionId(), reviewer, "next", null);
    plans.publish(next.planId(), next.revisionId(), actor, null);
    assertThat(self.assignments(actor)).hasSize(1);
    assertThat(self.assignments(actor).getFirst().get("revision_id")).isEqualTo(next.revisionId());
    assertThatThrownBy(() -> self.accept(actor, oldDuty)).isInstanceOf(ApiException.class);
  }

  @Test
  void submitsRealDemandWithoutInventingSeatsAndReplaysTheSameApproval() throws Exception {
    JsonNode submitted = submit();
    UUID plan = UUID.fromString(submitted.path("planId").asText());
    UUID revision = UUID.fromString(submitted.path("revisionId").asText());
    assertThat(submitted.path("mode").asText()).isEqualTo("INVIGILATOR_ONLY");
    assertThat(submitted.path("notice").asText()).contains("仅监考安排，未含考生座位名单");
    assertThat(submitted.path("status").asText()).isEqualTo("PENDING_APPROVAL");
    assertThat(submit().path("revisionId").asText()).isEqualTo(revision.toString());
    assertThat(jdbc.queryForObject("select count(*) from exam_plan_approval_request where revision_id=?", Integer.class, revision)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select sum(candidate_count) from exam_plan_session where revision_id=?", Integer.class, revision)).isEqualTo(20);
    assertThat(jdbc.queryForObject("select sum(allocated_candidates) from exam_plan_room_assignment where revision_id=?", Integer.class, revision)).isEqualTo(20);
    assertThat(jdbc.queryForObject("select count(*) from exam_plan_seat_assignment where revision_id=?", Integer.class, revision)).isZero();
    assertThatThrownBy(() -> plans.approve(plan, revision, actor, "self", null))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("APPROVAL_SELF_REVIEW_FORBIDDEN"));
    assertThat(plans.approve(plan, revision, reviewer, "reviewed", null).status()).isEqualTo("APPROVED");
    assertThat(plans.publish(plan, revision, actor, null).status()).isEqualTo("PUBLISHED");
    assertThat(jdbc.queryForObject("select published_history from legacy_exam_task_plan_link where org_id=? and exam_task_id=?",
        Boolean.class, org, task)).isTrue();
    assertThat(jdbc.queryForObject("select payload->>'scopeNotice' from exam_plan_outbox where revision_id=?", String.class, revision))
        .isEqualTo("仅监考安排，未含考生座位名单");
  }

  @Test
  void concurrentSubmissionsProduceOneProjectionAndOneApprovalRequest() throws Exception {
    CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> submitAfterBarrier(ready, start));
      var second = executor.submit(() -> submitAfterBarrier(ready, start));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(second.get(30, TimeUnit.SECONDS));
    } finally { start.countDown(); }
    assertThat(jdbc.queryForObject("select count(*) from exam_plan_revision where org_id=?", Integer.class, org)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from exam_plan_approval_request where org_id=?", Integer.class, org)).isEqualTo(1);
  }

  private UUID submitAfterBarrier(CountDownLatch ready, CountDownLatch start) throws Exception {
    TenantContext.set(org);
    try {
      ready.countDown();
      if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Submit barrier timed out");
      return legacy.submit(task, new SubmitPublicationRequest(run, 0L), actor).revisionId();
    } finally { TenantContext.clear(); }
  }

  @Test
  void callerControlledConfigurationCannotTurnOffOrdinarySeatValidation() {
    var plan = plans.create(new CreateExamPlanRequest(null, "Ordinary", null, null, null), actor, null);
    var revision = plans.createRevision(plan.id(), new CreateRevisionRequest(null, "Forged mode", "Scope regression",
        mapper.createObjectNode().put("mode", "INVIGILATOR_ONLY").put("legacyTaskId", task.toString())), actor, null);
    jdbc.update("""
        insert into exam_plan_session(org_id,revision_id,session_code,name,starts_at,ends_at,candidate_count)
        values (?,?,'S1','Ordinary',timestamptz '2043-04-04 00:00:00+00',timestamptz '2043-04-04 01:00:00+00',20)
        """, org, revision.id());
    var result = plans.validate(plan.id(), revision.id(), actor, 0L);
    assertThat(result.valid()).isFalse();
    assertThat(result.issues()).extracting(issue -> issue.code()).contains("SESSION_SEATS_UNASSIGNED", "SEATS_UNASSIGNED");
  }

  @ParameterizedTest
  @ValueSource(strings = {"capacity", "qualification"})
  void invigilatorOnlyScopeStillRequiresRealCapacityAndChiefQualification(String invalid) throws Exception {
    if (invalid.equals("capacity")) {
      jdbc.update("update exam set settings=jsonb_set(settings,'{seatDemand}','31'::jsonb) where id=?", exam);
    } else {
      jdbc.update("update teacher set qualifications='[\"INVIGILATOR\"]'::jsonb where id=?", teacher);
    }
    mvc.perform(post("/api/v1/tasks/{taskId}/publication/submit-approval", task)
            .requestAttr(AuthRequestAttributes.PRINCIPAL, principal).contentType(MediaType.APPLICATION_JSON)
            .content("{\"runId\":\"" + run + "\",\"runRevisionCursor\":0}"))
        .andExpect(status().isUnprocessableEntity());
    assertThat(jdbc.queryForObject("select count(*) from legacy_exam_task_plan_link where org_id=?", Integer.class, org)).isZero();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void sourceContentChangeInvalidatesApprovalEvenWhenRunAndCursorAreUnchanged(boolean alreadyApproved) throws Exception {
    JsonNode submitted = submit();
    UUID plan = UUID.fromString(submitted.path("planId").asText());
    UUID revision = UUID.fromString(submitted.path("revisionId").asText());
    if (alreadyApproved) plans.approve(plan, revision, reviewer, "reviewed", null);
    jdbc.update("update exam set settings=jsonb_set(settings,'{seatDemand}','21'::jsonb) where id=?", exam);
    assertThatThrownBy(() -> {
      if (alreadyApproved) plans.publish(plan, revision, actor, null);
      else plans.approve(plan, revision, reviewer, "outdated", null);
    }).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("LEGACY_PROJECTION_STALE"));
    assertThat(jdbc.queryForObject("select count(*) from exam_plan_outbox where revision_id=?", Integer.class, revision)).isZero();
    JsonNode successor = submit();
    assertThat(successor.path("revisionId").asText()).isNotEqualTo(revision.toString());
    assertThat(successor.path("status").asText()).isEqualTo("PENDING_APPROVAL");
  }

  @Test
  void editingProjectionCannotBypassSeatScopeOrSourceBinding() throws Exception {
    JsonNode submitted = submit();
    UUID plan = UUID.fromString(submitted.path("planId").asText());
    UUID revision = UUID.fromString(submitted.path("revisionId").asText());
    jdbc.update("update exam_plan_session set candidate_count=0 where revision_id=?", revision);
    assertThatThrownBy(() -> plans.approve(plan, revision, reviewer, "tampered", null))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("LEGACY_PROJECTION_STALE"));
  }

  @Test
  void staleClientRunIsRejectedWithoutCreatingProjection() throws Exception {
    mvc.perform(post("/api/v1/tasks/{taskId}/publication/submit-approval", task)
            .requestAttr(AuthRequestAttributes.PRINCIPAL, principal).contentType(MediaType.APPLICATION_JSON)
            .content("{\"runId\":\"" + run + "\",\"runRevisionCursor\":99}"))
        .andExpect(status().isConflict());
    assertThat(jdbc.queryForObject("select count(*) from legacy_exam_task_plan_link where org_id=?", Integer.class, org)).isZero();
  }

  @Test
  void unpublishedReimportInvalidatesProjectionWithoutDeletingApprovalHistory() throws Exception {
    JsonNode submitted = submit();
    UUID plan = UUID.fromString(submitted.path("planId").asText());
    UUID revision = UUID.fromString(submitted.path("revisionId").asText());
    try (var workbook = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("exams");
      String[][] cells = {{"考试科目", "年级", "考试日期", "开始时间", "结束时间", "考场编号", "人数", "监考人数", "班级"},
          {"Replacement", "高一", "2043-04-05", "08:00", "09:00", "R1", "20", "1", ""}};
      for (int r = 0; r < cells.length; r++) {
        var row = sheet.createRow(r);
        for (int c = 0; c < cells[r].length; c++) row.createCell(c).setCellValue(cells[r][c]);
      }
      workbook.write(bytes);
      var file = new MockMultipartFile("file", "exams.xlsx",
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes.toByteArray());
      assertThat(imports.commit("exams", task, file).inserted()).isEqualTo(1);
    }
    assertThat(jdbc.queryForObject("select count(*) from schedule_run where exam_task_id=?", Integer.class, task)).isZero();
    assertThat(jdbc.queryForObject("select status from exam_plan_approval_request where revision_id=?", String.class, revision)).isEqualTo("CANCELLED");
    assertThat(jdbc.queryForObject("select projected_run_id from legacy_exam_task_plan_link where org_id=? and exam_task_id=?",
        UUID.class, org, task)).isNull();
    assertThat(jdbc.queryForObject("select count(*) from exam_plan_revision where id=?", Integer.class, revision)).isEqualTo(1);
    assertThatThrownBy(() -> plans.approve(plan, revision, reviewer, "obsolete", null)).isInstanceOf(ApiException.class);
  }

  private JsonNode submit() throws Exception {
    String body = mvc.perform(post("/api/v1/tasks/{taskId}/publication/submit-approval", task)
            .requestAttr(AuthRequestAttributes.PRINCIPAL, principal).contentType(MediaType.APPLICATION_JSON)
            .content("{\"runId\":\"" + run + "\",\"runRevisionCursor\":0}"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    JsonNode value = mapper.readTree(body);
    mvc.perform(get("/api/v1/tasks/{taskId}/publication", task)
            .requestAttr(AuthRequestAttributes.PRINCIPAL, principal)).andExpect(status().isOk());
    return value;
  }
}
