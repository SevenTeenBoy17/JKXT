package com.jkxt.common.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jkxt.common.jobs.AsyncJobLeaseService.Kind;
import com.jkxt.common.jobs.AsyncJobLeaseService.Lease;
import com.jkxt.common.jobs.AsyncJobLeaseService.LeaseLostException;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"jkxt.jobs.heartbeat-interval-ms=3600000", "jkxt.jobs.watchdog-interval-ms=3600000"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LeaseClockRollbackIntegrationTest {
  private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  private AsyncJobLeaseService leases;
  private Lease lease;
  private UUID plan;
  private UUID revision;
  private UUID job;

  @BeforeEach void before() {
    leases = new AsyncJobLeaseService(jdbc, transactions, 120000, 30000);
    plan = jdbc.queryForObject("insert into exam_plan(org_id,plan_no,name) values (?,?,'Clock boundary') returning id",
        UUID.class, ORG, "CLOCK-" + UUID.randomUUID());
    revision = jdbc.queryForObject("insert into exam_plan_revision(org_id,plan_id,revision_no) values (?,?,1) returning id",
        UUID.class, ORG, plan);
    job = jdbc.queryForObject("insert into exam_plan_planning_job(org_id,plan_id,revision_id,status) values (?,?,?,'QUEUED') returning id",
        UUID.class, ORG, plan, revision);
    lease = new TransactionTemplate(transactions).execute(status -> leases.register(Kind.PLANNING, ORG, job));
  }
  @AfterEach void after() {
    if (lease != null) leases.stopTracking(lease);
    if (plan != null) jdbc.update("delete from exam_plan where id=?", plan);
  }

  @Test void queuedCallbackCannotStartWhenDbClockIsBehindItsRecordedWatermark() {
    simulateRollback();
    assertThat(leases.valid(lease)).isFalse();
    assertThatThrownBy(() -> leases.begin(lease, 120000)).isInstanceOf(LeaseLostException.class);
    assertThat(jdbc.queryForObject("select phase from async_job_lease where job_id=?", String.class, job)).isEqualTo("QUEUED");
  }

  @Test void activeWorkerCannotWriteOrSilentlyRenewAcrossBackwardClockBoundary() {
    leases.begin(lease, 120000);
    simulateRollback();
    Timestamp expiry = jdbc.queryForObject("select lease_until from async_job_lease where job_id=?", Timestamp.class, job);
    AtomicBoolean wrote = new AtomicBoolean();
    assertThat(leases.valid(lease)).isFalse();
    assertThatThrownBy(() -> leases.fencedRun(lease, () -> wrote.set(true))).isInstanceOf(LeaseLostException.class);
    assertThatThrownBy(() -> leases.finish(lease, () -> wrote.set(true))).isInstanceOf(LeaseLostException.class);
    leases.heartbeat();
    assertThat(wrote).isFalse();
    assertThat(jdbc.queryForObject("select lease_until from async_job_lease where job_id=?", Timestamp.class, job)).isEqualTo(expiry);
    assertThat(jdbc.queryForObject("select phase from async_job_lease where job_id=?", String.class, job)).isEqualTo("RUNNING");
  }

  @Test void freshProcessCanInvalidateRollbackLeaseWithoutWaitingForClockToCatchUp() {
    leases.begin(lease, 120000);
    simulateRollback();
    // Separate owner and empty local tracking: this is persisted-state recovery, not an in-memory flag.
    AsyncJobLeaseService restarted = new AsyncJobLeaseService(jdbc, transactions, 120000, 30000);
    assertThat(restarted.expire(Kind.PLANNING, expired -> jdbc.update(
        "update exam_plan_planning_job set status='FAILED',stage='WORKER_LOST' where org_id=? and id=?",
        expired.orgId(), expired.jobId()))).isEqualTo(1);
    assertThat(jdbc.queryForObject("select status from exam_plan_planning_job where id=?", String.class, job)).isEqualTo("FAILED");
    assertThat(jdbc.queryForObject("select phase from async_job_lease where job_id=?", String.class, job)).isEqualTo("EXPIRED");
    assertThat(jdbc.queryForObject("select fence_token from async_job_lease where job_id=?", Long.class, job)).isEqualTo(2L);
    assertThatThrownBy(() -> leases.finish(lease, () -> jdbc.update(
        "update exam_plan_planning_job set status='SUCCEEDED' where id=?", job))).isInstanceOf(LeaseLostException.class);
    assertThat(jdbc.queryForObject("select status from exam_plan_planning_job where id=?", String.class, job)).isEqualTo("FAILED");
  }

  @Test void concurrentQueuedCallbacksClaimOnceAndFinishedTokenCannotWriteAgain() throws Exception {
    var workers = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    java.util.concurrent.Callable<Boolean> claim = () -> {
      ready.countDown();
      if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test coordination timed out");
      try { leases.begin(lease, 120000); return true; }
      catch (LeaseLostException lost) { return false; }
    };
    try {
      var first = workers.submit(claim);
      var second = workers.submit(claim);
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(java.util.List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
      assertThat(jdbc.queryForObject("select phase from async_job_lease where job_id=?", String.class, job)).isEqualTo("RUNNING");
      leases.finish(lease, () -> jdbc.update("update exam_plan_planning_job set status='SUCCEEDED' where id=?", job));
      assertThat(jdbc.queryForObject("select phase from async_job_lease where job_id=?", String.class, job)).isEqualTo("FINISHED");
      assertThat(jdbc.queryForObject("select fence_token from async_job_lease where job_id=?", Long.class, job)).isEqualTo(2L);
      assertThatThrownBy(() -> leases.fencedRun(lease, () -> jdbc.update(
          "update exam_plan_planning_job set status='FAILED' where id=?", job))).isInstanceOf(LeaseLostException.class);
      assertThat(jdbc.queryForObject("select status from exam_plan_planning_job where id=?", String.class, job)).isEqualTo("SUCCEEDED");
    } finally {
      start.countDown();
      workers.shutdownNow();
      assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private void simulateRollback() {
    // Move only the persisted last-observed DB watermark forward. Never change the host/DB wall clock.
    jdbc.update("update async_job_lease set updated_at=clock_timestamp()+interval '5 minutes', "
        + "lease_until=clock_timestamp()+interval '5 minutes',hard_deadline_at=clock_timestamp()+interval '6 minutes' "
        + "where org_id=? and job_id=?", ORG, job);
  }
}
