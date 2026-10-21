package com.jkxt.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jkxt.common.tenant.TenantContext;
import com.jkxt.scheduling.api.ScheduleEditDtos.ConflictCheckRequest;
import com.jkxt.scheduling.api.ScheduleEditDtos.SchedulePatchRequest;
import com.jkxt.scheduling.service.ScheduleEditService;
import com.jkxt.scheduling.service.ScheduleJobService;
import com.jkxt.scheduling.solver.CpSatScheduleSolver;
import com.jkxt.scheduling.solver.ScheduleFixture;
import com.jkxt.scheduling.solver.SchedulePolicy;
import com.jkxt.tasks.repo.ExamTaskRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/** Real PostgreSQL/JPA mappings and actual solver/manual mutation, no mocked backend. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LegacyConstraintMappingIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired ScheduleJobService jobs;
  @Autowired ScheduleEditService edits;
  @Autowired ExamTaskRepository tasks;
  @Autowired jakarta.persistence.EntityManager entityManager;
  private UUID org, actor, task, teacher, target, room, run, assignment;
  private static final LocalDate DAY = LocalDate.of(2027, 1, 12);

  @BeforeEach void setUp() {
    org = UUID.randomUUID(); actor = UUID.randomUUID(); task = UUID.randomUUID();
    teacher = UUID.randomUUID(); target = UUID.randomUUID(); room = UUID.randomUUID();
    run = UUID.randomUUID(); assignment = UUID.randomUUID();
    jdbc.update("insert into organization(id,name,slug) values (?,'Legacy constraint test',?)", org, "lc-" + org);
    jdbc.update("insert into app_user(id,org_id,username,display_name,password_hash,role,must_change_password) values (?,?,'tester','Tester','unused-test-only','ADMIN',false)", actor, org);
    jdbc.update("insert into teacher(id,org_id,teacher_no,name,gender,qualifications,max_daily_sessions) values (?,?,'A','主监','unknown','[\"INVIGILATOR\",\"CHIEF\"]'::jsonb,4)", teacher, org);
    jdbc.update("insert into teacher(id,org_id,teacher_no,name,gender,qualifications,max_daily_sessions) values (?,?,'B','候选教师','unknown','[\"INVIGILATOR\"]'::jsonb,1)", target, org);
    jdbc.update("insert into classroom(id,org_id,code,name,capacity,exam_capacity) values (?,?,'R1','考场',40,40)", room, org);
    jdbc.update("insert into exam_task(id,org_id,task_no,name,status) values (?,?,?,'候选排班','scheduled')", task, org, "T-" + task);
    UUID exam = exam(task, 9);
    jdbc.update("insert into schedule_run(id,org_id,exam_task_id,status,finished_at) values (?,?,?,'succeeded',now())", run, org, task);
    jdbc.update("insert into schedule_assignment(id,org_id,schedule_run_id,exam_id,classroom_id,teacher_id,role,exam_date,start_time,end_time) values (?,?,?,?,?,?,'main',?,'09:00','10:00')", assignment, org, run, exam, room, teacher, DAY);
    TenantContext.set(org);
  }
  @AfterEach void clear() { TenantContext.clear(); }

  @Test void loaderRetainsMasterQualificationExpiryAndDailyLimits() {
    jdbc.update("update teacher set qualification_expiry=? where id=?", DAY.minusDays(1), teacher);
    var snapshot = fixture();
    var source = snapshot.teachers().stream().filter(t -> t.id().equals(teacher)).findFirst().orElseThrow();
    assertThat(source.qualifications()).containsExactlyInAnyOrder("INVIGILATOR", "CHIEF");
    assertThat(source.qualificationExpiry()).isEqualTo(DAY.minusDays(1));
    assertThat(snapshot.teachers().stream().filter(t -> t.id().equals(target)).findFirst().orElseThrow().maxDailySessions()).isEqualTo(1);
    assertThat(new CpSatScheduleSolver().solve(snapshot, SchedulePolicy.defaults()).status()).isEqualTo("infeasible");
  }

  @Test void approvedLeaveUsesShanghaiDateAndBlocksLoaderAndManualButPendingDoesNot() {
    leave("PENDING");
    qualifyTarget();
    assertThat(edits.checkConflicts(task, new ConflictCheckRequest(assignment, target)).allowed()).isTrue();
    jdbc.update("update exam_plan_leave_request set status='APPROVED',decided_at=now() where org_id=?", org);
    var snapshot = fixture().teachers().stream().filter(t -> t.id().equals(target)).findFirst().orElseThrow();
    assertThat(snapshot.leaveWindows()).anyMatch(w -> w.start().equals(DAY.atTime(9, 0)) && w.end().equals(DAY.atTime(10, 0)));
    assertRejected("H2_TEACHER_LEAVE_WINDOW");
  }

  @Test void manualChiefReassignmentAndMutationFailWithoutMasterChiefQualification() {
    assertRejected("H2_CHIEF_QUALIFICATION");
    assertThatThrownBy(() -> edits.patch(task, new SchedulePatchRequest(assignment, target, 0, "qualification test")))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(jdbc.queryForObject("select teacher_id from schedule_assignment where id=?", UUID.class, assignment)).isEqualTo(teacher);
    assertThat(jdbc.queryForObject("select revision_cursor from schedule_run where id=?", Integer.class, run)).isZero();
  }

  @Test void manualExpiredQualificationIsRejectedButExpiryDateItselfIsAllowed() {
    qualifyTarget();
    jdbc.update("update teacher set qualification_expiry=? where id=?", DAY.minusDays(1), target);
    assertRejected("H2_QUALIFICATION_EXPIRED");
    jdbc.update("update teacher set qualification_expiry=? where id=?", DAY, target);
    entityManager.clear();
    assertThat(edits.checkConflicts(task, new ConflictCheckRequest(assignment, target)).allowed()).isTrue();
  }

  @Test void manualDailyCapCountsOtherAssignmentsInSameCurrentRun() {
    qualifyTarget();
    UUID afternoon = exam(task, 15);
    jdbc.update("insert into schedule_assignment(org_id,schedule_run_id,exam_id,classroom_id,teacher_id,role,exam_date,start_time,end_time) values (?,?,?,?,?,'main',?,'15:00','16:00')", org, run, afternoon, room, target, DAY);
    assertRejected("H2_MAX_DAILY_SESSIONS");
  }

  @Test void legacyPublishedDutyWithoutProjectionConsumesCapacityAndOccupancy() {
    qualifyTarget();
    publishedLegacy(target, 8);
    assertRejected("H2_MAX_DAILY_SESSIONS");
    var source = fixture().teachers().stream().filter(t -> t.id().equals(target)).findFirst().orElseThrow();
    assertThat(source.publishedDuties()).hasSize(1);
    assertThat(source.publishedDuties().getFirst().start()).isEqualTo(DAY.atTime(8, 0));
    jdbc.update("update teacher set max_daily_sessions=4 where id=?", target);
    entityManager.clear();
    assertThat(edits.checkConflicts(task, new ConflictCheckRequest(assignment, target)).allowed()).isTrue();
    publishedLegacy(target, 9);
    assertRejected("H1_TEACHER_TIME_OVERLAP");
  }

  private ScheduleFixture fixture() {
    return ReflectionTestUtils.invokeMethod(jobs, "buildFixtureFromTask", org, tasks.findByOrgIdAndId(org, task).orElseThrow());
  }
  private void qualifyTarget() { jdbc.update("update teacher set qualifications='[\"INVIGILATOR\",\"CHIEF\"]'::jsonb where id=?", target); }
  private void assertRejected(String code) {
    var result = edits.checkConflicts(task, new ConflictCheckRequest(assignment, target));
    assertThat(result.allowed()).isFalse();
    assertThat(result.conflicts()).extracting(c -> c.code()).contains(code);
  }
  private UUID exam(UUID taskId, int hour) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into exam(id,org_id,exam_task_id,subject_name,exam_date,start_time,end_time,settings) values (?,?,?,'语文',?,?,?,'{\"seatDemand\":30,\"supervisorsPerRoom\":1}'::jsonb)", id, org, taskId, DAY, LocalTime.of(hour, 0), LocalTime.of(hour + 1, 0));
    jdbc.update("insert into exam_room(org_id,exam_id,classroom_id) values (?,?,?)", org, id, room);
    return id;
  }
  private void leave(String status) {
    jdbc.update("insert into exam_plan_leave_request(org_id,teacher_id,starts_at,ends_at,reason,status,requested_by) values (?,?,'2027-01-12T01:00:00Z','2027-01-12T02:00:00Z','测试请假',?,?)", org, target, status, actor);
  }
  private void publishedLegacy(UUID assignedTeacher, int hour) {
    UUID otherTask = UUID.randomUUID(), otherRun = UUID.randomUUID();
    jdbc.update("insert into exam_task(id,org_id,task_no,name,status,published_at) values (?,?,?,'已发布旧排班','published',now())", otherTask, org, "T-" + otherTask);
    UUID otherExam = exam(otherTask, hour);
    jdbc.update("insert into schedule_run(id,org_id,exam_task_id,status,finished_at) values (?,?,?,'succeeded',now())", otherRun, org, otherTask);
    jdbc.update("insert into schedule_assignment(org_id,schedule_run_id,exam_id,classroom_id,teacher_id,role,exam_date,start_time,end_time) values (?,?,?,?,?,'main',?,?,?)", org, otherRun, otherExam, room, assignedTeacher, DAY, LocalTime.of(hour, 0), LocalTime.of(hour + 1, 0));
  }
}
