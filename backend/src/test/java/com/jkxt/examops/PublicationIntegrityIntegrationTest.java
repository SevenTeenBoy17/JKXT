package com.jkxt.examops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiException;
import com.jkxt.examops.service.ExamPlanService;
import com.jkxt.examops.service.LeaveRequestService;
import com.jkxt.examops.api.LeaveRequestDtos.CreateLeaveRequest;
import com.jkxt.examops.api.LeaveRequestDtos.DecideLeaveRequest;
import com.jkxt.importer.service.ImportCommitService;
import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

/** Real storage boundaries: import cannot erase publication, and publication reserves resources. */
@SpringBootTest
@ActiveProfiles("test")
class PublicationIntegrityIntegrationTest {
  private static final Instant START = Instant.parse("2042-03-03T00:00:00Z");
  @Autowired JdbcTemplate jdbc;
  @Autowired ExamPlanService plans;
  @Autowired ImportCommitService imports;
  @Autowired LeaveRequestService leaves;
  private UUID org;
  private UUID teacher;
  private UUID room;
  private UUID actor;

  @ParameterizedTest
  @ValueSource(strings = {"PUBLISHED", "EXECUTING", "ARCHIVED", "DUTY_CANCELLED", "DUTY_DECLINED", "SESSION_CANCELLED", "REJECTED", "PUBLISHED_OVERLAP", "EXECUTING_OVERLAP"})
  void roomlessExternalDutyCountsAtCommonPublicationOnlyWhileLive(String state) {
    boolean overlap = state.endsWith("_OVERLAP");
    seedRoomlessPublication(state.replace("_OVERLAP", ""));
    jdbc.update("update teacher set max_daily_sessions=? where id=?", overlap ? 10 : 1, teacher);
    Plan candidate = approvedPlan(teacher, room, overlap ? START : START.plusSeconds(7200));
    Integer notificationsBefore = jdbc.queryForObject("select count(*) from notification where org_id=?", Integer.class, org);
    Integer outboxBefore = jdbc.queryForObject("select count(*) from exam_plan_outbox where org_id=?", Integer.class, org);
    boolean live = state.startsWith("PUBLISHED") || state.startsWith("EXECUTING");
    if (live) {
      assertThatThrownBy(() -> plans.publish(candidate.plan(), candidate.revision(), actor, 0L))
          .isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(error.getCode()).isEqualTo("PLAN_NOT_READY");
            assertThat(error.getMessage()).contains(overlap ? "PUBLISHED_TEACHER_CONFLICT" : "TEACHER_DAILY_SESSION_LIMIT_EXCEEDED");
          });
      assertThat(jdbc.queryForObject("select status from exam_plan_revision where id=?", String.class, candidate.revision())).isEqualTo("APPROVED");
      assertThat(jdbc.queryForObject("select count(*) from exam_plan_outbox where revision_id=?", Integer.class, candidate.revision())).isZero();
      assertThat(jdbc.queryForObject("select count(*) from exam_plan_outbox where org_id=?", Integer.class, org)).isEqualTo(outboxBefore);
      assertThat(jdbc.queryForObject("select count(*) from notification where org_id=?", Integer.class, org)).isEqualTo(notificationsBefore);
    } else {
      assertThat(plans.publish(candidate.plan(), candidate.revision(), actor, 0L).status()).isEqualTo("PUBLISHED");
      assertThat(jdbc.queryForObject("select count(*) from exam_plan_outbox where revision_id=?", Integer.class, candidate.revision())).isEqualTo(1);
    }
  }

  private void seedRoomlessPublication(String state) {
    UUID owner = UUID.randomUUID(), revision = UUID.randomUUID(), session = UUID.randomUUID();
    UUID requirement = UUID.randomUUID(), assignment = UUID.randomUUID();
    jdbc.update("insert into exam_plan(id,org_id,plan_no,name) values (?,?,?,'Published session-wide duty')", owner, org, owner.toString());
    jdbc.update("insert into exam_plan_revision(id,org_id,plan_id,revision_no) values (?,?,?,1)", revision, org, owner);
    jdbc.update("insert into exam_plan_session(id,org_id,revision_id,session_code,name,starts_at,ends_at,status) values (?,?,?,'ROOMLESS','Session-wide duty',?,?,?)",
        session, org, revision, Timestamp.from(START), Timestamp.from(START.plusSeconds(3600)),
        state.equals("SESSION_CANCELLED") ? "CANCELLED" : "PLANNED");
    jdbc.update("insert into exam_plan_duty_requirement(id,org_id,revision_id,session_id,duty_role,required_count) values (?,?,?,?,'ROAMING',1)",
        requirement, org, revision, session);
    jdbc.update("insert into exam_plan_duty_assignment(id,org_id,revision_id,requirement_id,teacher_id,starts_at,ends_at,duty_role,status) values (?,?,?,?,?,?,?,'ROAMING',?)",
        assignment, org, revision, requirement, teacher, Timestamp.from(START), Timestamp.from(START.plusSeconds(3600)),
        state.equals("DUTY_CANCELLED") ? "CANCELLED" : state.equals("DUTY_DECLINED") ? "DECLINED" : "ACCEPTED");
    if (state.equals("REJECTED")) jdbc.update("insert into exam_plan_duty_response(org_id,assignment_id,teacher_id,status,reason) values (?,?,?,'REJECTED','Synthetic rejection')",
        org, assignment, teacher);
    jdbc.update("update exam_plan_revision set status=? where id=?",
        state.equals("ARCHIVED") ? "ARCHIVED" : state.equals("EXECUTING") ? "EXECUTING" : "PUBLISHED", revision);
    jdbc.update("update exam_plan set active_revision_id=? where id=?", revision, owner);
  }

  @BeforeEach
  void isolateFixtures() {
    org = UUID.randomUUID();
    jdbc.update("insert into organization(id,name,slug) values (?, 'Publication regression', ?)",
        org, "publication-" + org);
    TenantContext.set(org);
    actor = UUID.randomUUID();
    jdbc.update("""
        insert into app_user(id,org_id,username,display_name,password_hash,role,must_change_password)
        values (?,?,'publisher','Publisher','test-only-unused-hash','ADMIN',false)
        """, actor, org);
    teacher = teacher("T1");
    room = room("R1");
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
    // Immutable publication rows remain in this disposable test database; never disable triggers.
  }

  @Test
  void approvedLeaveAddedAfterPlanApprovalBlocksTheCommonPublisher() {
    Plan plan = approvedPlan(teacher, room, START);
    approveLeave(teacher, START, START.plusSeconds(3600));
    assertThatThrownBy(() -> plans.publish(plan.plan(), plan.revision(), actor, 0L))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.getCode()).isEqualTo("PLAN_NOT_READY");
          assertThat(e.getMessage()).contains("TEACHER_UNAVAILABLE");
        });
    assertThat(jdbc.queryForObject("select status from exam_plan_revision where id=?", String.class, plan.revision())).isEqualTo("APPROVED");
    assertThat(jdbc.queryForObject("select count(*) from exam_plan_outbox where revision_id=?", Integer.class, plan.revision())).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"pending", "rejected", "touching-before", "touching-after"})
  void nonApprovedAndTouchingLeaveDoesNotBlockPublication(String state) {
    Plan plan = approvedPlan(teacher, room, START);
    Instant from = state.equals("touching-before") ? START.minusSeconds(3600)
        : state.equals("touching-after") ? START.plusSeconds(3600) : START;
    var leave = leaves.create(new CreateLeaveRequest(teacher, from, from.plusSeconds(3600), "Synthetic leave"), actor, UUID.randomUUID().toString());
    if (!state.equals("pending")) leaves.decide(leave.id(),
        new DecideLeaveRequest(state.equals("rejected") ? "REJECTED" : "APPROVED", "Reviewed"), actor);
    assertThat(plans.publish(plan.plan(), plan.revision(), actor, 0L).status()).isEqualTo("PUBLISHED");
  }

  @Test
  void leaveApprovedAfterSuccessorPublicationOnlyCreatesCurrentSubstitution() {
    Plan first = approvedPlan(teacher, room, START);
    plans.publish(first.plan(), first.revision(), actor, 0L);
    Plan next = approvedRevision(first.plan(), 2, teacher, room, START);
    plans.publish(next.plan(), next.revision(), actor, 0L);
    var leave = approveLeave(teacher, START, START.plusSeconds(3600));
    assertThat(leave.substitutionCaseCount()).isEqualTo(1);
    assertThat(jdbc.queryForList("select revision_id from exam_plan_substitution_case where leave_request_id=?", UUID.class, leave.id()))
        .containsExactly(next.revision());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3})
  void concurrentLeaveApprovalAndPublicationNeverLoseTheSubstitution(int trial) throws Exception {
    Plan plan = approvedPlan(teacher, room, START);
    var leave = leaves.create(new CreateLeaveRequest(teacher, START, START.plusSeconds(3600), "Concurrent leave"), actor, UUID.randomUUID().toString());
    CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var publication = executor.submit(() -> publishAfterBarrier(plan, ready, start));
      var approval = executor.submit(() -> {
        TenantContext.set(org);
        try {
          ready.countDown();
          if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Leave barrier timed out");
          return leaves.decide(leave.id(), new DecideLeaveRequest("APPROVED", "Concurrent review"), actor);
        } finally { TenantContext.clear(); }
      });
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
      String result = publication.get(30, TimeUnit.SECONDS);
      var approved = approval.get(30, TimeUnit.SECONDS);
      assertThat(approved.status()).isEqualTo("APPROVED");
      assertThat(result).isIn("PUBLISHED", "PLAN_NOT_READY");
      assertThat(approved.substitutionCaseCount()).isEqualTo(result.equals("PUBLISHED") ? 1 : 0);
    } finally { start.countDown(); }
  }

  private com.jkxt.examops.api.LeaveRequestDtos.LeaveRequestView approveLeave(UUID teacherId, Instant from, Instant to) {
    var leave = leaves.create(new CreateLeaveRequest(teacherId, from, to, "Approved synthetic leave"), actor, UUID.randomUUID().toString());
    return leaves.decide(leave.id(), new DecideLeaveRequest("APPROVED", "Reviewed"), actor);
  }

  @Test
  void approvedLeaveForSameTeacherNumberInAnotherSchoolDoesNotBlock() {
    Plan plan = approvedPlan(teacher, room, START);
    UUID otherOrg = UUID.randomUUID(), otherTeacher = UUID.randomUUID(), otherActor = UUID.randomUUID();
    jdbc.update("insert into organization(id,name,slug) values (?,'Other school',?)", otherOrg, "other-" + otherOrg);
    jdbc.update("insert into teacher(id,org_id,teacher_no,name,gender) values (?,?,'T1','Other teacher','unknown')", otherTeacher, otherOrg);
    jdbc.update("""
        insert into app_user(id,org_id,username,display_name,password_hash,role)
        values (?,?,'other-reviewer','Other reviewer','test-only-unused','ADMIN')
        """, otherActor, otherOrg);
    TenantContext.set(otherOrg);
    try {
      var leave = leaves.create(new CreateLeaveRequest(otherTeacher, START, START.plusSeconds(3600), "Other school leave"),
          otherActor, UUID.randomUUID().toString());
      leaves.decide(leave.id(), new DecideLeaveRequest("APPROVED", "Reviewed"), otherActor);
    } finally { TenantContext.set(org); }
    assertThat(plans.publish(plan.plan(), plan.revision(), actor, 0L).status()).isEqualTo("PUBLISHED");
  }

  @ParameterizedTest
  @ValueSource(strings = {"published", "archived", "scheduled-with-publication-marker", "reopened-with-history"})
  void validExamReimportCannotErasePublishedTaskOrItsArtifacts(String state) throws Exception {
    UUID task = seedLegacyTask(state.equals("scheduled-with-publication-marker") ? "scheduled"
        : state.equals("reopened-with-history") ? "published" : state);
    if (state.equals("scheduled-with-publication-marker")) {
      jdbc.update("update exam_task set published_at=? where id=?", Timestamp.from(START), task);
    }
    if (state.equals("reopened-with-history")) {
      jdbc.update("update exam_task set status='scheduled', published_at=null where id=?", task);
    }
    Map<String, Object> before = legacySnapshot(task);
    assertThatThrownBy(() -> imports.commit("exams", task, workbook("Replacement")))
        .isInstanceOfSatisfying(ApiException.class, error -> {
          assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
          assertThat(error.getCode()).isEqualTo("EXAM_IMPORT_TASK_IMMUTABLE");
        });
    assertThat(legacySnapshot(task)).isEqualTo(before);
  }

  @Test
  void unpublishedExamReimportStillReplacesData() throws Exception {
    UUID task = seedLegacyTask("scheduled");
    assertThat(imports.commit("exams", task, workbook("Replacement")).inserted()).isEqualTo(1);
    assertThat(jdbc.queryForList("select subject_name from exam where exam_task_id=?", String.class, task))
        .containsExactly("Replacement");
    assertThat(jdbc.queryForObject("select count(*) from schedule_run where exam_task_id=?", Integer.class, task))
        .isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"teacher", "room"})
  void publishedResourceCannotBeReservedByAnotherPlan(String sharedResource) {
    Plan first = approvedPlan(teacher, room, START);
    Plan second = approvedPlan(sharedResource.equals("teacher") ? teacher : teacher("T2"),
        sharedResource.equals("room") ? room : room("R2"), START);
    plans.publish(first.plan(), first.revision(), actor, 0L);
    assertThatThrownBy(() -> plans.publish(second.plan(), second.revision(), actor, 0L))
        .isInstanceOfSatisfying(ApiException.class, error -> {
          assertThat(error.getCode()).isEqualTo("PLAN_NOT_READY");
          assertThat(error.getMessage()).contains(sharedResource.equals("teacher")
              ? "PUBLISHED_TEACHER_CONFLICT" : "PUBLISHED_ROOM_CONFLICT");
        });
    assertThat(jdbc.queryForObject("select status from exam_plan_revision where id=?", String.class, second.revision()))
        .isEqualTo("APPROVED");
    assertThat(jdbc.queryForObject("select count(*) from exam_plan_outbox where revision_id=?", Integer.class, second.revision()))
        .isZero();
  }

  @Test
  void touchingIntervalsAndIndependentResourcesRemainPublishable() {
    Plan first = approvedPlan(teacher, room, START);
    Plan next = approvedPlan(teacher, room, START.plusSeconds(3600));
    Plan independent = approvedPlan(teacher("T2"), room("R2"), START);
    for (Plan plan : List.of(first, next, independent)) {
      assertThat(plans.publish(plan.plan(), plan.revision(), actor, 0L).status()).isEqualTo("PUBLISHED");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"teacher", "room", "daily-limit"})
  void legacyPublishedWithoutProjectionStillProtectsResourcesAtCommonPublication(String risk) {
    seedLegacyTask("published");
    if (risk.equals("daily-limit")) jdbc.update("update teacher set max_daily_sessions=1 where id=?", teacher);
    Plan candidate = approvedPlan(risk.equals("room") ? teacher("T2") : teacher,
        risk.equals("teacher") ? room("R2") : room,
        risk.equals("daily-limit") ? START.plusSeconds(7200) : START);
    String code = risk.equals("daily-limit") ? "TEACHER_DAILY_SESSION_LIMIT_EXCEEDED"
        : risk.equals("teacher") ? "PUBLISHED_TEACHER_CONFLICT" : "PUBLISHED_ROOM_CONFLICT";
    assertThatThrownBy(() -> plans.publish(candidate.plan(), candidate.revision(), actor, 0L))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.getCode()).isEqualTo("PLAN_NOT_READY");
          assertThat(e.getMessage()).contains(code);
        });
    assertThat(jdbc.queryForObject("select status from exam_plan_revision where id=?", String.class, candidate.revision())).isEqualTo("APPROVED");
    assertThat(jdbc.queryForObject("select count(*) from exam_plan_outbox where revision_id=?", Integer.class, candidate.revision())).isZero();
  }

  @Test
  void successorReplacesOnlyItsOwnResourceReservationAndDailyCount() {
    jdbc.update("update teacher set max_daily_sessions=2 where id=?", teacher);
    Plan first = approvedPlan(teacher, room, START);
    plans.publish(first.plan(), first.revision(), actor, 0L);
    Plan successor = approvedRevision(first.plan(), 2, teacher, room, START.plusSeconds(3600));
    assertThat(plans.publish(successor.plan(), successor.revision(), actor, 0L).status()).isEqualTo("PUBLISHED");
    Plan reuseReleasedSlot = approvedPlan(teacher, room, START);
    assertThat(plans.publish(reuseReleasedSlot.plan(), reuseReleasedSlot.revision(), actor, 0L).status())
        .isEqualTo("PUBLISHED");
    assertThat(jdbc.queryForObject("select status from exam_plan_revision where id=?", String.class, first.revision()))
        .isEqualTo("PUBLISHED"); // Historical evidence is retained, but is not a second live reservation.
  }

  @Test
  void simultaneousConflictingPublicationCommitsExactlyOneVersionAndOutbox() throws Exception {
    Plan first = approvedPlan(teacher, room, START);
    Plan second = approvedPlan(teacher, room, START);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var left = executor.submit(() -> publishAfterBarrier(first, ready, start));
      var right = executor.submit(() -> publishAfterBarrier(second, ready, start));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(List.of(left.get(30, TimeUnit.SECONDS), right.get(30, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("PUBLISHED", "PLAN_NOT_READY");
    } finally {
      start.countDown();
    }
    assertThat(jdbc.queryForObject("select count(*) from exam_plan_revision where org_id=? and status='PUBLISHED'", Integer.class, org))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from exam_plan_outbox where org_id=?", Integer.class, org))
        .isEqualTo(1);
  }

  private String publishAfterBarrier(Plan plan, CountDownLatch ready, CountDownLatch start) throws Exception {
    TenantContext.set(org);
    try {
      ready.countDown();
      if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Publication barrier timed out");
      try {
        return plans.publish(plan.plan(), plan.revision(), actor, 0L).status();
      } catch (ApiException error) {
        return error.getCode();
      }
    } finally {
      TenantContext.clear();
    }
  }

  private UUID teacher(String code) {
    UUID id = UUID.randomUUID();
    jdbc.update("""
        insert into teacher(id,org_id,teacher_no,name,gender,qualifications,max_daily_sessions)
        values (?, ?, ?, ?, 'unknown', '["INVIGILATOR","CHIEF"]'::jsonb, 10)
        """, id, org, code, code);
    return id;
  }

  private UUID room(String code) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into classroom(id,org_id,code,name,capacity,exam_capacity) values (?,?,?,?,10,10)",
        id, org, code, code);
    return id;
  }

  private Plan approvedPlan(UUID teacherId, UUID roomId, Instant starts) {
    UUID plan = UUID.randomUUID();
    jdbc.update("insert into exam_plan(id,org_id,plan_no,name,status) values (?,?,?,'Approved fixture','APPROVED')",
        plan, org, plan.toString());
    Plan result = approvedRevision(plan, 1, teacherId, roomId, starts);
    jdbc.update("update exam_plan set active_revision_id=? where id=?", result.revision(), plan);
    return result;
  }

  private Plan approvedRevision(UUID plan, int revisionNo, UUID teacherId, UUID roomId, Instant starts) {
    UUID revision = UUID.randomUUID(), session = UUID.randomUUID();
    UUID allocation = UUID.randomUUID(), requirement = UUID.randomUUID();
    Timestamp start = Timestamp.from(starts), end = Timestamp.from(starts.plusSeconds(3600));
    jdbc.update("insert into exam_plan_revision(id,org_id,plan_id,revision_no,status,source_type) values (?,?,?,?,'APPROVED','API')",
        revision, org, plan, revisionNo);
    jdbc.update("""
        insert into exam_plan_session(id,org_id,revision_id,session_code,name,starts_at,ends_at,candidate_count)
        values (?,?,?,'S1','Exam',?,?,1)
        """, session, org, revision, start, end);
    jdbc.update("""
        insert into exam_plan_room_assignment(id,org_id,revision_id,session_id,classroom_id,starts_at,ends_at,planned_capacity,allocated_candidates)
        values (?,?,?,?,?,?,?,1,1)
        """, allocation, org, revision, session, roomId, start, end);
    jdbc.update("""
        insert into exam_plan_seat_assignment(org_id,revision_id,session_id,room_assignment_id,candidate_external_id,seat_label)
        values (?,?,?,?,'C1','1')
        """, org, revision, session, allocation);
    jdbc.update("""
        insert into exam_plan_duty_requirement(id,org_id,revision_id,session_id,room_assignment_id,duty_role,required_count,qualification)
        values (?,?,?,?,?,'CHIEF',1,'{"requiredQualifications":["INVIGILATOR","CHIEF"]}'::jsonb)
        """, requirement, org, revision, session, allocation);
    jdbc.update("""
        insert into exam_plan_duty_assignment(org_id,revision_id,requirement_id,teacher_id,starts_at,ends_at,duty_role)
        values (?,?,?,?,?,?,'CHIEF')
        """, org, revision, requirement, teacherId, start, end);
    return new Plan(plan, revision);
  }

  private UUID seedLegacyTask(String status) {
    UUID task = UUID.randomUUID(), exam = UUID.randomUUID(), run = UUID.randomUUID();
    jdbc.update("insert into exam_task(id,org_id,task_no,name,status,published_at) values (?,?,?,'Original',?,?)",
        task, org, task.toString(), status, status.equals("published") ? Timestamp.from(START) : null);
    jdbc.update("""
        insert into exam(id,org_id,exam_task_id,subject_name,exam_date,start_time,end_time)
        values (?,?,?,'Original',date '2042-03-03',time '08:00',time '09:00')
        """, exam, org, task);
    jdbc.update("insert into exam_room(org_id,exam_id,classroom_id) values (?,?,?)", org, exam, room);
    jdbc.update("insert into schedule_run(id,org_id,exam_task_id,status) values (?,?,?,'succeeded')", run, org, task);
    jdbc.update("""
        insert into schedule_assignment(org_id,schedule_run_id,exam_id,classroom_id,teacher_id,role,exam_date,start_time,end_time)
        values (?,?,?,?,?,'main',date '2042-03-03',time '08:00',time '09:00')
        """, org, run, exam, room, teacher);
    if (status.equals("published")) {
      jdbc.update("insert into notification(org_id,exam_task_id,type,title,status) values (?,?,'schedule_published','Original','sent')",
          org, task);
    }
    return task;
  }

  private Map<String, Object> legacySnapshot(UUID task) {
    return Map.of("task", jdbc.queryForMap("select status,published_at,version from exam_task where id=?", task),
        "exams", jdbc.queryForList("select * from exam where exam_task_id=? order by id", task),
        "runs", jdbc.queryForList("select * from schedule_run where exam_task_id=? order by id", task),
        "assignments", jdbc.queryForList("select * from schedule_assignment where org_id=? order by id", org),
        "notifications", jdbc.queryForList("select * from notification where exam_task_id=? order by id", task));
  }

  private MockMultipartFile workbook(String subject) throws Exception {
    try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("exams");
      String[][] values = {{"考试科目", "年级", "考试日期", "开始时间", "结束时间", "考场编号", "人数", "监考人数", "班级"},
          {subject, "高一", "2042-03-04", "08:00", "09:00", "R1", "1", "1", ""}};
      for (int r = 0; r < values.length; r++) {
        var row = sheet.createRow(r);
        for (int c = 0; c < values[r].length; c++) row.createCell(c).setCellValue(values[r][c]);
      }
      workbook.write(output);
      return new MockMultipartFile("file", "exams.xlsx",
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
    }
  }

  private record Plan(UUID plan, UUID revision) {}
}
