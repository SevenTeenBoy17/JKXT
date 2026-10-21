package com.jkxt.common.jobs;

import static org.assertj.core.api.Assertions.*;

import com.jkxt.common.jobs.AsyncJobLeaseService.Kind;
import com.jkxt.common.jobs.AsyncJobLeaseService.Lease;
import com.jkxt.common.jobs.AsyncJobLeaseService.LeaseLostException;
import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PB-20 (ASYNC-06) and the ScheduleEditApiIntegrationTest flake: a live worker discarded itself with
 * "lease loss" right after it started, and the job later ended as *_WORKER_LOST.
 *
 * <p>Root cause (proven in round 10 with diagnostics and RED runs on the original code): the
 * clock-rollback guard in the fence/renewal queries had ZERO tolerance for clock steps. The DB clock of
 * the Docker Desktop VM stepped BACK by ~0.6 s right after begin() wrote the watermark (row
 * updated_at=03:27:24.570443, DB clock_timestamp()=03:27:23.965551), so updated_at > now() and the
 * live worker was discarded. Fix: a bounded tolerance (jkxt.jobs.clock-step-tolerance-ms, default 2000,
 * at most half a lease) with a forward-only watermark; real rollbacks are still rejected (see
 * LeaseClockRollbackIntegrationTest, 5 minutes).
 *
 * <p>The heartbeat-renewal race exercised by fenceThatWaitedBehindAHeartbeatRenewalKeepsOwnership (the
 * fence reading the clock before waiting for the row lock) was an early hypothesis: it failed only 1 of 5
 * runs on the original code, was NOT proven as the cause, and its separate fix was dropped for minimality.
 * The test is kept as a guard; under the tolerance fix the renewed watermark being milliseconds ahead of
 * a stale clock reading is covered as well.
 */
