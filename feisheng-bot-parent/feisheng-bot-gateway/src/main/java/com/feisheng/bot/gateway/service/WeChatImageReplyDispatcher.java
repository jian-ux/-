package com.feisheng.bot.gateway.service;

import com.feisheng.bot.gateway.client.WeChatWorkClient;
import com.feisheng.bot.gateway.util.ReplyAttachmentUtils;
import com.feisheng.bot.knowledge.service.KnowledgeImageService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
public class WeChatImageReplyDispatcher {
    private static final Logger log = LoggerFactory.getLogger(WeChatImageReplyDispatcher.class);

    private final WeChatWorkClient client;
    private final KnowledgeImageService imageService;
    private final Executor executor;
    private final ThreadPoolExecutor managedExecutor;

    @Autowired
    public WeChatImageReplyDispatcher(WeChatWorkClient client,
                                      KnowledgeImageService imageService) {
        this(client, imageService, new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(100),
            runnable -> {
                Thread thread = new Thread(runnable, "wecom-image-reply");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()));
    }

    WeChatImageReplyDispatcher(WeChatWorkClient client,
                               KnowledgeImageService imageService,
                               Executor executor) {
        this.client = client;
        this.imageService = imageService;
        this.executor = executor;
        this.managedExecutor = executor instanceof ThreadPoolExecutor pool ? pool : null;
    }

    public void dispatch(Map<String, Object> result, String userId) {
        List<ReplyAttachmentUtils.ImageAttachment> attachments =
            ReplyAttachmentUtils.images(result);
        if (attachments.isEmpty() || userId == null || userId.isBlank()) return;
        try {
            executor.execute(() -> send(attachments, userId));
        } catch (RuntimeException e) {
            log.warn("WeCom image reply queue is full: {}", e.getMessage());
        }
    }

    private void send(List<ReplyAttachmentUtils.ImageAttachment> attachments, String userId) {
        for (ReplyAttachmentUtils.ImageAttachment attachment : attachments) {
            try {
                KnowledgeImageService.ImageContent image = imageService.load(attachment.documentId());
                boolean sent = client.sendImage(userId, image.bytes(),
                    image.fileName(), image.contentType());
                if (!sent) {
                    log.warn("WeCom did not confirm image delivery for document {}",
                        attachment.documentId());
                }
            } catch (RuntimeException e) {
                log.warn("Could not send WeCom knowledge image {}: {}",
                    attachment.documentId(), e.getMessage());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        if (managedExecutor == null) return;
        managedExecutor.shutdown();
        try {
            if (!managedExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                managedExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            managedExecutor.shutdownNow();
        }
    }
}
