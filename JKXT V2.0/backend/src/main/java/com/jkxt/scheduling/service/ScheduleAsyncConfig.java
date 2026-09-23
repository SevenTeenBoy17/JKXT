package com.jkxt.scheduling.service;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ScheduleAsyncConfig {

  @Bean(name = "demoScheduleExecutor")
  ThreadPoolTaskExecutor demoScheduleExecutor() {
    ThreadPoolTaskExecutor executor=new ThreadPoolTaskExecutor();
    executor.setThreadNamePrefix("jkxt-demo-solve-");
    executor.setCorePoolSize(1);executor.setMaxPoolSize(1);executor.setQueueCapacity(20);
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    executor.setWaitForTasksToCompleteOnShutdown(true);executor.setAwaitTerminationSeconds(35);
    executor.initialize();return executor;
  }

  @Bean(name="demoSolveControlScheduler", destroyMethod="shutdown")
  java.util.concurrent.ScheduledThreadPoolExecutor demoSolveControlScheduler() {
    return scheduler(2,"jkxt-demo-stop-");
  }

  @Bean(name="scheduleStreamChecks", destroyMethod="shutdown")
  java.util.concurrent.ScheduledThreadPoolExecutor scheduleStreamChecks() {
    return scheduler(2,"jkxt-stream-check-");
  }

  @Bean(name="demoSolveMaintenanceScheduler", destroyMethod="shutdown")
  java.util.concurrent.ScheduledThreadPoolExecutor demoSolveMaintenanceScheduler() {
    return scheduler(1,"jkxt-demo-reconcile-");
  }

  @Bean(name="scheduleStreamExecutor")
  ThreadPoolTaskExecutor scheduleStreamExecutor() {
    ThreadPoolTaskExecutor executor=new ThreadPoolTaskExecutor();
    executor.setThreadNamePrefix("jkxt-stream-send-");executor.setCorePoolSize(4);executor.setMaxPoolSize(4);
    executor.setQueueCapacity(128);executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    executor.initialize();return executor;
  }

  private java.util.concurrent.ScheduledThreadPoolExecutor scheduler(int count,String prefix) {
    var sequence=new java.util.concurrent.atomic.AtomicInteger();
    var scheduler=new java.util.concurrent.ScheduledThreadPoolExecutor(count,r->{
      var thread=new Thread(r,prefix+sequence.incrementAndGet());thread.setDaemon(true);return thread;
    });
    scheduler.setRemoveOnCancelPolicy(true);scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);return scheduler;
  }

  @Bean(name = "scheduleTaskExecutor")
  Executor scheduleTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setThreadNamePrefix("jkxt-schedule-");
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(32);
    // 迭代11 P2: 默认 AbortPolicy 在池+队列全满时抛 RejectedExecutionException,而 ScheduleRun 行已先以
    // status='pending' 提交 → runAsync 提交被拒后无处置(无 exceptionally/无 reaper),行永久卡在 pending。
    // 改用 CallerRunsPolicy 施加背压:极端并发下在调用线程就地执行该次求解,既不丢任务也不留僵尸行。
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.initialize();
    return executor;
  }
}
