package com.jkxt.common.jobs;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared ownership protocol. No method reconstructs input or replays an expired job.
 * updated_at is the last persisted DB-clock watermark: a clock behind it invalidates ownership,
 * rather than extending a lease or waiting for time to catch up. This detects rollback past a
 * recorded watermark, not every clock adjustment; a stable deployment clock is still required.
 * PB-20: a small backward step of the DB clock (measured ~0.6 s on the Docker Desktop VM) is not a
 * rollback. The watermark only moves forward (greatest(updated_at, now)) and ownership is lost only
 * when the DB clock is behind the highest recorded watermark by MORE than clockStepToleranceMs.
 * Lease expiry itself (lease_until/hard_deadline_at) is unaffected; fence tokens still exclude stale owners.
 */
@Service
public class AsyncJobLeaseService {
  public enum Kind { PLANNING, SCHEDULE }
  public record Lease(UUID orgId, Kind kind, UUID jobId, UUID ownerInstance, long fenceToken) { }
  public static final class LeaseLostException extends RuntimeException {
    public LeaseLostException() { super("Job execution lease is unavailable or expired"); }
  }
  public static final class ParentBusyException extends RuntimeException {
    private ParentBusyException(Throwable cause) { super("Job parent is temporarily locked", cause); }
  }
  private static final long PARENT_LOCK_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(750);
  private static final Logger log = LoggerFactory.getLogger(AsyncJobLeaseService.class);
  private final UUID owner = UUID.randomUUID();
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final long queueTimeoutMs;
  private final long leaseDurationMs;
  private final long clockStepToleranceMs;
  private final Set<Lease> running = ConcurrentHashMap.newKeySet();

  public static final long DEFAULT_CLOCK_STEP_TOLERANCE_MS = 2000;

  public AsyncJobLeaseService(JdbcTemplate jdbc, PlatformTransactionManager transactions,
      long queueTimeoutMs, long leaseDurationMs) {
    this(jdbc, transactions, queueTimeoutMs, leaseDurationMs, DEFAULT_CLOCK_STEP_TOLERANCE_MS);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public AsyncJobLeaseService(JdbcTemplate jdbc, PlatformTransactionManager transactions,
      @Value("${jkxt.jobs.queue-timeout-ms:120000}") long queueTimeoutMs,
      @Value("${jkxt.jobs.lease-duration-ms:30000}") long leaseDurationMs,
      @Value("${jkxt.jobs.clock-step-tolerance-ms:" + DEFAULT_CLOCK_STEP_TOLERANCE_MS + "}") long clockStepToleranceMs) {
    if (queueTimeoutMs <= 0 || leaseDurationMs <= 0) throw new IllegalArgumentException("Lease durations must be positive");
    if (clockStepToleranceMs < 0) throw new IllegalArgumentException("Clock step tolerance must not be negative");
    // Never tolerate a step that is a large share of one lease period.
    this.clockStepToleranceMs = Math.min(clockStepToleranceMs, leaseDurationMs / 2);
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactions);
    // Never reuse a completed request transaction; rejection handlers must not call this inside afterCommit.
    this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.queueTimeoutMs = queueTimeoutMs;
    this.leaseDurationMs = leaseDurationMs;
  }

