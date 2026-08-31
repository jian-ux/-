package com.feisheng.bot.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Bounded executor for durable customer-memory outbox work. */
@Configuration
@EnableScheduling
public class CustomerMemoryOutboxExecutorConfig {
    @Bean(name = "customerMemoryOutboxExecutor")
    public ThreadPoolTaskExecutor customerMemoryOutboxExecutor(
            @Value("${customer-memory.outbox.executor.core-threads:2}") int coreThreads,
            @Value("${customer-memory.outbox.executor.max-threads:4}") int maxThreads,
            @Value("${customer-memory.outbox.executor.queue-capacity:64}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, coreThreads));
        executor.setMaxPoolSize(Math.max(Math.max(1, coreThreads), maxThreads));
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setThreadNamePrefix("customer-memory-outbox-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
