package com.feisheng.bot.core.service;

import com.feisheng.bot.knowledge.service.KnowledgeImageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ReplyAttachmentService {
    private static final Logger log = LoggerFactory.getLogger(ReplyAttachmentService.class);

    private final KnowledgeImageService imageService;
    private final int maxImages;

    public ReplyAttachmentService(
            KnowledgeImageService imageService,
            @Value("${knowledge.images.max-per-reply:3}") int maxImages) {
        this.imageService = imageService;
        this.maxImages = Math.max(0, maxImages);
    }

    public List<KnowledgeImageService.ImageAttachment> fromCitations(
            List<Map<String, Object>> citations, boolean answered) {
        if (!answered || maxImages == 0 || citations == null || citations.isEmpty()) {
            return List.of();
        }

        List<KnowledgeImageService.ImageAttachment> attachments = new ArrayList<>();
        Set<Long> seenDocuments = new LinkedHashSet<>();
        for (Map<String, Object> citation : citations) {
            if (!"image".equalsIgnoreCase(text(citation.get("sourceType")))) continue;
            Long documentId = longValue(citation.get("documentId"));
            if (documentId == null || !seenDocuments.add(documentId)) continue;
            try {
                imageService.attachment(documentId, text(citation.get("title")))
                    .ifPresent(attachments::add);
            } catch (RuntimeException e) {
                log.warn("Could not prepare knowledge image attachment {}: {}",
                    documentId, e.getMessage());
            }
            if (attachments.size() >= maxImages) break;
        }
        return List.copyOf(attachments);
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
