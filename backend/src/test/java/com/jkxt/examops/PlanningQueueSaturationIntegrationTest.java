package com.jkxt.examops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.jkxt.common.tenant.TenantContext;
import com.jkxt.examops.api.PlanningJobDtos.PlanningJobRequest;
import com.jkxt.examops.service.ExamPlanPlanningService;
import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {"spring.datasource.hikari.maximum-pool-size=2", "spring.datasource.hikari.minimum-idle=0",
    "spring.datasource.hikari.connection-timeout=1000", "jkxt.jobs.queue-timeout-ms=120000",
    "jkxt.jobs.heartbeat-interval-ms=3600000", "jkxt.jobs.watchdog-interval-ms=3600000"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PlanningQueueSaturationIntegrationTest {
  private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000204");
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;
  @Autowired ExamPlanPlanningService planning;
  @MockitoBean(name = "scheduleTaskExecutor") Executor executor;

  @Test void rejectedAfterCommitCallbackNeverBorrowsSecondConnectionAndWatchdogMakesRetryPossible() throws Exception {
    UUID plan = jdbc.queryForObject("insert into exam_plan(org_id,plan_no,name) values (?,?,'Queue saturation') returning id",
        UUID.class, ORG, "QUEUE-" + UUID.randomUUID());
    UUID revision = jdbc.queryForObject("insert into exam_plan_revision(org_id,plan_id,revision_no) values (?,?,1) returning id",
        UUID.class, ORG, plan);
    jdbc.update("update exam_plan set active_revision_id=? where id=?", revision, plan);
    CountDownLatch atRejection = new CountDownLatch(1);
    CountDownLatch releaseRejection = new CountDownLatch(1);
    doAnswer(invocation -> {
      atRejection.countDown();
      if (!releaseRejection.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test coordination timed out");
      throw new RejectedExecutionException("controlled isolated saturation");
    }).when(executor).execute(any(Runnable.class));
    var caller = Executors.newSingleThreadExecutor();
    // Flyway needs two connections at startup; reserve one only after startup, leaving exactly
    // one for the request. This models all connections being held by concurrent request work.
    try (var reserved = dataSource.getConnection()) {
      var future = caller.submit(() -> {
        TenantContext.set(ORG);
        try { return planning.start(plan, revision, new PlanningJobRequest("JOINT", 0L, true, 120), UUID.randomUUID().toString(), ACTOR); }
        finally { TenantContext.clear(); }
      });
      assertThat(atRejection.await(5, TimeUnit.SECONDS)).isTrue();
      HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
      assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isEqualTo(2);
      releaseRejection.countDown();
      int peakWaiting = 0;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (!future.isDone() && System.nanoTime() < deadline) {
        peakWaiting = Math.max(peakWaiting, pool.getHikariPoolMXBean().getThreadsAwaitingConnection());
        Thread.sleep(2);
      }
      var queued = future.get(1, TimeUnit.SECONDS);
      assertThat(peakWaiting).as("rejected afterCommit may not wait for a second DB connection").isZero();
      assertThat(queued.status()).isEqualTo("QUEUED");
      jdbc.update("update async_job_lease set lease_until='2001-01-01T00:00:00Z'::timestamptz where job_id=?", queued.id());
      planning.recoverExpiredPlanningJobs();
      TenantContext.set(ORG);
      assertThat(planning.get(queued.id()).status()).isEqualTo("FAILED");
      assertThat(planning.get(queued.id()).stage()).isEqualTo("WORKER_LOST");
      var retry = planning.start(plan, revision, new PlanningJobRequest("JOINT", 0L, true, 120), UUID.randomUUID().toString(), ACTOR);
      assertThat(retry.id()).isNotEqualTo(queued.id());
      assertThat(retry.status()).isEqualTo("QUEUED");
      assertThat(jdbc.queryForObject("select count(*) from exam_plan_session where revision_id=?", Integer.class, revision)).isZero();
    } finally {
      releaseRejection.countDown();
      caller.shutdownNow();
      assertThat(caller.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      TenantContext.clear();
      jdbc.update("delete from api_idempotency_record where resource_id in (select id from exam_plan_planning_job where plan_id=?)", plan);
      jdbc.update("update exam_plan_revision set status='CANCELLED' where plan_id=?", plan);
      jdbc.update("delete from exam_plan where id=?", plan);
    }
  }
}