  /** Must join the transaction that inserts the job and updates its aggregate pointer. */
  public Lease register(Kind kind, UUID orgId, UUID jobId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Register a lease in the job creation transaction");
    }
    jdbc.update("""
        with db_clock as materialized (select clock_timestamp() as observed_at)
        insert into async_job_lease(org_id,job_kind,job_id,planning_job_id,schedule_run_id,
          owner_instance,fence_token,phase,lease_until,hard_deadline_at,updated_at)
        select ?,?,?,?,?,?,1,'QUEUED',observed_at+(? * interval '1 millisecond'),
          observed_at+(? * interval '1 millisecond'),observed_at from db_clock
        """, orgId, kind.name(), jobId, kind == Kind.PLANNING ? jobId : null,
        kind == Kind.SCHEDULE ? jobId : null, owner, queueTimeoutMs, queueTimeoutMs);
    return new Lease(orgId, kind, jobId, owner, 1);
  }

  /** Starts the captured lease once. An expired queued callback cannot acquire a fresh token. */
  public void begin(Lease lease, long maximumRunMillis) {
    if (maximumRunMillis <= 0) throw new IllegalArgumentException("Run deadline must be positive");
    fenced(lease, () -> {
      int changed = jdbc.update("""
          with db_clock as materialized (select clock_timestamp() as observed_at)
          update async_job_lease set phase='RUNNING',
            hard_deadline_at=observed_at+(? * interval '1 millisecond'),
            lease_until=observed_at+(? * interval '1 millisecond'), updated_at=greatest(updated_at,observed_at) from db_clock
          where org_id=? and job_kind=? and job_id=? and owner_instance=? and fence_token=? and phase='QUEUED'
            and lease_until>observed_at and hard_deadline_at>observed_at
            and updated_at<=observed_at+(? * interval '1 millisecond')
          """, maximumRunMillis, Math.min(maximumRunMillis, leaseDurationMs), lease.orgId(), lease.kind().name(),
          lease.jobId(), lease.ownerInstance(), lease.fenceToken(), clockStepToleranceMs);
      if (changed != 1) throw new LeaseLostException();
      return null;
    });
    running.add(lease);
  }

  /** Lock and validate ownership before AND after all output writes in the same DB transaction. */
  public <T> T fenced(Lease lease, Supplier<T> action) {
    return tx.execute(status -> {
      requireFence(lease);
      T result = action.get();
      requireFence(lease);
      return result;
    });
  }

  public void fencedRun(Lease lease, Runnable action) {
    fenced(lease, () -> { action.run(); return null; });
  }

  public void finish(Lease lease, Runnable terminalWrites) {
    tx.executeWithoutResult(status -> {
      requireFence(lease);
      terminalWrites.run();
      requireFence(lease);
      int changed = jdbc.update("""
          with db_clock as materialized (select clock_timestamp() as observed_at)
          update async_job_lease set phase='FINISHED',fence_token=fence_token+1,updated_at=greatest(updated_at,observed_at) from db_clock
          where org_id=? and job_kind=? and job_id=? and owner_instance=? and fence_token=?
            and phase in ('QUEUED','RUNNING') and lease_until>observed_at
            and hard_deadline_at>observed_at and updated_at<=observed_at+(? * interval '1 millisecond')
          """, lease.orgId(), lease.kind().name(), lease.jobId(), lease.ownerInstance(), lease.fenceToken(), clockStepToleranceMs);
      if (changed != 1) throw new LeaseLostException();
    });
    stopTracking(lease);
  }

  public void stopTracking(Lease lease) { running.remove(lease); }

  public boolean valid(Lease lease) {
    // An unavailable database throws: inability to establish ownership must stop the worker.
    return Boolean.TRUE.equals(jdbc.queryForObject("""
        with db_clock as materialized (select clock_timestamp() as observed_at)
        select exists(select 1 from async_job_lease,db_clock where org_id=? and job_kind=? and job_id=?
          and owner_instance=? and fence_token=? and phase in ('QUEUED','RUNNING')
          and lease_until>observed_at and hard_deadline_at>observed_at and updated_at<=observed_at+(? * interval '1 millisecond'))
        """, Boolean.class, lease.orgId(), lease.kind().name(), lease.jobId(), lease.ownerInstance(), lease.fenceToken(),
        clockStepToleranceMs));
  }

  private void requireFence(Lease lease) {
    if (lease.kind() == Kind.SCHEDULE) {
      // The public delete cascades task -> run -> lease. Normal worker writes must use
      // that same order, including progress and begin (not just terminal persistence).
      lockScheduleParents(lease);
    }
    List<Long> tokens = jdbc.queryForList("""
        with db_clock as materialized (select clock_timestamp() as observed_at)
        select fence_token from async_job_lease l,db_clock where org_id=? and job_kind=? and job_id=?
          and owner_instance=? and fence_token=? and phase in ('QUEUED','RUNNING')
          and lease_until>observed_at and hard_deadline_at>observed_at
          and updated_at<=observed_at+(? * interval '1 millisecond') for update of l
        """, Long.class, lease.orgId(), lease.kind().name(), lease.jobId(), lease.ownerInstance(), lease.fenceToken(),
        clockStepToleranceMs);
    if (tokens.size() != 1) throw new LeaseLostException();
  }

  private void lockScheduleParents(Lease lease) {
    String previousTimeout = jdbc.queryForObject("select current_setting('lock_timeout')", String.class);
    long deadline = System.nanoTime() + PARENT_LOCK_WAIT_NANOS;
    try {
      setRemainingParentLockTimeout(deadline);
      List<UUID> parents = jdbc.queryForList("select exam_task_id from schedule_run where org_id=? and id=?",
          UUID.class, lease.orgId(), lease.jobId());
      if (parents.size() != 1) throw new LeaseLostException();
      setRemainingParentLockTimeout(deadline);
      if (jdbc.queryForList("select id from exam_task where org_id=? and id=? for update",
          UUID.class, lease.orgId(), parents.getFirst()).size() != 1) throw new LeaseLostException();
      setRemainingParentLockTimeout(deadline);
      if (jdbc.queryForList("select id from schedule_run where org_id=? and id=? for update",
          UUID.class, lease.orgId(), lease.jobId()).size() != 1) throw new LeaseLostException();
      jdbc.queryForObject("select set_config('lock_timeout',?,true)", String.class, previousTimeout);
    } catch (DataAccessException failure) {
      for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
        if (cause instanceof SQLException sql && "55P03".equals(sql.getSQLState())) {
          // The PG statement aborts this transaction. Propagate, allowing TransactionTemplate
          // to roll back every write and SET LOCAL; do not query again in the aborted transaction.
          throw new ParentBusyException(failure);
        }
      }
      throw failure;
    }
  }

  private void setRemainingParentLockTimeout(long deadline) {
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) throw new ParentBusyException(null);
    long millis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining));
    jdbc.queryForObject("select set_config('lock_timeout',?,true)", String.class, millis + "ms");
  }

  @Scheduled(scheduler = "jobLeaseScheduler", fixedDelayString = "${jkxt.jobs.heartbeat-interval-ms:10000}")
  public void heartbeat() {
    for (Lease lease : running) {
      try {
        Boolean retained = jdbc.execute((ConnectionCallback<Boolean>) connection -> {
          // A worker's fenced output transaction owns this same row lock. Do not wait for it:
          // one slow job must not prevent unrelated live jobs from renewing their leases.
          try (var statement = connection.prepareStatement("""
              with db_clock as materialized (select clock_timestamp() as observed_at), available as (
                select org_id,job_kind,job_id from async_job_lease l,db_clock
                where org_id=? and job_kind=? and job_id=? and owner_instance=? and fence_token=?
                  and phase='RUNNING' and lease_until>observed_at and hard_deadline_at>observed_at
                  and updated_at<=observed_at+(? * interval '1 millisecond')
                for update of l skip locked
              ), renewed as (
                update async_job_lease l set lease_until=least(l.hard_deadline_at,
                  c.observed_at+(? * interval '1 millisecond')),updated_at=greatest(l.updated_at,c.observed_at)
                from available a,db_clock c where l.org_id=a.org_id and l.job_kind=a.job_kind and l.job_id=a.job_id
                  and l.phase='RUNNING' and l.lease_until>c.observed_at and l.hard_deadline_at>c.observed_at
                  and l.updated_at<=c.observed_at+(? * interval '1 millisecond')
                returning l.job_id
              )
              select exists(select 1 from renewed) or exists(
                select 1 from async_job_lease,db_clock
                where org_id=? and job_kind=? and job_id=? and owner_instance=? and fence_token=?
                  and phase='RUNNING' and lease_until>observed_at and hard_deadline_at>observed_at
                  and updated_at<=observed_at+(? * interval '1 millisecond'))
              """)) {
            // SKIP LOCKED handles row locks, not e.g. an ACCESS EXCLUSIVE relation lock.
            // Bound each database statement without changing the shared JdbcTemplate defaults.
            statement.setQueryTimeout(2);
            statement.setObject(1, lease.orgId());
            statement.setString(2, lease.kind().name());
            statement.setObject(3, lease.jobId());
            statement.setObject(4, lease.ownerInstance());
            statement.setLong(5, lease.fenceToken());
            statement.setLong(6, clockStepToleranceMs);
            statement.setLong(7, leaseDurationMs);
            statement.setLong(8, clockStepToleranceMs);
            statement.setObject(9, lease.orgId());
            statement.setString(10, lease.kind().name());
            statement.setObject(11, lease.jobId());
            statement.setObject(12, lease.ownerInstance());
            statement.setLong(13, lease.fenceToken());
            statement.setLong(14, clockStepToleranceMs);
            try (var result = statement.executeQuery()) {
              if (!result.next()) throw new IllegalStateException("Heartbeat returned no ownership state");
              return result.getBoolean(1);
            }
          }
        });
        // An unavailable lock is not lost ownership. Only a visible expired/replaced/finished
        // lease is forgotten; all output writes still require the existing transactional fence.
        if (!Boolean.TRUE.equals(retained)) stopTracking(lease);
      } catch (RuntimeException unavailable) {
        log.warn("Job lease heartbeat unavailable; ownership is not extended for {}", lease.jobId());
      }
    }
  }

  /** Callback runs with the expired row locked, in the SAME transaction as token invalidation. */
  public int expire(Kind kind, Consumer<Lease> terminalWrites) {
    return expireIfReady(kind, lease -> { terminalWrites.accept(lease); return true; });
  }

  /** A busy aggregate may defer recovery without invalidating ownership or blocking unrelated jobs.
   * A false callback must perform no writes; it only tries nonblocking aggregate locks.
   */
  public int expireIfReady(Kind kind, Predicate<Lease> terminalWrites) {
    Integer count = tx.execute(status -> {
      List<Lease> expired = jdbc.query("""
          with db_clock as materialized (select clock_timestamp() as observed_at)
          select org_id,job_id,owner_instance,fence_token from async_job_lease l,db_clock
          where job_kind=? and phase in ('QUEUED','RUNNING')
            and (lease_until<=observed_at or hard_deadline_at<=observed_at
              or updated_at>observed_at+(? * interval '1 millisecond'))
          order by lease_until limit 100 for update of l skip locked
          """, (rs, row) -> new Lease(rs.getObject(1, UUID.class), kind, rs.getObject(2, UUID.class),
              rs.getObject(3, UUID.class), rs.getLong(4)), kind.name(), clockStepToleranceMs);
      int recovered = 0;
      for (Lease lease : expired) {
        if (!terminalWrites.test(lease)) continue;
        jdbc.update("""
            update async_job_lease set phase='EXPIRED',fence_token=fence_token+1,updated_at=clock_timestamp()
            where org_id=? and job_kind=? and job_id=? and owner_instance=? and fence_token=?
            """, lease.orgId(), kind.name(), lease.jobId(), lease.ownerInstance(), lease.fenceToken());
        recovered++;
      }
      return recovered;
    });
    return count == null ? 0 : count;
  }
}
