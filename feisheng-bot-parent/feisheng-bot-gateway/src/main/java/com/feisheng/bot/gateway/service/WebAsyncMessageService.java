package com.feisheng.bot.gateway.service;

import com.feisheng.bot.gateway.dto.ChannelMessageDTO;
import com.feisheng.bot.gateway.service.impl.ChannelServiceImpl;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs long web-channel questions in the background and exposes bounded task state. */
@Service
public class WebAsyncMessageService {
    private static final Logger log = LoggerFactory.getLogger(WebAsyncMessageService.class);
    private final ChannelServiceImpl channelService;
    private final Executor executor;
    private final ThreadPoolExecutor managedExecutor;
    private final Map<String, AsyncJob> jobs = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final int maxJobs;

    @Autowired
    public WebAsyncMessageService(
            ChannelServiceImpl channelService,
            @Value("${web.async.worker-threads:4}") int workerThreads,
            @Value("${web.async.queue-capacity:100}") int queueCapacity,
            @Value("${web.async.result-ttl-seconds:600}") long resultTtlSeconds,
            @Value("${web.async.max-jobs:1000}") int maxJobs) {
        this(channelService, createExecutor(workerThreads, queueCapacity),
            resultTtlSeconds, maxJobs);
    }

    WebAsyncMessageService(ChannelServiceImpl channelService, Executor executor,
                           long resultTtlSeconds, int maxJobs) {
        this.channelService = channelService;
        this.executor = executor;
        this.managedExecutor = executor instanceof ThreadPoolExecutor pool ? pool : null;
        this.ttlMillis = TimeUnit.SECONDS.toMillis(Math.max(60, resultTtlSeconds));
        this.maxJobs = Math.max(10, maxJobs);
    }

    public Map<String, Object> submit(ChannelMessageDTO dto) {
        validate(dto);
        cleanup();
        if (jobs.size() >= maxJobs) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "异步任务较多，请稍后重试");
        }
        dto.setChannelType("web");
        String requestId = UUID.randomUUID().toString();
        AsyncJob job = new AsyncJob(requestId, System.currentTimeMillis());
        jobs.put(requestId, job);
        try {
            executor.execute(() -> process(job, dto));
        } catch (RuntimeException e) {
            jobs.remove(requestId);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "异步任务队列已满，请稍后重试", e);
        }
        return job.toMap();
    }

    public Map<String, Object> status(String requestId) {
        cleanup();
        AsyncJob job = jobs.get(requestId);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "异步任务不存在或已过期");
        }
        return job.toMap();
    }

    private void process(AsyncJob job, ChannelMessageDTO dto) {
        try {
            Map<String, Object> result = channelService.processMessage(dto);
            if (isFailedResult(result)) {
                log.warn("Web async message completed with an error result, requestId={}",
                    job.requestId);
                job.fail(result);
            } else {
                job.complete(result);
            }
        } catch (Exception e) {
            log.error("Web async message processing failed, requestId={}", job.requestId, e);
            job.fail(null);
        }
    }

    private boolean isFailedResult(Map<String, Object> result) {
        if (result == null) return true;
        return "error".equals(result.get("source"))
            || "error".equals(result.get("answerStatus"))
            || Boolean.FALSE.equals(result.get("success"));
    }

    private void validate(ChannelMessageDTO dto) {
        if (dto == null || !StringUtils.hasText(dto.getChannelUserId())
                || !StringUtils.hasText(dto.getMsgId())
                || !StringUtils.hasText(dto.getContent())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "channelUserId, msgId and content are required");
        }
    }

    private void cleanup() {
        long cutoff = System.currentTimeMillis() - ttlMillis;
        jobs.entrySet().removeIf(entry -> entry.getValue().updatedAt < cutoff);
    }

    private static ThreadPoolExecutor createExecutor(int workers, int capacity) {
        int threads = Math.max(1, workers);
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(Math.max(1, capacity)), runnable -> {
                Thread thread = new Thread(runnable,
                    "web-async-message-" + sequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    public void shutdown() {
        if (managedExecutor != null) managedExecutor.shutdown();
    }

    private static final class AsyncJob {
        private final String requestId;
        private final long submittedAt;
        private volatile long updatedAt;
        private volatile String status = "processing";
        private volatile Map<String, Object> result;

        private AsyncJob(String requestId, long submittedAt) {
            this.requestId = requestId;
            this.submittedAt = submittedAt;
            this.updatedAt = submittedAt;
        }

        private void complete(Map<String, Object> value) {
            result = value == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(value));
            status = "completed";
            updatedAt = System.currentTimeMillis();
        }

        private void fail(Map<String, Object> value) {
            result = value == null
                ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(value));
            status = "failed";
            updatedAt = System.currentTimeMillis();
        }

        private Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("requestId", requestId);
            value.put("status", status);
            value.put("submittedAt", Instant.ofEpochMilli(submittedAt).toString());
            value.put("updatedAt", Instant.ofEpochMilli(updatedAt).toString());
            if ("processing".equals(status)) {
                value.put("message", "问题正在处理中");
            } else if ("completed".equals(status)) {
                value.put("result", result);
            } else {
                value.put("message", "处理失败，请稍后重试");
                if (result != null) value.put("result", result);
            }
            return value;
        }
    }
}
