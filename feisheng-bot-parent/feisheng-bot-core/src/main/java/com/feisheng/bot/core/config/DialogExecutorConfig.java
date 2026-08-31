package com.feisheng.bot.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded executor for request-scoped customer context reads. */
@Configuration
public class DialogExecutorConfig {
    @Bean(name = "customerContextExecutor")
    public Executor customerContextExecutor(DialogPerformanceProperties properties) {
        int cores = properties.getContextCoreThreads();
        int max = Math.max(cores, properties.getContextMaxThreads());
        int queue = properties.getContextQueueCapacity();
        return new ThreadPoolExecutor(cores, max, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queue), new ThreadPoolExecutor.AbortPolicy());
    }
}
