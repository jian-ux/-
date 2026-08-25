package com.feisheng.bot.core.service;

import com.feisheng.bot.knowledge.service.KnowledgeImageService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReplyAttachmentServiceTest {

    @Test
    void returnsDistinctImageDocumentsWithinConfiguredLimit() {
        KnowledgeImageService imageService = mock(KnowledgeImageService.class);
        when(imageService.attachment(10L, "产品图"))
            .thenReturn(Optional.of(new KnowledgeImageService.ImageAttachment(
                "image", 10L, "产品图", "/signed/10")));
        when(imageService.attachment(11L, "流程图"))
            .thenReturn(Optional.of(new KnowledgeImageService.ImageAttachment(
                "image", 11L, "流程图", "/signed/11")));
        ReplyAttachmentService service = new ReplyAttachmentService(imageService, 2);
        List<Map<String, Object>> citations = List.of(
            Map.of("sourceType", "image", "documentId", 10L, "title", "产品图"),
            Map.of("sourceType", "image", "documentId", 10L, "title", "产品图"),
            Map.of("sourceType", "document", "documentId", 99L, "title", "说明书"),
            Map.of("sourceType", "image", "documentId", 11L, "title", "流程图"),
            Map.of("sourceType", "image", "documentId", 12L, "title", "第三张图"));

        List<KnowledgeImageService.ImageAttachment> result =
            service.fromCitations(citations, true);

        assertEquals(List.of(10L, 11L), result.stream().map(
            KnowledgeImageService.ImageAttachment::documentId).toList());
    }

    @Test
    void neverAttachesImagesToUnansweredResponse() {
        KnowledgeImageService imageService = mock(KnowledgeImageService.class);
        ReplyAttachmentService service = new ReplyAttachmentService(imageService, 3);

        assertTrue(service.fromCitations(List.of(
            Map.of("sourceType", "image", "documentId", 10L)), false).isEmpty());
        verifyNoInteractions(imageService);
    }

    @Test
    void returnsFixedKnowledgeImageByTitle() {
        KnowledgeImageService imageService = mock(KnowledgeImageService.class);
        when(imageService.attachmentByTitle("点签产品版本功能.png"))
            .thenReturn(Optional.of(new KnowledgeImageService.ImageAttachment(
                "image", 42L, "点签产品版本功能.png", "/signed/42")));
        ReplyAttachmentService service = new ReplyAttachmentService(imageService, 3);

        List<KnowledgeImageService.ImageAttachment> result =
            service.fromKnowledgeImageTitle("点签产品版本功能.png");

        assertEquals(List.of(42L), result.stream().map(
            KnowledgeImageService.ImageAttachment::documentId).toList());
    }
}
