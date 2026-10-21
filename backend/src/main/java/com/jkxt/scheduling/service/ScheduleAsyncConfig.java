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
    // Never execute a solver on an HTTP/transaction-completion thread: that thread may still
    // own a connection while the worker needs REQUIRES_NEW. Persisted job leases recover rejection.
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    executor.initialize();
    return executor;
  }
}
