package com.jkxt.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class ScheduleExecutorSaturationTest {
  @Test
  void saturatedExecutorRejectsWithoutRunningSolverOnCallerThread() throws Exception {
    ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new ScheduleAsyncConfig().scheduleTaskExecutor();
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch active = new CountDownLatch(4);
    AtomicBoolean ranRejectedWork = new AtomicBoolean();
    try {
      // Production capacity: 4 workers plus 32 queued jobs. No solver, DB or timing sleep is mocked.
      for (int i = 0; i < 36; i++) executor.execute(() -> {
        active.countDown();
        try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      });
      assertThat(active.await(5, TimeUnit.SECONDS)).as("all worker slots are occupied").isTrue();
      long before = System.nanoTime();
      assertThatThrownBy(() -> executor.execute(() -> ranRejectedWork.set(true)))
          .isInstanceOf(RejectedExecutionException.class);
      assertThat(Duration.ofNanos(System.nanoTime() - before)).isLessThan(Duration.ofSeconds(1));
      assertThat(ranRejectedWork).as("caller must never execute rejected solver work").isFalse();
      assertThat(executor.getThreadPoolExecutor().getQueue()).hasSize(32);
    } finally {
      release.countDown();
      executor.shutdown();
      assertThat(executor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }
}
