package com.jkxt.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.jkxt.common.jobs.AsyncJobLeaseService;
import com.jkxt.common.jobs.AsyncJobLeaseService.Lease;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.scheduling.api.ScheduleDtos.RunStartResponse;
import com.jkxt.scheduling.api.ScheduleDtos.ProgressEvent;
import com.jkxt.scheduling.service.ScheduleJobService;
import com.jkxt.scheduling.service.ScheduleProgressSink;
import com.jkxt.tasks.service.ExamTaskService;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest(properties = {"jkxt.jobs.queue-timeout-ms=120000", "jkxt.jobs.watchdog-interval-ms=3600000",
    "jkxt.jobs.heartbeat-interval-ms=3600000", "jkxt.jobs.lease-duration-ms=30000"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ScheduleRunLeaseIntegrationTest {
  private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
  @Autowired JdbcTemplate jdbc;
  @Autowired ScheduleJobService scheduling;
  @MockitoSpyBean ScheduleProgressSink progress;
  @Autowired ExamTaskService taskService;
  @Autowired DataSource dataSource;
  @MockitoBean(name = "scheduleTaskExecutor") Executor executor;
  @MockitoSpyBean AsyncJobLeaseService leases;
  private final Queue<Runnable> pending = new ConcurrentLinkedQueue<>();
  private final List<UUID> tasks = new ArrayList<>();

  @BeforeEach void before() {
    TenantContext.set(ORG);
    doAnswer(invocation -> { pending.add(invocation.getArgument(0, Runnable.class)); return null; })
        .when(executor).execute(any(Runnable.class));
  }

  @AfterEach void after() {
    pending.clear();
    for (UUID task : tasks) {
      System.out.println("LEASE_DIAGNOSTIC " + jdbc.queryForList("""
          with db_clock as materialized(select clock_timestamp() as observed_at)
          select r.id,r.status,l.phase,l.owner_instance,l.fence_token,l.updated_at,l.lease_until,l.hard_deadline_at,
            c.observed_at,l.updated_at<=c.observed_at watermark_valid,l.lease_until>c.observed_at lease_valid,
            l.hard_deadline_at>c.observed_at deadline_valid
          from schedule_run r left join async_job_lease l on l.job_id=r.id cross join db_clock c
          where r.org_id=? and r.exam_task_id=?
          """, ORG, task));
      jdbc.update("delete from exam_task where org_id=? and id=?", ORG, task);
    }
    TenantContext.clear();
  }

  @Test void queuedRunOwnsDurableTenantBoundLeaseBeforeCallbackCanRun() {
    RunStartResponse run = start();
    assertThat(pending).hasSize(1);
    assertThat(jdbc.queryForObject("select count(*) from async_job_lease where org_id=? and job_kind='SCHEDULE' "
        + "and job_id=? and schedule_run_id=? and phase='QUEUED'", Integer.class, ORG, run.runId(), run.runId())).isEqualTo(1);
    assertThat(scheduling.snapshot(run.runId()).status()).isEqualTo("pending");
  }

  @Test void realSolverResultAndAssignmentsCommitWithFinishedLease() {
    RunStartResponse run = start();
    AtomicBoolean successObservedAfterCommit = new AtomicBoolean();
    doAnswer(invocation -> {
      ProgressEvent event = invocation.getArgument(0);
      if (event.runId().equals(run.runId()) && event.status().equals("succeeded")) {
        // An independent physical connection cannot observe uncommitted JPA/JDBC writes.
        try (var observer = dataSource.getConnection(); var statement = observer.prepareStatement("""
            select r.status,l.phase,(select count(*) from schedule_assignment a where a.schedule_run_id=r.id) assignments
            from schedule_run r join async_job_lease l on l.job_id=r.id where r.id=?
            """)) {
          statement.setObject(1, run.runId());
          try (var rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("succeeded");
            assertThat(rows.getString(2)).isEqualTo("FINISHED");
            assertThat(rows.getInt(3)).isEqualTo(6);
          }
        }
        successObservedAfterCommit.set(true);
      }
      return invocation.callRealMethod();
    }).when(progress).publish(any(ProgressEvent.class));
    pending.remove().run();
    assertThat(scheduling.snapshot(run.runId()).status()).isEqualTo("succeeded");
    assertThat(scheduling.snapshot(run.runId()).assignments()).hasSize(6);
    assertThat(jdbc.queryForObject("select phase from async_job_lease where job_id=?", String.class, run.runId())).isEqualTo("FINISHED");
    assertThat(jdbc.queryForObject("select fence_token from async_job_lease where job_id=?", Long.class, run.runId())).isEqualTo(2L);
    assertThat(successObservedAfterCommit).isTrue();
  }

  @Test void fullQueueIsExplicitFailureNotStuckPendingOrCallerRuns() {
    doThrow(new RejectedExecutionException("controlled isolated full queue")).when(executor).execute(any(Runnable.class));
    RunStartResponse run = start();
    assertThat(run.status()).isEqualTo("failed");
    assertThat(scheduling.snapshot(run.runId()).status()).isEqualTo("failed");
    assertThat(scheduling.snapshot(run.runId()).errorCode()).isEqualTo("SCHEDULE_QUEUE_FULL");
    assertThat(scheduling.snapshot(run.runId()).assignments()).isEmpty();
    assertThat(jdbc.queryForObject("select status from exam_task where id=?", String.class, run.taskId())).isEqualTo("failed");
    assertThat(jdbc.queryForObject("select phase from async_job_lease where job_id=?", String.class, run.runId())).isEqualTo("FINISHED");
  }

  @Test void expiredQueueConvergesAndLateCallbackCannotOverwriteNewRun() {
    RunStartResponse old = start();
    Runnable oldCallback = pending.remove();
    expire(old.runId());
    recover();
    assertThat(scheduling.snapshot(old.runId()).errorCode()).isEqualTo("SCHEDULE_WORKER_LOST");
    assertThat(jdbc.queryForObject("select status from exam_task where id=?", String.class, old.taskId())).isEqualTo("failed");
    // Fixture endpoint has one exam row per room but deliberately no importer joins. Bind them for the public task retry.
    jdbc.update("insert into exam_room(exam_id,classroom_id,org_id) select e.id,c.id,e.org_id from exam e "
        + "cross join lateral (select id from classroom where org_id=e.org_id and status='active' order by id limit 1) c "
        + "where e.org_id=? and e.exam_task_id=?", ORG, old.taskId());
    RunStartResponse retry = scheduling.startTaskRun(old.taskId());
    oldCallback.run();
    assertThat(scheduling.snapshot(old.runId()).status()).isEqualTo("failed");
    assertThat(scheduling.snapshot(old.runId()).assignments()).isEmpty();
    assertThat(scheduling.snapshot(retry.runId()).status()).isEqualTo("pending");
    assertThat(jdbc.queryForObject("select status from exam_task where id=?", String.class, old.taskId())).isEqualTo("scheduling");
    assertThat(jdbc.queryForObject("select phase from async_job_lease where job_id=?", String.class, old.runId())).isEqualTo("EXPIRED");
  }

  @Test void completedRealSolverCannotWriteAnyResultAfterLeaseWasLost() {
    RunStartResponse run = start();
    AtomicBoolean invalidated = new AtomicBoolean();
    doAnswer(invocation -> {
      Lease lease = invocation.getArgument(0);
      if (lease.jobId().equals(run.runId()) && invalidated.compareAndSet(false, true)) expire(run.runId());
      return invocation.callRealMethod();
    }).when(leases).finish(any(Lease.class), any(Runnable.class));
    pending.remove().run();
    assertThat(invalidated).as("the complete real solver result must enter the transactional terminal fence").isTrue();
    recover();
    assertThat(scheduling.snapshot(run.runId()).status()).isEqualTo("failed");
    assertThat(scheduling.snapshot(run.runId()).errorCode()).isEqualTo("SCHEDULE_WORKER_LOST");
    assertThat(scheduling.snapshot(run.runId()).assignments()).isEmpty();
    assertThat(progress.events(run.runId())).noneMatch(event -> "succeeded".equals(event.status()));
  }

  @Test void watchdogDoesNotRewriteHistoricalUnleasedRun() {
    RunStartResponse run = start();
    jdbc.update("delete from async_job_lease where job_id=?", run.runId());
    recover();
    assertThat(scheduling.snapshot(run.runId()).status()).isEqualTo("pending");
  }

  @Test void lockedTaskDefersOnlyItsRecoveryWithoutLosingLeaseOrBlockingOtherTask() throws Exception {
    RunStartResponse locked = start();
    RunStartResponse healthy = start();
    expire(locked.runId());
    expire(healthy.runId());
    var watcher = Executors.newSingleThreadExecutor();
    java.util.concurrent.Future<?> recovery = null;
    try (var deleteTransaction = dataSource.getConnection()) {
      deleteTransaction.setAutoCommit(false);
      try (var statement = deleteTransaction.prepareStatement("select id from exam_task where id=? for update")) {
        statement.setObject(1, locked.taskId());
        try (var result = statement.executeQuery()) { assertThat(result.next()).isTrue(); }
      }
      try {
        recovery = watcher.submit(this::recover);
        recovery.get(2, TimeUnit.SECONDS);
        assertThat(scheduling.snapshot(locked.runId()).status()).isEqualTo("pending");
        assertThat(jdbc.queryForObject("select phase from async_job_lease where job_id=?", String.class, locked.runId())).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("select fence_token from async_job_lease where job_id=?", Long.class, locked.runId())).isEqualTo(1L);
        assertThat(scheduling.snapshot(healthy.runId()).errorCode()).isEqualTo("SCHEDULE_WORKER_LOST");
        // Complete the same cascade used by the public delete endpoint while recovery is deferred.
        try (var statement = deleteTransaction.prepareStatement("delete from exam_task where id=?")) {
          statement.setQueryTimeout(2);
          statement.setObject(1, locked.taskId());
          assertThat(statement.executeUpdate()).isEqualTo(1);
        }
      } finally { deleteTransaction.rollback(); }
      // Rollback proves a deferred (not deleted) task is still recoverable on a later scan.
      recover();
      assertThat(scheduling.snapshot(locked.runId()).errorCode()).isEqualTo("SCHEDULE_WORKER_LOST");
      assertThat(jdbc.queryForObject("select phase from async_job_lease where job_id=?", String.class, locked.runId())).isEqualTo("EXPIRED");
    } finally {
      if (recovery != null) recovery.get(5, TimeUnit.SECONDS);
      watcher.shutdownNow();
      assertThat(watcher.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"TASK", "RUN"})
  void briefOrdinaryParentLockDoesNotDiscardTheSameLiveCallback(String parent) throws Exception {
    RunStartResponse run = start();
    Runnable callback = pending.remove();
    CountDownLatch beginEntered = new CountDownLatch(1);
    doAnswer(invocation -> {
      if (((Lease) invocation.getArgument(0)).jobId().equals(run.runId())) beginEntered.countDown();
      return invocation.callRealMethod();
    }).when(leases).begin(any(Lease.class), org.mockito.ArgumentMatchers.anyLong());
    var worker = Executors.newSingleThreadExecutor();
    java.util.concurrent.Future<?> completion = null;
    try (var ordinaryTransaction = dataSource.getConnection()) {
      ordinaryTransaction.setAutoCommit(false);
      int blockerPid;
      try (var statement = ordinaryTransaction.createStatement(); var rows = statement.executeQuery("select pg_backend_pid()")) {
        assertThat(rows.next()).isTrue();
        blockerPid = rows.getInt(1);
      }
      String table = parent.equals("TASK") ? "exam_task" : "schedule_run";
      UUID id = parent.equals("TASK") ? run.taskId() : run.runId();
      try {
        try (var lock = ordinaryTransaction.prepareStatement("select id from " + table + " where org_id=? and id=? for update")) {
          lock.setObject(1, ORG);
          lock.setObject(2, id);
          try (var rows = lock.executeQuery()) { assertThat(rows.next()).isTrue(); }
        }
        completion = worker.submit(callback);
        assertThat(beginEntered.await(5, TimeUnit.SECONDS)).as("the actual callback reaches begin while its parent is locked").isTrue();
        // Release as soon as the real database reports the worker's wait. With the old
        // SKIP LOCKED implementation the callback instead finishes early, which makes
        // this test fail on its required persisted success rather than a timing guess.
        boolean observedDatabaseWait = false;
        long observationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!completion.isDone() && System.nanoTime() < observationDeadline) {
          observedDatabaseWait = Boolean.TRUE.equals(jdbc.queryForObject(
              "select exists(select 1 from pg_stat_activity where ? = any(pg_blocking_pids(pid)))",
              Boolean.class, blockerPid));
          if (observedDatabaseWait) break;
          TimeUnit.MILLISECONDS.sleep(5);
        }
        assertThat(observedDatabaseWait || completion.isDone())
            .as("observe a real contending worker, not merely release before it reaches the database").isTrue();
        assertThat(jdbc.queryForObject("select count(*) from schedule_assignment where schedule_run_id=?", Integer.class, run.runId())).isZero();
        assertThat(progress.events(run.runId())).noneMatch(event -> "succeeded".equals(event.status()));
      } finally { ordinaryTransaction.rollback(); }
      completion.get(10, TimeUnit.SECONDS);
      assertThat(scheduling.snapshot(run.runId()).status()).as("the original callback survives an ordinary short parent lock").isEqualTo("succeeded");
      assertThat(scheduling.snapshot(run.runId()).assignments()).hasSize(6);
      assertThat(jdbc.queryForObject("select count(*) from schedule_run where exam_task_id=?", Integer.class, run.taskId())).isEqualTo(1);
      assertThat(jdbc.queryForObject("select phase from async_job_lease where job_id=?", String.class, run.runId())).isEqualTo("FINISHED");
      assertThat(jdbc.queryForObject("select fence_token from async_job_lease where job_id=?", Long.class, run.runId())).isEqualTo(2L);
      assertThat(progress.events(run.runId())).anyMatch(event -> "succeeded".equals(event.status()));
      assertThat(pending).as("no replacement callback or implicit retry was scheduled").isEmpty();
    } finally {
      if (completion != null) completion.get(10, TimeUnit.SECONDS);
      worker.shutdownNow();
      assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void initialProgressParentTimeoutStillSubmitsExactlyOneRealCallback() throws Exception {
    AtomicBoolean heldInitialProgress = new AtomicBoolean();
    doAnswer(invocation -> {
      Lease lease = invocation.getArgument(0);
      if (!heldInitialProgress.compareAndSet(false, true)) return invocation.callRealMethod();
      UUID taskId = jdbc.queryForObject("select exam_task_id from schedule_run where org_id=? and id=?",
          UUID.class, lease.orgId(), lease.jobId());
      // Track before the public call returns so a broken enqueue cannot leak the fixture.
      tasks.add(taskId);
      try (var ordinaryTransaction = dataSource.getConnection()) {
        ordinaryTransaction.setAutoCommit(false);
        try {
          try (var lock = ordinaryTransaction.prepareStatement("select id from exam_task where org_id=? and id=? for update")) {
            lock.setObject(1, ORG);
            lock.setObject(2, taskId);
            try (var rows = lock.executeQuery()) { assertThat(rows.next()).isTrue(); }
          }
          // The real PG lock timeout must propagate out of this transactional progress write.
          return invocation.callRealMethod();
        } finally { ordinaryTransaction.rollback(); }
      }
    }).when(leases).fencedRun(any(Lease.class), any(Runnable.class));

    RunStartResponse run = scheduling.startFixtureRun("feasible");
    assertThat(heldInitialProgress).isTrue();
    assertThat(pending).as("a nonessential progress write must not lose executor submission").hasSize(1);
    assertThat(scheduling.snapshot(run.runId()).status()).isEqualTo("pending");
    assertThat(scheduling.snapshot(run.runId()).assignments()).isEmpty();
    assertThat(progress.events(run.runId())).isEmpty();
    assertThat(jdbc.queryForObject("select phase from async_job_lease where job_id=?", String.class, run.runId())).isEqualTo("QUEUED");
    assertThat(jdbc.queryForObject("select fence_token from async_job_lease where job_id=?", Long.class, run.runId())).isEqualTo(1L);
    pending.remove().run();
    assertThat(scheduling.snapshot(run.runId()).status()).isEqualTo("succeeded");
    assertThat(scheduling.snapshot(run.runId()).assignments()).hasSize(6);
    assertThat(jdbc.queryForObject("select phase from async_job_lease where job_id=?", String.class, run.runId())).isEqualTo("FINISHED");
    assertThat(pending).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"BEGIN", "MARK_RUNNING", "PROGRESS", "FINISH"})
  void normalWorkerNeverWaitsBehindParentDeletionOrPublishesUncommittedSuccess(String boundary) throws Exception {
    RunStartResponse blocked = start();
    Runnable callback = pending.remove();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicInteger writes = new AtomicInteger();
    Runnable pause = () -> {
      entered.countDown();
      try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
      catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    };
    if (boundary.equals("BEGIN")) {
      doAnswer(invocation -> { if (((Lease) invocation.getArgument(0)).jobId().equals(blocked.runId())) pause.run(); return invocation.callRealMethod(); })
          .when(leases).begin(any(Lease.class), org.mockito.ArgumentMatchers.anyLong());
    } else if (boundary.equals("FINISH")) {
      doAnswer(invocation -> { if (((Lease) invocation.getArgument(0)).jobId().equals(blocked.runId())) pause.run(); return invocation.callRealMethod(); })
          .when(leases).finish(any(Lease.class), any(Runnable.class));
    } else {
      int selectedWrite = boundary.equals("MARK_RUNNING") ? 1 : 2;
      doAnswer(invocation -> {
        if (((Lease) invocation.getArgument(0)).jobId().equals(blocked.runId()) && writes.incrementAndGet() == selectedWrite) pause.run();
        return invocation.callRealMethod();
      }).when(leases).fencedRun(any(Lease.class), any(Runnable.class));
    }
    var worker = Executors.newSingleThreadExecutor();
    var completion = worker.submit(callback);
    try (var deletion = dataSource.getConnection()) {
      deletion.setAutoCommit(false);
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).as("real worker reaches selected boundary").isTrue();
        try (var lock = deletion.prepareStatement("select id from exam_task where id=? for update")) {
          lock.setObject(1, blocked.taskId());
          try (var rows = lock.executeQuery()) { assertThat(rows.next()).isTrue(); }
        }
        release.countDown();
        completion.get(2, TimeUnit.SECONDS);
        assertThat(jdbc.queryForObject("select count(*) from schedule_assignment where schedule_run_id=?", Integer.class, blocked.runId())).isZero();
        assertThat(progress.events(blocked.runId())).noneMatch(event -> "succeeded".equals(event.status()));
        assertThat(jdbc.queryForObject("select phase from async_job_lease where job_id=?", String.class, blocked.runId()))
            .isEqualTo(boundary.equals("BEGIN") ? "QUEUED" : "RUNNING");
        assertThat(jdbc.queryForObject("select fence_token from async_job_lease where job_id=?", Long.class, blocked.runId())).isEqualTo(1L);
        RunStartResponse unrelated = start();
        pending.remove().run();
        assertThat(scheduling.snapshot(unrelated.runId()).status()).isEqualTo("succeeded");
        try (var delete = deletion.prepareStatement("delete from exam_task where org_id=? and id=?")) {
          delete.setQueryTimeout(2); delete.setObject(1, ORG); delete.setObject(2, blocked.taskId());
          assertThat(delete.executeUpdate()).isEqualTo(1);
        }
      } finally { release.countDown(); deletion.rollback(); }
      expire(blocked.runId());
      recover();
      assertThat(scheduling.snapshot(blocked.runId()).errorCode()).isEqualTo("SCHEDULE_WORKER_LOST");
      assertThat(jdbc.queryForObject("select phase from async_job_lease where job_id=?", String.class, blocked.runId())).isEqualTo("EXPIRED");
      taskService.delete(blocked.taskId());
      assertThat(jdbc.queryForObject("select count(*) from exam_task where id=?", Integer.class, blocked.taskId())).isZero();
      assertThat(jdbc.queryForObject("select count(*) from schedule_run where id=?", Integer.class, blocked.runId())).isZero();
      assertThat(jdbc.queryForObject("select count(*) from async_job_lease where job_id=?", Integer.class, blocked.runId())).isZero();
    } finally {
      release.countDown();
      completion.get(5, TimeUnit.SECONDS);
      worker.shutdownNow();
      assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private RunStartResponse start() {
    RunStartResponse run = scheduling.startFixtureRun("feasible");
    tasks.add(run.taskId());
    return run;
  }
  private void expire(UUID run) {
    jdbc.update("update async_job_lease set lease_until='2001-01-01T00:00:00Z'::timestamptz where org_id=? and job_id=?", ORG, run);
  }
  private void recover() {
    // Reflection keeps this same RED/GREEN test compilable before the recovery entry point exists.
    ReflectionTestUtils.invokeMethod(scheduling, "recoverExpiredScheduleRuns");
  }
}
