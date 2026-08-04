package com.feisheng.bot.admin.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.admin.entity.BotKnowledgeDocument;
import com.feisheng.bot.admin.mapper.BotKnowledgeDocumentMapper;
import com.feisheng.bot.knowledge.service.MinioStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

@Component
public class ChatImageCleanupTask {
    private static final Logger log = LoggerFactory.getLogger(ChatImageCleanupTask.class);

    private final BotKnowledgeDocumentMapper documentMapper;
    private final MinioStorageService storageService;

    public ChatImageCleanupTask(BotKnowledgeDocumentMapper documentMapper,
                                MinioStorageService storageService) {
        this.documentMapper = documentMapper;
        this.storageService = storageService;
    }

    @Scheduled(cron = "0 20 * * * ?")
    public void removeExpiredChatImages() {
        List<BotKnowledgeDocument> expired = documentMapper.selectList(
            new LambdaQueryWrapper<BotKnowledgeDocument>()
                .eq(BotKnowledgeDocument::getSourceScope, "CHAT")
                .isNotNull(BotKnowledgeDocument::getExpiresAt)
                .lt(BotKnowledgeDocument::getExpiresAt, new Date())
                .last("LIMIT 100"));
        int deleted = 0;
        for (BotKnowledgeDocument image : expired) {
            try {
                if (image.getObjectKey() != null && !image.getObjectKey().isBlank()) {
                    storageService.delete(image.getObjectKey());
                }
                documentMapper.deleteById(image.getId());
                deleted++;
            } catch (Exception e) {
                log.warn("Could not delete expired chat image {}: {}", image.getId(), e.getMessage());
            }
        }
        if (deleted > 0) log.info("Deleted {} expired chat images", deleted);
    }
}
