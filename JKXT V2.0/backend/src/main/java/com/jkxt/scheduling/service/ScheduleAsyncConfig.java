package com.jkxt.scheduling.service;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ScheduleAsyncConfig {

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
