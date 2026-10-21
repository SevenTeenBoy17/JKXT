package com.jkxt.examops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiException;
import com.jkxt.examops.api.PlanningJobDtos.PlanningJobRequest;
import com.jkxt.examops.api.PlanningJobDtos.PlanningJobView;
import com.jkxt.examops.service.ExamPlanPlanningService;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ExamPlanJointPlanningIntegrationTest {

  private static final UUID ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000204");
  private static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Shanghai");

  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired ExamPlanPlanningService planningService;
  @MockitoBean(name = "scheduleTaskExecutor") Executor executor;

  private final Queue<Runnable> queuedTasks = new ConcurrentLinkedQueue<>();
  private final List<UUID> planIds = new ArrayList<>();
  private final List<UUID> jobIds = new ArrayList<>();
  private final Map<UUID, TeacherState> teacherStates = new LinkedHashMap<>();
  private UUID foreignOrgId;

  @BeforeEach
  void setUp() {
    doAnswer(invocation -> {
      queuedTasks.add(invocation.getArgument(0, Runnable.class));
      return null;
    }).when(executor).execute(any(Runnable.class));
    TenantContext.set(ORG_ID);
  }

  @AfterEach
  void cleanup() {
    TenantContext.clear();
    queuedTasks.clear();
    for (UUID planId : planIds) {
      jdbcTemplate.update("update exam_plan_revision set status='CANCELLED' where plan_id=? and status not in ('PUBLISHED','EXECUTING','CLOSED','ARCHIVED')", planId);
      jdbcTemplate.update("delete from exam_plan where id=?", planId);
    }
    jobIds.forEach(jobId -> jdbcTemplate.update(
        "delete from api_idempotency_record where resource_id=?", jobId));
    teacherStates.forEach((id, state) -> jdbcTemplate.update("""
        update teacher set qualifications=?::jsonb, qualification_expiry=?, max_daily_sessions=?
        where id=?
        """, state.qualifications(), state.qualificationExpiry(), state.maxDailySessions(), id));
    if (foreignOrgId != null) jdbcTemplate.update("delete from organization where id=?", foreignOrgId);
  }

  @Test
  void jointSuccessIsAtomicDeterministicAndKeepsInvigilatorOnlyCompatibility() {
    Fixture fixture = seedFixture(2, 2, 2);

    PlanningJobView first = run(fixture, "JOINT", 42L, 120, "joint-success-1");
    assertThat(first.status()).isEqualTo("SUCCEEDED");
    assertThat(first.stage()).isEqualTo("COMPLETE");
    assertThat(first.inputHash()).hasSize(64);
    assertThat(first.resultHash()).hasSize(64);
    assertThat(first.solverVersion()).startsWith("joint-");
    assertThat(first.iterationCount()).isPositive();
    assertThat(first.requestHash()).hasSize(64);
    assertThat(first.stats().path("partialAssignmentsExposed").asBoolean()).isFalse();
    assertThat(first.stats().path("hardConstraintViolations").asInt()).isZero();

    assertThat(count("exam_plan_session", fixture.revisionId())).isOne();
    assertThat(count("exam_plan_room_assignment", fixture.revisionId())).isOne();
    assertThat(count("exam_plan_class_session_assignment", fixture.revisionId())).isOne();
    assertThat(count("exam_plan_seat_assignment", fixture.revisionId())).isEqualTo(2);
    assertThat(count("exam_plan_duty_assignment", fixture.revisionId())).isEqualTo(2);
    assertThat(jdbcTemplate.queryForObject("""
        select count(*) from exam_plan_session
        where revision_id=? and source_type='JOINT' and demand_id is not null
        """, Integer.class, fixture.revisionId())).isOne();
    assertThat(jdbcTemplate.queryForObject("""
        select count(*) from exam_plan_seat_assignment
        where revision_id=? and source_type='JOINT' and candidate_id is not null
          and seat_id is not null and starts_at is not null and ends_at is not null
        """, Integer.class, fixture.revisionId())).isEqualTo(2);

    PlanningJobView replay = run(fixture, "JOINT", 42L, 120, "joint-success-2");
    assertThat(replay.status()).isEqualTo("SUCCEEDED");
    assertThat(replay.inputHash()).isEqualTo(first.inputHash());
    assertThat(replay.resultHash()).isEqualTo(first.resultHash());
    assertThat(count("exam_plan_session", fixture.revisionId())).isOne();
    assertThat(count("exam_plan_seat_assignment", fixture.revisionId())).isEqualTo(2);

    PlanningJobView legacyMode = run(
        fixture, "INVIGILATOR_ONLY", 42L, 120, "joint-invigilator-regression");
    assertThat(legacyMode.status()).isEqualTo("SUCCEEDED");
    assertThat(legacyMode.solverVersion()).isEqualTo("INVIGILATOR_ONLY");
    assertThat(count("exam_plan_session", fixture.revisionId())).isOne();
    assertThat(count("exam_plan_seat_assignment", fixture.revisionId())).isEqualTo(2);
    assertThat(count("exam_plan_duty_assignment", fixture.revisionId())).isEqualTo(2);
  }

  @Test
  void infeasibleDeadlineAndCancellationNeverPersistPartialOutputs() {
    Fixture infeasible = seedFixture(1, 2, 2);
    PlanningJobView noStaff = run(infeasible, "JOINT", 7L, 120, "joint-infeasible");
    assertThat(noStaff.status()).isEqualTo("INFEASIBLE");
    assertThat(noStaff.diagnostics().findValuesAsText("code")).isNotEmpty();
    assertThat(noStaff.inputHash()).hasSize(64);
    assertThat(noStaff.resultHash()).hasSize(64);
    assertThat(noStaff.stats().path("partialAssignmentsExposed").asBoolean()).isFalse();
    assertNoOutputs(infeasible.revisionId());

    Fixture deadline = seedFixture(2, 2, 2);
    PlanningJobView timedOut = run(deadline, "JOINT", 8L, 0, "joint-deadline");
    assertThat(timedOut.status()).isEqualTo("FAILED");
    assertThat(timedOut.stage()).isEqualTo("DEADLINE_EXCEEDED");
    assertThat(timedOut.diagnostics().findValuesAsText("code"))
        .contains("PLANNING_DEADLINE_EXCEEDED");
    assertThat(timedOut.resultHash()).hasSize(64);
    assertNoOutputs(deadline.revisionId());

    Fixture cancelled = seedFixture(2, 2, 2);
    PlanningJobView queued = start(cancelled, "JOINT", 9L, 120, "joint-cancel");
    PlanningJobView cancelResponse = planningService.cancel(queued.jobId(), ACTOR_ID);
    assertThat(cancelResponse.status()).isEqualTo("CANCELLED");
    drainOne();
    PlanningJobView cancelledJob = planningService.get(queued.jobId());
    assertThat(cancelledJob.status()).isEqualTo("CANCELLED");
    assertThat(jdbcTemplate.queryForObject(
        "select status from exam_plan_revision where id=?", String.class, cancelled.revisionId()))
        .isEqualTo("DRAFT");
    assertNoOutputs(cancelled.revisionId());
  }

  @Test
  void planningStartAndJobReadsAreTenantScoped() {
    Fixture local = seedFixture(2, 2, 2);
    PlanningJobView complete = run(local, "JOINT", 11L, 120, "joint-tenant-local");
    foreignOrgId = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into organization (id, name, slug) values (?, 'Foreign joint org', ?)
        """, foreignOrgId, "foreign-joint-" + foreignOrgId.toString().substring(0, 8));
    UUID foreignPlan = UUID.randomUUID();
    UUID foreignRevision = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into exam_plan (id, org_id, plan_no, name, status)
        values (?, ?, ?, 'Foreign plan', 'DRAFT')
        """, foreignPlan, foreignOrgId, "FP-" + foreignPlan.toString().substring(0, 8));
    jdbcTemplate.update("""
        insert into exam_plan_revision (id, org_id, plan_id, revision_no, status, source_type)
        values (?, ?, ?, 1, 'DRAFT', 'API')
        """, foreignRevision, foreignOrgId, foreignPlan);
    jdbcTemplate.update("update exam_plan set active_revision_id=? where id=?", foreignRevision, foreignPlan);

    assertThatThrownBy(() -> planningService.start(
        foreignPlan, foreignRevision, new PlanningJobRequest("JOINT", 1L, true, 120),
        "cross-tenant-start", ACTOR_ID))
        .isInstanceOfSatisfying(ApiException.class,
            exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

    TenantContext.set(foreignOrgId);
    assertThatThrownBy(() -> planningService.get(complete.jobId()))
        .isInstanceOfSatisfying(ApiException.class,
            exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    TenantContext.set(ORG_ID);
  }

  private PlanningJobView run(
      Fixture fixture,
      String mode,
      long seed,
      int timeoutSeconds,
      String key
  ) {
    PlanningJobView queued = start(fixture, mode, seed, timeoutSeconds, key);
    drainOne();
    return planningService.get(queued.jobId());
  }

  private PlanningJobView start(
      Fixture fixture,
      String mode,
      long seed,
      int timeoutSeconds,
      String key
  ) {
    PlanningJobView job = planningService.start(fixture.planId(), fixture.revisionId(),
        new PlanningJobRequest(mode, seed, true, timeoutSeconds), key + '-' + UUID.randomUUID(), ACTOR_ID);
    jobIds.add(job.jobId());
    assertThat(job.status()).isEqualTo("QUEUED");
    return job;
  }

  private void drainOne() {
    Runnable task = queuedTasks.poll();
    assertThat(task).as("planning task queued after transaction commit").isNotNull();
    task.run();
  }

  private Fixture seedFixture(int teacherCount, int candidateCount, int invigilatorsPerRoom) {
    UUID planId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    planIds.add(planId);
    jdbcTemplate.update("""
        insert into exam_plan (id, org_id, plan_no, name, status, created_by, updated_by)
        values (?, ?, ?, 'Joint integration plan', 'DRAFT', ?, ?)
        """, planId, ORG_ID, "JP-" + planId.toString().substring(0, 12), ACTOR_ID, ACTOR_ID);
    jdbcTemplate.update("""
        insert into exam_plan_revision (
          id, org_id, plan_id, revision_no, status, source_type, title, created_by, updated_by
        ) values (?, ?, ?, 1, 'DRAFT', 'API', 'Joint integration revision', ?, ?)
        """, revisionId, ORG_ID, planId, ACTOR_ID, ACTOR_ID);
    jdbcTemplate.update("update exam_plan set active_revision_id=? where id=?", revisionId, planId);

    UUID classroomId = jdbcTemplate.queryForObject("""
        select id from classroom where org_id=? and status='active' and exam_capacity>=?
        order by code limit 1
        """, UUID.class, ORG_ID, candidateCount);
    UUID classId = jdbcTemplate.queryForObject("""
        select id from school_class where org_id=? and status='active' order by code limit 1
        """, UUID.class, ORG_ID);
    List<UUID> teacherIds = jdbcTemplate.queryForList("""
        select id from teacher where org_id=? and status='active' order by teacher_no limit ?
        """, UUID.class, ORG_ID, teacherCount);
    assertThat(teacherIds).hasSize(teacherCount);
    for (UUID teacherId : teacherIds) {
      teacherStates.computeIfAbsent(teacherId, this::teacherState);
      jdbcTemplate.update("""
          update teacher set qualifications='["INVIGILATOR","CHIEF"]'::jsonb,
            qualification_expiry=null, max_daily_sessions=4 where id=?
          """, teacherId);
    }

    UUID demandId = UUID.randomUUID();
    UUID windowId = UUID.randomUUID();
    UUID roomPoolId = UUID.randomUUID();
    var start = LocalDate.of(2080, 1, 1)
        .plusDays(Math.floorMod(planId.hashCode(), 5_000))
        .atTime(8, 0).atZone(SCHOOL_ZONE).toInstant();
    var end = start.plusSeconds(2 * 60 * 60);
    jdbcTemplate.update("""
        insert into exam_plan_exam_demand (
          id, org_id, revision_id, demand_code, name, duration_minutes, candidate_count,
          invigilator_count, min_rooms, max_rooms, split_allowed, created_by, updated_by
        ) values (?, ?, ?, 'MATH-1', 'Mathematics', 90, ?, ?, 1, 1, false, ?, ?)
        """, demandId, ORG_ID, revisionId, candidateCount, invigilatorsPerRoom, ACTOR_ID, ACTOR_ID);
    jdbcTemplate.update("""
        insert into exam_plan_demand_class (
          org_id, revision_id, demand_id, class_id, expected_candidates, created_by, updated_by
        ) values (?, ?, ?, ?, ?, ?, ?)
        """, ORG_ID, revisionId, demandId, classId, candidateCount, ACTOR_ID, ACTOR_ID);
    jdbcTemplate.update("""
        insert into exam_plan_demand_window (
          id, org_id, revision_id, demand_id, window_code, starts_at, ends_at,
          preference_weight, locked, created_by, updated_by
        ) values (?, ?, ?, ?, 'W-1', ?, ?, 100, false, ?, ?)
        """, windowId, ORG_ID, revisionId, demandId, Timestamp.from(start), Timestamp.from(end),
        ACTOR_ID, ACTOR_ID);

    jdbcTemplate.update("""
        insert into exam_plan_room_pool (
          id, org_id, revision_id, classroom_id, campus_code, exam_capacity,
          features, status, created_by, updated_by
        ) values (?, ?, ?, ?, 'MAIN', ?, '[]'::jsonb, 'INCLUDED', ?, ?)
        """, roomPoolId, ORG_ID, revisionId, classroomId, candidateCount, ACTOR_ID, ACTOR_ID);
    for (int index = 1; index <= candidateCount; index++) {
      jdbcTemplate.update("""
          insert into exam_plan_room_seat (
            org_id, revision_id, room_pool_id, seat_code, row_no, column_no,
            features, status, created_by, updated_by
          ) values (?, ?, ?, ?, 1, ?, '[]'::jsonb, 'ACTIVE', ?, ?)
          """, ORG_ID, revisionId, roomPoolId, "S-" + index, index, ACTOR_ID, ACTOR_ID);
    }
    for (int index = 1; index <= candidateCount; index++) {
      UUID candidateId = UUID.randomUUID();
      String candidateCode = "C-" + index;
      jdbcTemplate.update("""
          insert into exam_plan_candidate (
            id, org_id, revision_id, candidate_code, name, class_id, created_by, updated_by
          ) values (?, ?, ?, ?, ?, ?, ?, ?)
          """, candidateId, ORG_ID, revisionId, candidateCode, "Candidate " + index,
          classId, ACTOR_ID, ACTOR_ID);
      jdbcTemplate.update("""
          insert into exam_plan_enrollment (
            org_id, revision_id, candidate_id, demand_id, status, created_by, updated_by
          ) values (?, ?, ?, ?, 'ENROLLED', ?, ?)
          """, ORG_ID, revisionId, candidateId, demandId, ACTOR_ID, ACTOR_ID);
    }
    for (UUID teacherId : teacherIds) {
      jdbcTemplate.update("""
          insert into exam_plan_teacher_pool (
            org_id, revision_id, teacher_id, eligible_roles, max_daily_sessions,
            min_rest_minutes, preferred_campus, status, created_by, updated_by
          ) values (?, ?, ?, '["INVIGILATOR","CHIEF"]'::jsonb, 4, 0, 'MAIN',
            'INCLUDED', ?, ?)
          """, ORG_ID, revisionId, teacherId, ACTOR_ID, ACTOR_ID);
    }
    return new Fixture(planId, revisionId);
  }

  private TeacherState teacherState(UUID id) {
    Map<String, Object> row = jdbcTemplate.queryForMap("""
        select qualifications::text qualifications, qualification_expiry, max_daily_sessions
        from teacher where id=?
        """, id);
    return new TeacherState(String.valueOf(row.get("qualifications")),
        (Date) row.get("qualification_expiry"), ((Number) row.get("max_daily_sessions")).intValue());
  }

  private int count(String table, UUID revisionId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from " + table + " where revision_id=?", Integer.class, revisionId);
  }

  private void assertNoOutputs(UUID revisionId) {
    assertThat(count("exam_plan_session", revisionId)).isZero();
    assertThat(count("exam_plan_room_assignment", revisionId)).isZero();
    assertThat(count("exam_plan_seat_assignment", revisionId)).isZero();
    assertThat(count("exam_plan_duty_assignment", revisionId)).isZero();
  }

  private record Fixture(UUID planId, UUID revisionId) {
  }

  private record TeacherState(String qualifications, Date qualificationExpiry, int maxDailySessions) {
  }
}
