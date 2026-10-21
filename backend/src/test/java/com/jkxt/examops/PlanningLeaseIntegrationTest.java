package com.jkxt.examops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.jobs.AsyncJobLeaseService;
import com.jkxt.common.jobs.AsyncJobLeaseService.Kind;
import com.jkxt.common.jobs.AsyncJobLeaseService.Lease;
import com.jkxt.common.jobs.AsyncJobLeaseService.LeaseLostException;
import com.jkxt.examops.api.PlanningJobDtos.PlanningJobRequest;
import com.jkxt.examops.api.PlanningJobDtos.PlanningJobView;
import com.jkxt.examops.service.ExamPlanPlanningService;
import java.time.Duration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataIntegrityViolationException;

@SpringBootTest(properties = {
    "jkxt.jobs.queue-timeout-ms=1000", "jkxt.jobs.watchdog-interval-ms=50",
    "jkxt.jobs.heartbeat-interval-ms=50", "jkxt.jobs.lease-duration-ms=1000",
    "spring.datasource.hikari.maximum-pool-size=2", "spring.datasource.hikari.minimum-idle=0"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PlanningLeaseIntegrationTest {
  private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000204");
  @Autowired JdbcTemplate jdbc;
  @Autowired ExamPlanPlanningService planning;
  @Autowired PlatformTransactionManager transactions;
  @MockitoBean(name = "scheduleTaskExecutor") Executor executor;
  @MockitoSpyBean AsyncJobLeaseService leases;
  private final Queue<Runnable> pending = new ConcurrentLinkedQueue<>();
  private UUID plan;
  private UUID revision;
  private UUID teacher;
  private UUID room;

  @BeforeEach
  void setUp() {
    TenantContext.set(ORG);
    doAnswer(invocation -> { pending.add(invocation.getArgument(0, Runnable.class)); return null; })
        .when(executor).execute(any(Runnable.class));
    plan = jdbc.queryForObject("insert into exam_plan(org_id,plan_no,name) values (?,?,?) returning id",
        UUID.class, ORG, "LEASE-" + UUID.randomUUID(), "Isolated lease recovery fixture");
    revision = jdbc.queryForObject("insert into exam_plan_revision(org_id,plan_id,revision_no) values (?,?,1) returning id",
        UUID.class, ORG, plan);
    jdbc.update("update exam_plan set active_revision_id=? where id=?", revision, plan);
  }

  @AfterEach
  void cleanUp() {
    pending.clear();
    TenantContext.clear();
    if (plan != null) {
      jdbc.update("delete from api_idempotency_record where resource_id in (select id from exam_plan_planning_job where plan_id=?)", plan);
      jdbc.update("update exam_plan_revision set status='CANCELLED' where plan_id=?", plan);
      jdbc.update("delete from exam_plan where id=?", plan);
    }
    if (teacher != null) jdbc.update("delete from teacher where id=?", teacher);
    if (room != null) jdbc.update("delete from classroom where id=?", room);
  }

  @Test
  void lostQueuedWorkerBecomesExplicitlyFailedAndPublicStartAllowsANewAttempt() throws Exception {
    PlanningJobView lost = planning.start(plan, revision, new PlanningJobRequest("JOINT", 0L, true, 120),
        "lease-lost-" + UUID.randomUUID(), ACTOR);
    assertThat(lost.status()).isEqualTo("QUEUED");
    assertThat(pending).hasSize(1);
    // Simulate the committed job losing its in-memory queue callback, not an actual process kill.
    pending.clear();
    awaitFailed(lost.id());
    PlanningJobView retry = planning.start(plan, revision, new PlanningJobRequest("JOINT", 0L, true, 120),
        "lease-retry-" + UUID.randomUUID(), ACTOR);
    assertThat(retry.id()).isNotEqualTo(lost.id());
    assertThat(retry.status()).isEqualTo("QUEUED");
    assertThat(jdbc.queryForObject("select count(*) from exam_plan_session where revision_id=?", Integer.class, revision)).isZero();
  }

  @Test
  void fixedPastLeaseExpiresThroughRealWatchdogAndReleasesRevisionForRetry() throws Exception {
    PlanningJobView lost = start();
    pending.clear();
    Lease previous = lease(lost.id());
    // Deterministic expired-state test; this does not prove elapsed wall-clock recovery or a process kill.
    expire(lost.id());
    planning.recoverExpiredPlanningJobs();
    awaitFailed(lost.id());
    assertThat(planning.get(lost.id()).stage()).isEqualTo("WORKER_LOST");
    assertThat(jdbc.queryForObject("select phase from async_job_lease where job_id=?", String.class, lost.id()))
        .isEqualTo("EXPIRED");
    assertThat(jdbc.queryForObject("select fence_token from async_job_lease where job_id=?", Long.class, lost.id()))
        .isGreaterThan(previous.fenceToken());
    PlanningJobView retry = start();
    assertThat(retry.id()).isNotEqualTo(lost.id());
    assertThat(retry.status()).isEqualTo("QUEUED");
    assertThat(jdbc.queryForObject("select active_planning_job_id from exam_plan_revision where id=?", UUID.class, revision))
        .isEqualTo(retry.id());
    assertThat(jdbc.queryForObject("select count(*) from exam_plan_session where revision_id=?", Integer.class, revision)).isZero();
  }

  @Test
  void expiredQueuedCallbackCannotResurrectItselfOrOverwriteTheNewAttempt() throws Exception {
    PlanningJobView old = start();
    Runnable oldCallback = pending.remove();
    expire(old.id());
    planning.recoverExpiredPlanningJobs();
    awaitFailed(old.id());
    PlanningJobView retry = start();
    oldCallback.run();
    assertThat(planning.get(old.id()).status()).isEqualTo("FAILED");
    assertThat(planning.get(retry.id()).status()).isEqualTo("QUEUED");
    assertThat(jdbc.queryForObject("select active_planning_job_id from exam_plan_revision where id=?", UUID.class, revision))
        .isEqualTo(retry.id());
    assertThat(jdbc.queryForObject("select status from exam_plan_revision where id=?", String.class, revision)).isEqualTo("SOLVING");
  }

  @Test
  void realJointPlannerCannotCommitItsCompleteResultAfterLeaseExpiry() throws Exception {
    seedFeasibleInput();
    PlanningJobView job = start();
    AtomicBoolean invalidated = new AtomicBoolean();
    AtomicReference<String> stageBeforeExpiry = new AtomicReference<>();
    doAnswer(invocation -> {
      Lease lease = invocation.getArgument(0);
      if (lease.jobId().equals(job.id()) && invalidated.compareAndSet(false, true)) {
        stageBeforeExpiry.set(jdbc.queryForObject("select stage from exam_plan_planning_job where id=?", String.class, job.id()));
        expire(job.id());
      }
      return invocation.callRealMethod();
    }).when(leases).finish(any(Lease.class), any(Runnable.class));
    pending.remove().run();
    assertThat(invalidated).isTrue();
    // Assert on the JUnit thread: CompletableFuture may capture errors thrown by its callback.
    assertThat(stageBeforeExpiry.get()).isEqualTo("PERSISTING_COMPLETE_RESULT");
    planning.recoverExpiredPlanningJobs();
    awaitFailed(job.id());
    assertThat(planning.get(job.id()).stage()).isEqualTo("WORKER_LOST");
    assertThat(planning.get(job.id()).inputHash()).hasSize(64);
    assertThat(planning.get(job.id()).resultHash()).isNull();
    assertThat(jdbc.queryForObject("select count(*) from exam_plan_session where revision_id=?", Integer.class, revision)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from exam_plan_duty_assignment where revision_id=?", Integer.class, revision)).isZero();
  }

  @Test
  void expiryDuringOutputTransactionRollsBackAllOutputAndTerminalWrites() {
    PlanningJobView job = start();
    Lease lease = lease(job.id());
    leases.begin(lease, 30_000);
    assertThatThrownBy(() -> leases.finish(lease, () -> {
      jdbc.update("""
          insert into exam_plan_session(org_id,revision_id,session_code,name,starts_at,ends_at)
          values (?,?,'SHOULD-ROLL-BACK','Lease rollback',clock_timestamp(),clock_timestamp()+interval '1 hour')
          """, ORG, revision);
      jdbc.update("update exam_plan_planning_job set status='SUCCEEDED' where id=?", job.id());
      // Deterministic fault injection before the second fence check, on this same real transaction.
      expire(job.id());
    })).isInstanceOf(LeaseLostException.class);
    leases.stopTracking(lease);
    assertThat(planning.get(job.id()).status()).isEqualTo("QUEUED");
    assertThat(jdbc.queryForObject("select count(*) from exam_plan_session where revision_id=?", Integer.class, revision)).isZero();
  }

  @Test
  void independentHeartbeatRenewsOnlyLiveLeaseAndLateProgressOrFailureCannotWrite() throws Exception {
    PlanningJobView job = start();
    Lease lease = lease(job.id());
    leases.begin(lease, 30_000);
    jdbc.update("update exam_plan_planning_job set status='RUNNING' where id=?", job.id());
    Instant first = jdbc.queryForObject("select lease_until from async_job_lease where job_id=?", Timestamp.class, job.id()).toInstant();
    long until = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    Instant renewed = first;
    while (!renewed.isAfter(first) && System.nanoTime() < until) {
      Thread.sleep(25);
      renewed = jdbc.queryForObject("select lease_until from async_job_lease where job_id=?", Timestamp.class, job.id()).toInstant();
    }
    assertThat(renewed).isAfter(first);
    expire(job.id());
    leases.heartbeat();
    assertThat(leases.valid(lease)).isFalse();
    assertThatThrownBy(() -> leases.fencedRun(lease, () -> ReflectionTestUtils.invokeMethod(planning,
        "updateRunningStage", ORG, job.id(), ACTOR, "LATE_PROGRESS", 99))).isInstanceOf(LeaseLostException.class);
    assertThatThrownBy(() -> leases.finish(lease, () -> ReflectionTestUtils.invokeMethod(planning,
        "finishFailed", ORG, plan, revision, job.id(), ACTOR, new IllegalStateException("late failure"))))
        .isInstanceOf(LeaseLostException.class);
    planning.recoverExpiredPlanningJobs();
    awaitFailed(job.id());
    assertThat(planning.get(job.id()).stage()).isEqualTo("WORKER_LOST");
    assertThat(jdbc.queryForObject("select fence_token from async_job_lease where job_id=?", Long.class, job.id())).isGreaterThan(lease.fenceToken());
  }

  @Test
  void watchdogLeavesUnleasedHistoricalJobsUntouched() {
    UUID historic = UUID.randomUUID();
    jdbc.update("""
        insert into exam_plan_planning_job(id,org_id,plan_id,revision_id,status,created_at)
        values (?,?,?,?,'RUNNING',clock_timestamp()-interval '1 day')
        """, historic, ORG, plan, revision);
    jdbc.update("update exam_plan_revision set status='SOLVING' where id=?", revision);
    planning.recoverExpiredPlanningJobs();
    assertThat(planning.get(historic).status()).isEqualTo("RUNNING");
  }

  @Test
  void leaseCannotAttachAJobFromAnotherTenant() {
    PlanningJobView job = start();
    UUID other = jdbc.queryForObject("insert into organization(name,slug) values (?,?) returning id", UUID.class,
        "Other lease tenant", "lease-other-" + UUID.randomUUID());
    try {
      assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status ->
          leases.register(Kind.PLANNING, other, job.id()))).isInstanceOf(DataIntegrityViolationException.class);
      assertThat(jdbc.queryForObject("select count(*) from async_job_lease where org_id=?", Integer.class, other)).isZero();
    } finally {
      jdbc.update("delete from organization where id=?", other);
    }
  }

  private PlanningJobView start() {
    return planning.start(plan, revision, new PlanningJobRequest("JOINT", 0L, true, 120),
        "lease-" + UUID.randomUUID(), ACTOR);
  }

  private void awaitFailed(UUID job) throws InterruptedException {
    // A concurrent watchdog may already hold the lease row; SKIP LOCKED deliberately skips it.
    // Observe the promised bounded asynchronous terminal state, not one scanner invocation.
    var initialClock = leaseClock(job);
    long until = System.nanoTime() + Duration.ofSeconds(4).toNanos();
    while (System.nanoTime() < until && !"FAILED".equals(planning.get(job).status())) Thread.sleep(25);
    if (!"FAILED".equals(planning.get(job).status())) {
      Thread.getAllStackTraces().forEach((thread, stack) -> {
        if (thread.getName().startsWith("jkxt-job-lease-")) {
          System.out.println("LEASE_SCHEDULER_THREAD " + thread.getName() + " " + java.util.Arrays.toString(stack));
        }
      });
    }
    assertThat(planning.get(job).status())
        .as("a lost worker must converge to FAILED; DB clock/lease before=%s after=%s", initialClock, leaseClock(job))
        .isEqualTo("FAILED");
  }

  private java.util.Map<String, Object> leaseClock(UUID job) {
    return jdbc.queryForMap("select clock_timestamp() as db_now,lease_until,hard_deadline_at,phase,fence_token "
        + "from async_job_lease where org_id=? and job_id=?", ORG, job);
  }

  private Lease lease(UUID job) {
    return jdbc.queryForObject("select owner_instance,fence_token from async_job_lease where org_id=? and job_id=?",
        (rs, row) -> new Lease(ORG, Kind.PLANNING, job, rs.getObject(1, UUID.class), rs.getLong(2)), ORG, job);
  }

  private void expire(UUID job) {
    // Stay expired even when the isolated Docker clock jumps backwards during host NTP synchronization.
    jdbc.update("update async_job_lease set lease_until='2001-01-01T00:00:00Z'::timestamptz where org_id=? and job_id=?", ORG, job);
  }

  private void seedFeasibleInput() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    teacher = jdbc.queryForObject("""
        insert into teacher(org_id,teacher_no,name,gender,qualifications,max_daily_sessions)
        values (?,?,'Lease Teacher','unknown','["CHIEF","INVIGILATOR"]'::jsonb,2) returning id
        """, UUID.class, ORG, "LT-" + suffix);
    room = jdbc.queryForObject("insert into classroom(org_id,code,name,capacity,exam_capacity) values (?,?,'Lease Room',1,1) returning id",
        UUID.class, ORG, "LR-" + suffix);
    UUID demand = jdbc.queryForObject("""
        insert into exam_plan_exam_demand(org_id,revision_id,demand_code,name,duration_minutes,candidate_count,
          invigilator_count,min_rooms,max_rooms,split_allowed)
        values (?,?,'LEASE','Lease Demand',60,0,1,1,1,false) returning id
        """, UUID.class, ORG, revision);
    jdbc.update("""
        insert into exam_plan_demand_window(org_id,revision_id,demand_id,window_code,starts_at,ends_at)
        values (?,?,?,'W1','2088-06-05T00:00:00Z','2088-06-05T01:00:00Z')
        """, ORG, revision, demand);
    jdbc.update("""
        insert into exam_plan_room_pool(org_id,revision_id,classroom_id,campus_code,exam_capacity,status)
        values (?,?,?,'MAIN',1,'INCLUDED')
        """, ORG, revision, room);
    jdbc.update("""
        insert into exam_plan_teacher_pool(org_id,revision_id,teacher_id,eligible_roles,max_daily_sessions,min_rest_minutes,status)
        values (?,?,?,'["CHIEF","INVIGILATOR"]'::jsonb,2,0,'INCLUDED')
        """, ORG, revision, teacher);
  }
}
