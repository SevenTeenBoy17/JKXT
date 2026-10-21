package com.jkxt.common.jobs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
public class JobLeaseConfig {
  @Bean(name = "jobLeaseScheduler")
  ThreadPoolTaskScheduler jobLeaseScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("jkxt-job-lease-");
    scheduler.setRemoveOnCancelPolicy(true);
    return scheduler;
  }
}
