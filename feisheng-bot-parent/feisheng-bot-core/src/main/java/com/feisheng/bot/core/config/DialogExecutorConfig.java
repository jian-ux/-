package com.feisheng.bot.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded executor for request-scoped customer context reads. */
@Configuration
public class DialogExecutorConfig {
    @Value("${rag.context.executor.core-threads:3}")
    private int coreThreads;
    @Value("${rag.context.executor.max-threads:6}")
    private int maxThreads;
    @Value("${rag.context.executor.queue-capacity:32}")
    private int queueCapacity;

    @Bean(name = "customerContextExecutor")
    public Executor customerContextExecutor() {
        int cores = Math.max(1, coreThreads);
        int max = Math.max(cores, maxThreads);
        int queue = Math.max(1, queueCapacity);
        return new ThreadPoolExecutor(cores, max, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queue), new ThreadPoolExecutor.AbortPolicy());
    }
}
