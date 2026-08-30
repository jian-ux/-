package com.feisheng.bot.admin.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class KnowledgeMigrationExecutorConfig {
    @Bean(name = "knowledgeMigrationExecutor")
    public Executor knowledgeMigrationExecutor(
            @Value("${knowledge.migration.worker-count:2}") int workerCount,
            @Value("${knowledge.migration.queue-capacity:100}") int queueCapacity) {
        int workers = Math.max(1, Math.min(workerCount, 32));
        int capacity = Math.max(1, queueCapacity);
        return new ThreadPoolExecutor(workers, workers, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(capacity), runnable -> {
                Thread thread = new Thread(runnable, "knowledge-migration-worker");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    }
}
