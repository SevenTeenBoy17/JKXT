package com.jkxt.common.jobs;

import static org.assertj.core.api.Assertions.*;

import com.jkxt.common.jobs.AsyncJobLeaseService.Kind;
import com.jkxt.common.jobs.AsyncJobLeaseService.Lease;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"spring.datasource.hikari.maximum-pool-size=5", "spring.datasource.hikari.minimum-idle=0"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HeartbeatLockIsolationIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;
  @Autowired PlatformTransactionManager transactions;
  private UUID org;
  private UUID plan;
  private int revisionNumber;
  private AsyncJobLeaseService service;

  @BeforeEach void prepare() {
    org = UUID.randomUUID(); plan = UUID.randomUUID(); revisionNumber = 0;
    jdbc.update("insert into organization(id,name,slug) values (?,'Heartbeat isolation',?)", org, "heartbeat-" + org);
    jdbc.update("insert into exam_plan(id,org_id,plan_no,name) values (?,?,?,'Heartbeat isolation')", plan, org, plan.toString());
    // Independent real service instance: the Spring scheduler cannot renew these test-owned leases.
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
    jdbc.update("update async_job_lease set lease_until=clock_timestamp()+interval '10 seconds' where job_id=?", job);
    return lease;
  }
  @SuppressWarnings("unchecked")
  private Set<Lease> tracked() { return (Set<Lease>) ReflectionTestUtils.getField(service, "running"); }
  private Instant expiry(Lease lease) {
    return jdbc.queryForObject("select lease_until from async_job_lease where job_id=?", Timestamp.class, lease.jobId()).toInstant();
  }

  @Test void oneLockedLeaseCannotBlockAnotherAndSkippedLeaseRemainsTrackedUntilUnlock() throws Exception {
    runningLease(); runningLease();
    // Choose the first actual iteration entry solely to schedule the blocking fault deterministically.
    // The oracle is observable persisted renewal, not a private-method invocation or SQL string.
    Iterator<Lease> order = tracked().iterator();
    Lease locked = order.next(), healthy = order.next();
    Instant lockedBefore = expiry(locked), healthyBefore = expiry(healthy);
    ExecutorService worker = Executors.newSingleThreadExecutor();
    Future<?> heartbeat = null;
    try (Connection lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      try (var statement = lock.prepareStatement("select job_id from async_job_lease where job_id=? for update")) {
        statement.setObject(1, locked.jobId());
        try (var result = statement.executeQuery()) { assertThat(result.next()).isTrue(); }
      }
      try {
        heartbeat = worker.submit(service::heartbeat);
        Future<?> pending = heartbeat;
        assertThatCode(() -> pending.get(3, TimeUnit.SECONDS)).doesNotThrowAnyException();
        assertThat(expiry(healthy)).isAfter(healthyBefore);
        assertThat(expiry(locked)).isEqualTo(lockedBefore);
        assertThat(tracked()).contains(locked, healthy);
      } finally {
        lock.rollback();
      }
      service.heartbeat();
      assertThat(expiry(locked)).isAfter(lockedBefore);
      assertThat(tracked()).contains(locked, healthy);
    } finally {
      if (heartbeat != null) heartbeat.get(5, TimeUnit.SECONDS);
      worker.shutdownNow();
    }
  }

  @Test void expiredOrSupersededLeaseNeverRenewsAndIsRemovedFromTracking() {
    Lease expired = runningLease(), fenced = runningLease();
    jdbc.update("update async_job_lease set lease_until='2001-01-01T00:00:00Z'::timestamptz where job_id=?", expired.jobId());
    jdbc.update("update async_job_lease set fence_token=fence_token+1 where job_id=?", fenced.jobId());
    Instant expiredBefore = expiry(expired), fencedBefore = expiry(fenced);
    service.heartbeat();
    assertThat(expiry(expired)).isEqualTo(expiredBefore);
    assertThat(expiry(fenced)).isEqualTo(fencedBefore);
    assertThat(service.valid(expired)).isFalse();
    assertThat(service.valid(fenced)).isFalse();
    assertThat(tracked()).doesNotContain(expired, fenced);
  }

  @Test void databaseLockWaitHasABoundAndDoesNotDiscardOtherwiseLiveOwnership() throws Exception {
    Lease lease = runningLease();
    Instant before = expiry(lease);
    ExecutorService worker = Executors.newSingleThreadExecutor();
    Future<?> heartbeat = null;
    try (Connection lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      try (var statement = lock.createStatement()) { statement.execute("lock table async_job_lease in access exclusive mode"); }
      try {
        heartbeat = worker.submit(service::heartbeat);
        Future<?> pending = heartbeat;
        assertThatCode(() -> pending.get(5, TimeUnit.SECONDS)).doesNotThrowAnyException();
        assertThat(tracked()).contains(lease);
      } finally { lock.rollback(); }
      service.heartbeat();
      assertThat(expiry(lease)).isAfter(before);
    } finally {
      if (heartbeat != null) heartbeat.get(5, TimeUnit.SECONDS);
      worker.shutdownNow();
    }
  }
}