@SpringBootTest(properties = {"jkxt.jobs.heartbeat-interval-ms=3600000", "jkxt.jobs.watchdog-interval-ms=3600000"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LeaseFenceRenewalRaceIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;
  @Autowired PlatformTransactionManager transactions;
  private UUID org;
  private UUID plan;
  private int revisionNumber;
  private AsyncJobLeaseService service;

  @BeforeEach void prepare() {
    org = UUID.randomUUID(); plan = UUID.randomUUID(); revisionNumber = 0;
    jdbc.update("insert into organization(id,name,slug) values (?,'Fence renewal race',?)", org, "fence-race-" + org);
    jdbc.update("insert into exam_plan(id,org_id,plan_no,name) values (?,?,?,'Fence renewal race')", plan, org, plan.toString());
    // Independent real service instance: the Spring scheduler cannot touch these test-owned leases.
    service = new AsyncJobLeaseService(jdbc, transactions, 30_000, 30_000);
  }

  @AfterEach void cleanup() {
    jdbc.update("delete from exam_plan where id=?", plan);
    jdbc.update("delete from organization where id=?", org);
  }

  private Lease runningLease() {
    UUID job = UUID.randomUUID();
    UUID revision = UUID.randomUUID();
    Lease lease = new TransactionTemplate(transactions).execute(status -> {
      jdbc.update("insert into exam_plan_revision(id,org_id,plan_id,revision_no) values (?,?,?,?)", revision, org, plan, ++revisionNumber);
      jdbc.update("insert into exam_plan_planning_job(id,org_id,plan_id,revision_id,status) values (?,?,?,?,'RUNNING')", job, org, plan, revision);
      return service.register(Kind.PLANNING, org, job);
    });
    service.begin(lease, 120_000);
    return lease;
  }

  /** Waits until a backend of this database is blocked on a lock while reading async_job_lease. */
  private void awaitFenceBlockedOnRowLock() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Integer waiting = jdbc.queryForObject("""
          select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock'
            and query like '%from async_job_lease%' and query like '%for update of l%'
          """, Integer.class);
      if (waiting != null && waiting > 0) return;
      Thread.sleep(20);
    }
    throw new AssertionError("The fence query never waited for the held lease row lock");
  }

  @Test void fenceThatWaitedBehindAHeartbeatRenewalKeepsOwnership() throws Exception {
    Lease lease = runningLease();
    AtomicBoolean wrote = new AtomicBoolean();
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try (Connection renewal = dataSource.getConnection()) {
      renewal.setAutoCommit(false);
      try (var lock = renewal.prepareStatement("select job_id from async_job_lease where job_id=? for update")) {
        lock.setObject(1, lease.jobId());
        try (var row = lock.executeQuery()) { assertThat(row.next()).isTrue(); }
      }
      Future<?> fence = worker.submit(() -> service.fencedRun(lease, () -> wrote.set(true)));
      awaitFenceBlockedOnRowLock();
      // Exactly the persisted effect of AsyncJobLeaseService.heartbeat(): renew lease_until and move the
      // DB-clock watermark to a clock reading taken after the waiting fence read its own clock.
      try (var renew = renewal.prepareStatement("""
          update async_job_lease set lease_until=clock_timestamp()+interval '30 seconds',updated_at=clock_timestamp()
          where job_id=? and phase='RUNNING'
          """)) {
        renew.setObject(1, lease.jobId());
        assertThat(renew.executeUpdate()).isEqualTo(1);
      }
      renewal.commit();
      assertThatCode(() -> fence.get(10, TimeUnit.SECONDS)).doesNotThrowAnyException();
    } finally {
      worker.shutdownNow();
    }
    assertThat(wrote).isTrue();
    assertThat(service.valid(lease)).isTrue();
    assertThatCode(() -> service.finish(lease, () -> { })).doesNotThrowAnyException();
    assertThat(jdbc.queryForObject("select phase from async_job_lease where job_id=?", String.class, lease.jobId())).isEqualTo("FINISHED");
  }

  @Test void fenceThatWaitedBehindARealExpiryStillLosesOwnershipAndWritesNothing() throws Exception {
    Lease lease = runningLease();
    AtomicBoolean wrote = new AtomicBoolean();
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try (Connection watchdog = dataSource.getConnection()) {
      watchdog.setAutoCommit(false);
      try (var lock = watchdog.prepareStatement("select job_id from async_job_lease where job_id=? for update")) {
        lock.setObject(1, lease.jobId());
        try (var row = lock.executeQuery()) { assertThat(row.next()).isTrue(); }
      }
      Future<?> fence = worker.submit(() -> service.fencedRun(lease, () -> wrote.set(true)));
      awaitFenceBlockedOnRowLock();
      try (var expire = watchdog.prepareStatement("""
          update async_job_lease set phase='EXPIRED',fence_token=fence_token+1,updated_at=clock_timestamp() where job_id=?
          """)) {
        expire.setObject(1, lease.jobId());
        assertThat(expire.executeUpdate()).isEqualTo(1);
      }
      watchdog.commit();
      assertThatThrownBy(() -> fence.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(LeaseLostException.class);
    } finally {
      worker.shutdownNow();
    }
    assertThat(wrote).isFalse();
  }

  @Test void fenceThatWaitedBehindAClockRollbackWatermarkStillLosesOwnership() throws Exception {
    Lease lease = runningLease();
    AtomicBoolean wrote = new AtomicBoolean();
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try (Connection other = dataSource.getConnection()) {
      other.setAutoCommit(false);
      try (var lock = other.prepareStatement("select job_id from async_job_lease where job_id=? for update")) {
        lock.setObject(1, lease.jobId());
        try (var row = lock.executeQuery()) { assertThat(row.next()).isTrue(); }
      }
      Future<?> fence = worker.submit(() -> service.fencedRun(lease, () -> wrote.set(true)));
      awaitFenceBlockedOnRowLock();
      // A watermark that is still ahead of the DB clock AFTER the lock is acquired is a real rollback.
      try (var ahead = other.prepareStatement("""
          update async_job_lease set updated_at=clock_timestamp()+interval '5 minutes',
            lease_until=clock_timestamp()+interval '5 minutes' where job_id=?
          """)) {
        ahead.setObject(1, lease.jobId());
        assertThat(ahead.executeUpdate()).isEqualTo(1);
      }
      other.commit();
      assertThatThrownBy(() -> fence.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(LeaseLostException.class);
    } finally {
      worker.shutdownNow();
    }
    assertThat(wrote).isFalse();
  }

  /** Same persisted state as the observed failure: the DB clock is now ~0.6 s behind the recorded watermark. */
  private void dbClockSteppedBackBy(Lease lease, int millis) {
    jdbc.update("update async_job_lease set updated_at=clock_timestamp()+(? * interval '1 millisecond') where job_id=?",
        millis, lease.jobId());
  }

  private java.sql.Timestamp watermark(Lease lease) {
    return jdbc.queryForObject("select updated_at from async_job_lease where job_id=?", java.sql.Timestamp.class, lease.jobId());
  }

  @Test void subSecondBackwardDbClockStepAfterBeginKeepsALiveWorkerOwning() {
    Lease lease = runningLease();
    dbClockSteppedBackBy(lease, 600);
    java.sql.Timestamp before = watermark(lease);
    AtomicBoolean wrote = new AtomicBoolean();
    assertThat(service.valid(lease)).isTrue();
    assertThatCode(() -> service.fencedRun(lease, () -> wrote.set(true))).doesNotThrowAnyException();
    assertThat(wrote).isTrue();
    service.heartbeat();
    // Forward-only watermark: a renewal under a stepped-back clock must not lower it.
    assertThat(watermark(lease)).isAfterOrEqualTo(before);
    assertThatCode(() -> service.finish(lease, () -> { })).doesNotThrowAnyException();
    assertThat(jdbc.queryForObject("select phase from async_job_lease where job_id=?", String.class, lease.jobId())).isEqualTo("FINISHED");
    assertThat(watermark(lease)).isAfterOrEqualTo(before);
  }

  @Test void queuedCallbackStartsAfterASubSecondBackwardStepAndKeepsTheWatermark() {
    UUID job = UUID.randomUUID();
    UUID revision = UUID.randomUUID();
    Lease lease = new TransactionTemplate(transactions).execute(status -> {
      jdbc.update("insert into exam_plan_revision(id,org_id,plan_id,revision_no) values (?,?,?,?)", revision, org, plan, ++revisionNumber);
      jdbc.update("insert into exam_plan_planning_job(id,org_id,plan_id,revision_id,status) values (?,?,?,?,'QUEUED')", job, org, plan, revision);
      return service.register(Kind.PLANNING, org, job);
    });
    dbClockSteppedBackBy(lease, 600);
    java.sql.Timestamp before = watermark(lease);
    assertThatCode(() -> service.begin(lease, 120_000)).doesNotThrowAnyException();
    assertThat(jdbc.queryForObject("select phase from async_job_lease where job_id=?", String.class, job)).isEqualTo("RUNNING");
    assertThat(watermark(lease)).isAfterOrEqualTo(before);
    // The watchdog must not treat the tolerated step as a rollback either.
    assertThat(new AsyncJobLeaseService(jdbc, transactions, 30_000, 30_000).expire(Kind.PLANNING, expired -> { })).isZero();
    assertThat(service.valid(lease)).isTrue();
  }

  @Test void backwardStepBeyondTheToleranceIsStillARollbackAndIsRecoverable() {
    Lease lease = runningLease();
    dbClockSteppedBackBy(lease, 3_000);
    AtomicBoolean wrote = new AtomicBoolean();
    assertThat(service.valid(lease)).isFalse();
    assertThatThrownBy(() -> service.fencedRun(lease, () -> wrote.set(true))).isInstanceOf(LeaseLostException.class);
    assertThatThrownBy(() -> service.finish(lease, () -> wrote.set(true))).isInstanceOf(LeaseLostException.class);
    assertThat(wrote).isFalse();
    AsyncJobLeaseService restarted = new AsyncJobLeaseService(jdbc, transactions, 30_000, 30_000);
    assertThat(restarted.expire(Kind.PLANNING, expired -> { })).isEqualTo(1);
    assertThat(jdbc.queryForObject("select phase from async_job_lease where job_id=?", String.class, lease.jobId())).isEqualTo("EXPIRED");
  }

  @Test void toleranceIsBoundedByHalfALeaseAndNeverNegative() {
    assertThatThrownBy(() -> new AsyncJobLeaseService(jdbc, transactions, 1_000, 1_000, -1))
        .isInstanceOf(IllegalArgumentException.class);
    // A 1 s lease tolerates at most 500 ms even when configured higher.
    service = new AsyncJobLeaseService(jdbc, transactions, 30_000, 1_000, 2_000);
    Lease lease = runningLease();
    dbClockSteppedBackBy(lease, 800);
    assertThat(service.valid(lease)).isFalse();
    Lease other = runningLease();
    dbClockSteppedBackBy(other, 200);
    assertThat(service.valid(other)).isTrue();
  }

  @Test void concurrentRealHeartbeatsNeverDiscardALiveFencedWorker() throws Exception {
    Lease lease = runningLease();
    AtomicBoolean stop = new AtomicBoolean();
    AtomicInteger renewals = new AtomicInteger();
    ExecutorService heart = Executors.newSingleThreadExecutor();
    Future<?> beating = heart.submit(() -> {
      while (!stop.get()) { service.heartbeat(); renewals.incrementAndGet(); }
    });
    int lost = 0;
    try {
      for (int i = 0; i < 300; i++) {
        try { service.fencedRun(lease, () -> { }); } catch (LeaseLostException e) { lost++; }
      }
    } finally {
      stop.set(true);
      beating.get(10, TimeUnit.SECONDS);
      heart.shutdownNow();
    }
    assertThat(renewals.get()).isGreaterThan(0);
    assertThat(lost).as("live fenced writes discarded as lease loss while the real heartbeat renewed").isZero();
    assertThat(service.valid(lease)).isTrue();
  }
}
