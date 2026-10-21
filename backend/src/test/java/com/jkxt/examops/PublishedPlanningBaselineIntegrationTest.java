package com.jkxt.examops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.jkxt.common.tenant.TenantContext;
import com.jkxt.examops.api.PlanningJobDtos.PlanningJobRequest;
import com.jkxt.examops.joint.DeterministicJointPlanner;
import com.jkxt.examops.joint.JointPlanningModel.PlanningControl;
import com.jkxt.examops.joint.JointPlanningModel.ResultStatus;
import com.jkxt.examops.joint.JointPlanningModel.TimeInterval;
import com.jkxt.examops.service.ExamPlanPlanningService;
import com.jkxt.examops.service.JointPlanningSnapshotLoader;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/** Real PostgreSQL baseline and real solvers; inline dispatch is not an asynchronous lifecycle test. */
@SpringBootTest(properties = {
    "spring.datasource.hikari.maximum-pool-size=2", "spring.datasource.hikari.minimum-idle=0"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PublishedPlanningBaselineIntegrationTest {
  private static final Instant OLD = Instant.parse("2088-06-05T00:00:00Z");
  private static final Instant CURRENT = OLD.plusSeconds(7200);
  @Autowired JdbcTemplate jdbc;
  @Autowired JointPlanningSnapshotLoader loader;
  @Autowired ExamPlanPlanningService planning;
  @Autowired com.jkxt.examops.service.PublishedResourceBaseline publishedBaseline;
  @MockitoBean(name = "scheduleTaskExecutor") Executor executor;
  private UUID org, actor, teacher, room, plan, revision, historical, active;

  @ParameterizedTest
  @ValueSource(strings = {"PUBLISHED", "EXECUTING", "ARCHIVED", "DUTY_CANCELLED", "DUTY_DECLINED", "SESSION_CANCELLED", "REJECTED"})
  void roomlessPublishedDutiesKeepTeacherOccupancyWithoutInventingACampusOrRoom(String state) {
    replaceExternalWithRoomlessDuty(state);
    var baseline = publishedBaseline.load(org, plan, null);
    boolean live = state.equals("PUBLISHED") || state.equals("EXECUTING");
    assertThat(baseline.rooms()).isEmpty();
    assertThat(baseline.teacherDuties()).hasSize(live ? 1 : 0);
    if (live) {
      var duty = baseline.teacherDuties().getFirst();
      assertThat(duty.teacherId()).isEqualTo(teacher);
      assertThat(duty.startsAt()).isEqualTo(CURRENT);
      assertThat(duty.endsAt()).isEqualTo(CURRENT.plusSeconds(3600));
      assertThat(duty.campus()).isNull();
      UUID otherOrg = UUID.randomUUID();
      jdbc.update("insert into organization(id,name,slug) values (?,'Other school',?)", otherOrg, "roomless-other-" + otherOrg);
      assertThat(publishedBaseline.load(otherOrg, null, null).teacherDuties()).isEmpty();
      assertThat(publishedBaseline.load(otherOrg, null, null).rooms()).isEmpty();
    }
  }

  @ParameterizedTest
  @CsvSource({"INVIGILATOR_ONLY,DAILY", "JOINT,DAILY", "INVIGILATOR_ONLY,OVERLAP", "JOINT,OVERLAP"})
  void roomlessPublishedDutyProtectsDailyAllowanceAndTimeInBothGenerationPaths(String mode, String risk) {
    replaceExternalWithRoomlessDuty("PUBLISHED");
    jdbc.update("update teacher set max_daily_sessions=? where id=?", risk.equals("DAILY") ? 1 : 10, teacher);
    Instant later = risk.equals("DAILY") ? CURRENT.plusSeconds(7200) : CURRENT;
    if (mode.equals("JOINT")) {
      moveDemand(seedJointInput(), later);
      var result = new DeterministicJointPlanner().solve(loader.load(org, revision).snapshot(), PlanningControl.unlimited(0));
      assertThat(result.status()).isEqualTo(ResultStatus.INFEASIBLE);
      assertThat(result.duties()).isEmpty();
    } else {
      seedSession(revision, later, false);
      var job = planning.start(plan, revision, new PlanningJobRequest(mode, 0L, true, 120),
          "roomless-baseline-" + UUID.randomUUID(), actor);
      var finished = planning.get(job.id());
      assertThat(finished.status()).as("job diagnostics: %s", finished.diagnostics()).isEqualTo("FAILED");
      assertThat(finished.stage()).isEqualTo("UNRESOLVED");
    }
    assertThat(jdbc.queryForObject("select count(*) from exam_plan_duty_assignment where revision_id=?", Integer.class, revision)).isZero();
  }

  @Test
  void aDutyWithAnExplicitCancelledRoomIsNotTreatedAsARoomlessDuty() {
    UUID owner = jdbc.queryForObject("select plan_id from exam_plan_revision where id=?", UUID.class, active);
    UUID cancelled = newRevision(owner, 3);
    seedSession(cancelled, CURRENT, true);
    jdbc.update("update exam_plan_room_assignment set status='CANCELLED' where revision_id=?", cancelled);
    jdbc.update("update exam_plan_revision set status='PUBLISHED' where id=?", cancelled);
    jdbc.update("update exam_plan set active_revision_id=? where id=?", cancelled, owner);
    var baseline = publishedBaseline.load(org, plan, null);
    assertThat(baseline.rooms()).isEmpty();
    assertThat(baseline.teacherDuties()).isEmpty();
  }

  private void replaceExternalWithRoomlessDuty(String state) {
    UUID owner = jdbc.queryForObject("select plan_id from exam_plan_revision where id=?", UUID.class, active);
    UUID roomless = newRevision(owner, 3), session = UUID.randomUUID(), requirement = UUID.randomUUID(), duty = UUID.randomUUID();
    Timestamp from = Timestamp.from(CURRENT), to = Timestamp.from(CURRENT.plusSeconds(3600));
    jdbc.update("insert into exam_plan_session(id,org_id,revision_id,session_code,name,starts_at,ends_at,status) values (?,?,?,'ROOMLESS','Session-wide duty',?,?,?)",
        session, org, roomless, from, to, state.equals("SESSION_CANCELLED") ? "CANCELLED" : "PLANNED");
    jdbc.update("insert into exam_plan_duty_requirement(id,org_id,revision_id,session_id,duty_role,required_count) values (?,?,?,?,'ROAMING',1)",
        requirement, org, roomless, session);
    jdbc.update("insert into exam_plan_duty_assignment(id,org_id,revision_id,requirement_id,teacher_id,starts_at,ends_at,duty_role,status) values (?,?,?,?,?,?,?,'ROAMING',?)",
        duty, org, roomless, requirement, teacher, from, to,
        state.equals("DUTY_CANCELLED") ? "CANCELLED" : state.equals("DUTY_DECLINED") ? "DECLINED" : "ACCEPTED");
    if (state.equals("REJECTED")) jdbc.update("insert into exam_plan_duty_response(org_id,assignment_id,teacher_id,status,reason) values (?,?,?,'REJECTED','Synthetic rejection')",
        org, duty, teacher);
    jdbc.update("update exam_plan_revision set status=? where id=?",
        state.equals("ARCHIVED") ? "ARCHIVED" : state.equals("EXECUTING") ? "EXECUTING" : "PUBLISHED", roomless);
    jdbc.update("update exam_plan set active_revision_id=? where id=?", roomless, owner);
  }

  @ParameterizedTest
  @ValueSource(strings = {"APPROVED", "PENDING", "REJECTED", "CANCELLED", "TOUCHING_BEFORE", "TOUCHING_AFTER"})
  void invigilatorOnlyHonorsApprovedLeaveAtGenerationNotOnlyAtPublication(String leaveState) {
    Instant start = leaveState.equals("TOUCHING_BEFORE") ? OLD.minusSeconds(3600)
        : leaveState.equals("TOUCHING_AFTER") ? OLD.plusSeconds(3600) : OLD;
    String status = leaveState.startsWith("TOUCHING") ? "APPROVED" : leaveState;
    jdbc.update("insert into exam_plan_leave_request(org_id,teacher_id,starts_at,ends_at,reason,status,requested_by,decided_by,decided_at) values (?,?,?,?,?,?,?,?,?)",
        org, teacher, Timestamp.from(start), Timestamp.from(start.plusSeconds(3600)), "Synthetic approved leave", status,
        actor, status.equals("PENDING") ? null : actor, status.equals("PENDING") ? null : Timestamp.from(OLD.minusSeconds(86400)));
    seedSession(revision, OLD, false);
    var job = planning.start(plan, revision, new PlanningJobRequest("INVIGILATOR_ONLY", 0L, true, 120),
        "approved-leave-" + UUID.randomUUID(), actor);
    boolean unavailable = leaveState.equals("APPROVED");
    assertThat(planning.get(job.id()).status()).isEqualTo(unavailable ? "INFEASIBLE" : "SUCCEEDED");
    assertThat(jdbc.queryForObject("select count(*) from exam_plan_duty_assignment where revision_id=?", Integer.class, revision))
        .isEqualTo(unavailable ? 0 : 1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"TEACHER_NUMBER", "TEACHER_UUID"})
  void invigilatorOnlyHonorsHardLeaveWithEitherSupportedTeacherIdentifier(String resourceType) {
    jdbc.update("insert into exam_plan_constraint(org_id,revision_id,constraint_type,severity,resource_type,resource_id,expression) values (?,?,'TEACHER_LEAVE','HARD','teacher',?,?::jsonb)",
        org, revision, resourceType.equals("TEACHER_UUID") ? teacher.toString() : "T1",
        "{\"approved\":true,\"startsAt\":\"" + OLD + "\",\"endsAt\":\"" + OLD.plusSeconds(3600) + "\"}");
    seedSession(revision, OLD, false);
    var job = planning.start(plan, revision, new PlanningJobRequest("INVIGILATOR_ONLY", 0L, true, 120),
        "hard-leave-" + UUID.randomUUID(), actor);
    assertThat(planning.get(job.id()).status()).isEqualTo("INFEASIBLE");
    assertThat(jdbc.queryForObject("select count(*) from exam_plan_duty_assignment where revision_id=?", Integer.class, revision)).isZero();
  }

  @Test
  void invigilatorOnlyCannotReuseLegacyPublishedTeacherDailyAllowance() {
    seedLegacyPublication(OLD);
    jdbc.update("update teacher set max_daily_sessions=2 where id=?", teacher);
    seedSession(revision, OLD.plusSeconds(14400), false);
    var started = planning.start(plan, revision, new PlanningJobRequest("INVIGILATOR_ONLY", 0L, true, 120),
        "legacy-baseline-" + UUID.randomUUID(), actor);
    assertThat(planning.get(started.id()).status()).isEqualTo("FAILED");
    assertThat(jdbc.queryForObject("select count(*) from exam_plan_duty_assignment where revision_id=?", Integer.class, revision)).isZero();
  }

  @Test
  void validLegacyPublicationPointerWinsOverAnotherSuccessfulRun() {
    UUID task = seedLegacyPublication(OLD);
    UUID publishedRun = jdbc.queryForObject("select id from schedule_run where exam_task_id=?", UUID.class, task);
    jdbc.update("insert into schedule_run(org_id,exam_task_id,status) values (?,?,'succeeded')", org, task);
    jdbc.update("insert into notification(org_id,exam_task_id,type,title,payload) values (?,?,'schedule_published','Confirmed',?::jsonb)",
        org, task, "{\"runId\":\"" + publishedRun + "\"}");
    var baseline = publishedBaseline.load(org, plan, null);
    assertThat(baseline.teacherDuties()).hasSize(2);
    assertThat(baseline.teacherDuties()).extracting(duty -> duty.startsAt()).containsExactlyInAnyOrder(OLD, CURRENT);
  }

  @Test
  void invalidLegacyPublicationPointerCannotSilentlyFallBackToTheOnlySuccessfulRun() {
    UUID task = seedLegacyPublication(OLD);
    jdbc.update("insert into notification(org_id,exam_task_id,type,title,payload) values (?,?,'schedule_published','Invalid',?::jsonb)",
        org, task, "{\"runId\":\"" + UUID.randomUUID() + "\"}");
    assertThatThrownBy(() -> publishedBaseline.load(org, plan, null))
        .isInstanceOfSatisfying(com.jkxt.common.web.ApiException.class,
            e -> assertThat(e.getCode()).isEqualTo("LEGACY_PUBLISHED_BASELINE_AMBIGUOUS"));
  }

  @Test
  void projectedLegacyTaskIsCountedOnceAndExcludingTaskExcludesItsProjection() {
    UUID task = seedLegacyPublication(CURRENT);
    UUID owner = jdbc.queryForObject("select plan_id from exam_plan_revision where id=?", UUID.class, active);
    jdbc.update("update exam_plan set legacy_exam_task_id=? where id=?", task, owner);
    assertThat(publishedBaseline.load(org, plan, null).teacherDuties()).hasSize(1);
    assertThat(publishedBaseline.load(org, null, task).teacherDuties()).isEmpty();
    assertThat(publishedBaseline.load(org, null, task).rooms()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"PUBLISHED", "EXECUTING", "ARCHIVED"})
  void cancelledCurrentProjectionMustNotResurrectLegacyOccupancyButArchivedProjectionDoesNotMaskIt(String status) {
    UUID task = seedLegacyPublication(OLD);
    UUID owner = jdbc.queryForObject("select plan_id from exam_plan_revision where id=?", UUID.class, active);
    UUID cancelled = newRevision(owner, 3);
    seedSession(cancelled, OLD, true);
    jdbc.update("update exam_plan_session set status='CANCELLED' where revision_id=?", cancelled);
    jdbc.update("update exam_plan_room_assignment set status='CANCELLED' where revision_id=?", cancelled);
    jdbc.update("update exam_plan_duty_assignment set status='CANCELLED' where revision_id=?", cancelled);
    jdbc.update("update exam_plan_revision set status=? where id=?", status, cancelled);
    jdbc.update("update exam_plan set legacy_exam_task_id=?,active_revision_id=? where id=?", task, cancelled, owner);
    var baseline = publishedBaseline.load(org, plan, null);
    if (status.equals("ARCHIVED")) {
      assertThat(baseline.teacherDuties()).hasSize(1);
      assertThat(baseline.rooms()).hasSize(1);
      assertThat(baseline.teacherDuties().getFirst().startsAt()).isEqualTo(OLD);
    } else {
      assertThat(baseline.teacherDuties()).isEmpty();
      assertThat(baseline.rooms()).isEmpty();
    }
  }

  @Test
  void anotherTenantPublishedLegacyAmbiguityDoesNotBlockThisSchool() {
    UUID foreignOrg = UUID.randomUUID();
    jdbc.update("insert into organization(id,name,slug) values (?,'Other school',?)", foreignOrg, "other-" + foreignOrg);
    jdbc.update("insert into exam_task(org_id,task_no,name,status) values (?,'UNRESOLVED','Foreign published','published')", foreignOrg);
    assertThat(publishedBaseline.load(org, plan, null).teacherDuties()).hasSize(1);
  }

  @Test
  void jointCannotSpendDailyAllowanceAlreadyUsedByAnotherPublishedPlan() {
    UUID demand = seedJointInput();
    jdbc.update("update teacher set max_daily_sessions=1 where id=?", teacher);
    moveDemand(demand, OLD.plusSeconds(14400));
    var result = new DeterministicJointPlanner().solve(loader.load(org, revision).snapshot(), PlanningControl.unlimited(0));
    assertThat(result.status()).isEqualTo(ResultStatus.INFEASIBLE);
    assertThat(result.duties()).isEmpty();
  }

  @Test
  void legacyPublishedRoomWithoutProjectionRemainsOccupied() {
    seedLegacyPublication(OLD);
    seedJointInput();
    var result = new DeterministicJointPlanner().solve(loader.load(org, revision).snapshot(), PlanningControl.unlimited(0));
    assertThat(result.status()).isEqualTo(ResultStatus.INFEASIBLE);
    assertThat(result.rooms()).isEmpty();
  }

  @Test
  void legacyPublishedDutyWithoutProjectionCountsTowardNonoverlappingDailyLimit() {
    seedLegacyPublication(OLD);
    UUID demand = seedJointInput();
    jdbc.update("update teacher set max_daily_sessions=2 where id=?", teacher);
    moveDemand(demand, OLD.plusSeconds(14400));
    var result = new DeterministicJointPlanner().solve(loader.load(org, revision).snapshot(), PlanningControl.unlimited(0));
    assertThat(result.status()).isEqualTo(ResultStatus.INFEASIBLE);
    assertThat(result.duties()).isEmpty();
  }

  @Test
  void doesNotGuessWhichOldSuccessfulRunWasPublishedWhenThePointerIsMissing() {
    UUID task = seedLegacyPublication(OLD);
    jdbc.update("insert into schedule_run(org_id,exam_task_id,status) values (?,?,'succeeded')", org, task);
    seedJointInput();
    assertThatThrownBy(() -> loader.load(org, revision))
        .isInstanceOfSatisfying(com.jkxt.common.web.ApiException.class,
            e -> assertThat(e.getCode()).isEqualTo("LEGACY_PUBLISHED_BASELINE_AMBIGUOUS"));
  }

  private void moveDemand(UUID demand, Instant start) {
    jdbc.update("update exam_plan_demand_window set starts_at=?,ends_at=? where demand_id=?",
        Timestamp.from(start), Timestamp.from(start.plusSeconds(3600)), demand);
  }

  private UUID seedLegacyPublication(Instant start) {
    UUID task = UUID.randomUUID(), run = UUID.randomUUID(), exam = UUID.randomUUID();
    var local = start.atZone(java.time.ZoneId.of("Asia/Shanghai"));
    jdbc.update("insert into exam_task(id,org_id,task_no,name,status,published_at) values (?,?,?,'Legacy published','published',?)",
        task, org, task.toString(), Timestamp.from(start.minusSeconds(86400)));
    jdbc.update("insert into schedule_run(id,org_id,exam_task_id,status) values (?,?,?,'succeeded')", run, org, task);
    jdbc.update("insert into exam(id,org_id,exam_task_id,subject_name,exam_date,start_time,end_time) values (?,?,?,'Math',?,?,?)",
        exam, org, task, local.toLocalDate(), local.toLocalTime(), local.plusHours(1).toLocalTime());
    jdbc.update("insert into schedule_assignment(org_id,schedule_run_id,exam_id,classroom_id,teacher_id,role,exam_date,start_time,end_time) values (?,?,?,?,?,'main',?,?,?)",
        org, run, exam, room, teacher, local.toLocalDate(), local.toLocalTime(), local.plusHours(1).toLocalTime());
    return task;
  }

  @BeforeEach
  void fixture() {
    org = UUID.randomUUID(); actor = UUID.randomUUID(); teacher = UUID.randomUUID(); room = UUID.randomUUID();
    jdbc.update("insert into organization(id,name,slug) values (?,'Planning baseline',?)", org, "baseline-" + org);
    jdbc.update("""
        insert into app_user(id,org_id,username,display_name,password_hash,role,must_change_password)
        values (?,?,'planner','Planner','test-only-unused','ADMIN',false)
        """, actor, org);
    jdbc.update("""
        insert into teacher(id,org_id,teacher_no,name,gender,qualifications,max_daily_sessions)
        values (?,?,'T1','Chief','unknown','["INVIGILATOR","CHIEF"]'::jsonb,3)
        """, teacher, org);
    jdbc.update("insert into classroom(id,org_id,code,name,capacity,exam_capacity) values (?,?,'R1','Room',1,1)", room, org);
    TenantContext.set(org);
    doAnswer(invocation -> { invocation.getArgument(0, Runnable.class).run(); return null; })
        .when(executor).execute(any(Runnable.class));
    UUID otherPlan = newPlan();
    historical = newRevision(otherPlan, 1);
    seedSession(historical, OLD, true);
    jdbc.update("update exam_plan_revision set status='PUBLISHED' where id=?", historical);
    active = newRevision(otherPlan, 2);
    seedSession(active, CURRENT, true);
    jdbc.update("update exam_plan_revision set status='PUBLISHED' where id=?", active);
    jdbc.update("update exam_plan set status='PUBLISHED',active_revision_id=? where id=?", active, otherPlan);
    plan = newPlan();
    revision = newRevision(plan, 1);
    jdbc.update("update exam_plan set active_revision_id=? where id=?", revision, plan);
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
    // Immutable historical rows remain only in this run's disposable database; never disable triggers.
  }

  @ParameterizedTest
  @ValueSource(strings = {"PUBLISHED", "EXECUTING"})
  void jointLoadsOnlyCurrentOccupancyAndCanReuseTheReleasedSlot(String activeStatus) {
    if (activeStatus.equals("EXECUTING")) jdbc.update("update exam_plan_revision set status='EXECUTING' where id=?", active);
    UUID demand = seedJointInput();
    var snapshot = loader.load(org, revision).snapshot();
    var currentWindow = new TimeInterval(CURRENT, CURRENT.plusSeconds(3600));
    assertThat(snapshot.rooms()).hasSize(1);
    assertThat(snapshot.teachers()).hasSize(1);
    assertThat(snapshot.rooms().getFirst().unavailableWindows()).containsExactly(currentWindow);
    assertThat(snapshot.teachers().getFirst().unavailableWindows()).containsExactly(currentWindow);
    var planner = new DeterministicJointPlanner();
    var result = planner.solve(snapshot, new PlanningControl(0L, null, () -> false, 4));
    assertThat(result.status()).isEqualTo(ResultStatus.SUCCESS);
    assertThat(result.duties()).extracting(duty -> duty.teacherId()).containsExactly(teacher);
    assertThat(jdbc.queryForObject("select status from exam_plan_revision where id=?", String.class, historical))
        .isEqualTo("PUBLISHED");

    jdbc.update("update exam_plan_demand_window set starts_at=?,ends_at=? where demand_id=?",
        Timestamp.from(CURRENT), Timestamp.from(CURRENT.plusSeconds(3600)), demand);
    var blocked = planner.solve(loader.load(org, revision).snapshot(), new PlanningControl(0L, null, () -> false, 4));
    assertThat(blocked.status()).isEqualTo(ResultStatus.INFEASIBLE);
    assertThat(blocked.duties()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 4})
  void invigilatorPlanningIgnoresHistoricalOverlapAndHistoricalDailyLoad(int targetHour) {
    // At hour 0, allow three duties so only obsolete interval occupancy can cause rejection.
    // At hour 4, no intervals overlap: obsolete daily counting alone would exceed the limit of two.
    jdbc.update("update teacher set max_daily_sessions=? where id=?", targetHour == 0 ? 3 : 2, teacher);
    seedSession(revision, OLD.plusSeconds(targetHour * 3600L), false);
    var started = planning.start(plan, revision, new PlanningJobRequest("INVIGILATOR_ONLY", 0L, true, 120),
        "baseline-" + UUID.randomUUID(), actor);
    var finished = planning.get(started.id());
    assertThat(finished.status()).as("job diagnostics: %s", finished.diagnostics()).isEqualTo("SUCCEEDED");
    assertThat(jdbc.queryForList("select teacher_id from exam_plan_duty_assignment where revision_id=?", UUID.class, revision))
        .containsExactly(teacher);
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 4})
  void invigilatorPlanningStillHonorsCurrentOverlapAndCurrentDailyLoad(int targetHour) {
    jdbc.update("update teacher set max_daily_sessions=? where id=?", targetHour == 2 ? 3 : 1, teacher);
    seedSession(revision, OLD.plusSeconds(targetHour * 3600L), false);
    var started = planning.start(plan, revision, new PlanningJobRequest("INVIGILATOR_ONLY", 0L, true, 120),
        "baseline-" + UUID.randomUUID(), actor);
    var finished = planning.get(started.id());
    assertThat(finished.status()).isEqualTo("FAILED");
    assertThat(finished.stage()).isEqualTo("UNRESOLVED");
    assertThat(jdbc.queryForObject("select count(*) from exam_plan_duty_assignment where revision_id=?", Integer.class, revision))
        .isZero();
  }

  private UUID newPlan() {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into exam_plan(id,org_id,plan_no,name) values (?,?,?,'Baseline')", id, org, id.toString());
    return id;
  }

  private UUID newRevision(UUID owner, int number) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into exam_plan_revision(id,org_id,plan_id,revision_no) values (?,?,?,?)", id, org, owner, number);
    return id;
  }

  private void seedSession(UUID targetRevision, Instant starts, boolean assign) {
    UUID session = UUID.randomUUID(), allocation = UUID.randomUUID(), requirement = UUID.randomUUID();
    Timestamp start = Timestamp.from(starts), end = Timestamp.from(starts.plusSeconds(3600));
    jdbc.update("""
        insert into exam_plan_session(id,org_id,revision_id,session_code,name,starts_at,ends_at,candidate_count)
        values (?,?,?,'S1','Baseline exam',?,?,0)
        """, session, org, targetRevision, start, end);
    jdbc.update("""
        insert into exam_plan_room_assignment(id,org_id,revision_id,session_id,classroom_id,starts_at,ends_at,planned_capacity,allocated_candidates)
        values (?,?,?,?,?,?,?,1,0)
        """, allocation, org, targetRevision, session, room, start, end);
    jdbc.update("""
        insert into exam_plan_duty_requirement(id,org_id,revision_id,session_id,room_assignment_id,duty_role,required_count,qualification)
        values (?,?,?,?,?,'CHIEF',1,'{"requiredQualifications":["INVIGILATOR","CHIEF"]}'::jsonb)
        """, requirement, org, targetRevision, session, allocation);
    if (assign) jdbc.update("""
        insert into exam_plan_duty_assignment(org_id,revision_id,requirement_id,teacher_id,starts_at,ends_at,duty_role)
        values (?,?,?,?,?,?,'CHIEF')
        """, org, targetRevision, requirement, teacher, start, end);
  }

  private UUID seedJointInput() {
    UUID demand = jdbc.queryForObject("""
        insert into exam_plan_exam_demand(org_id,revision_id,demand_code,name,duration_minutes,candidate_count,
          invigilator_count,min_rooms,max_rooms,split_allowed)
        values (?,?,'D1','Joint baseline',60,0,1,1,1,false) returning id
        """, UUID.class, org, revision);
    jdbc.update("""
        insert into exam_plan_demand_window(org_id,revision_id,demand_id,window_code,starts_at,ends_at)
        values (?,?,?,'W1',?,?)
        """, org, revision, demand, Timestamp.from(OLD), Timestamp.from(OLD.plusSeconds(3600)));
    jdbc.update("""
        insert into exam_plan_room_pool(org_id,revision_id,classroom_id,campus_code,exam_capacity,status)
        values (?,?,?,'MAIN',1,'INCLUDED')
        """, org, revision, room);
    jdbc.update("""
        insert into exam_plan_teacher_pool(org_id,revision_id,teacher_id,eligible_roles,max_daily_sessions,min_rest_minutes,status)
        values (?,?,?,'["CHIEF","INVIGILATOR"]'::jsonb,3,0,'INCLUDED')
        """, org, revision, teacher);
    return demand;
  }
}
