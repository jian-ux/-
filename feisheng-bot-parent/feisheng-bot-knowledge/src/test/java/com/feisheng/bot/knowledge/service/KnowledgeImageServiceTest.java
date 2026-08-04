package com.feisheng.bot.knowledge.service;

import com.feisheng.bot.knowledge.entity.BotKnowledgeDocument;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeDocumentMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeImageServiceTest {

    @Test
    void createsSignedAttachmentAndLoadsOnlyAvailableKnowledgeImage() throws Exception {
        BotKnowledgeDocumentMapper mapper = mock(BotKnowledgeDocumentMapper.class);
        MinioStorageService storage = mock(MinioStorageService.class);
        BotKnowledgeDocument document = imageDocument();
        when(mapper.selectById(42L)).thenReturn(document);
        when(storage.download("product.png"))
            .thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));
        KnowledgeImageService service = new KnowledgeImageService(
            mapper, storage, "a-long-test-signing-secret", 3600, 1024, "https://bot.example.com/");

        KnowledgeImageService.ImageAttachment attachment = service.attachment(42L, "fallback")
            .orElseThrow();
        URI uri = URI.create(attachment.url());
        long expires = queryLong(uri.getQuery(), "expires");
        String signature = query(uri.getQuery(), "signature");

        assertEquals("image", attachment.type());
        assertEquals("https://bot.example.com", uri.getScheme() + "://" + uri.getAuthority());
        assertTrue(service.verify(42L, expires, signature));
        assertFalse(service.verify(43L, expires, signature));

        KnowledgeImageService.ImageContent content = service.load(42L);
        assertArrayEquals(new byte[] {1, 2, 3}, content.bytes());
        assertEquals("image/png", content.contentType());
        assertEquals("点签产品图.png", content.fileName());
    }

    @Test
    void rejectsDocumentAndChatImages() {
        BotKnowledgeDocumentMapper mapper = mock(BotKnowledgeDocumentMapper.class);
        MinioStorageService storage = mock(MinioStorageService.class);
        BotKnowledgeDocument document = imageDocument();
        document.setSourceScope("CHAT");
        when(mapper.selectById(42L)).thenReturn(document);
        KnowledgeImageService service = new KnowledgeImageService(
            mapper, storage, "test-secret", 3600, 1024, "");

        assertTrue(service.attachment(42L, "fallback").isEmpty());

        document.setSourceScope("KNOWLEDGE");
        document.setMediaType("DOCUMENT");
        assertTrue(service.attachment(42L, "fallback").isEmpty());
    }

    private static BotKnowledgeDocument imageDocument() {
        BotKnowledgeDocument document = new BotKnowledgeDocument();
        document.setId(42L);
        document.setTitle("点签产品图");
        document.setFileName("点签产品图.png");
        document.setFileType("png");
        document.setMediaType("IMAGE");
        document.setSourceScope("KNOWLEDGE");
        document.setStatus(2);
        document.setObjectKey("product.png");
        document.setFileSize(3L);
        return document;
    }

    private static long queryLong(String query, String name) {
        return Long.parseLong(query(query, name));
    }

    private static String query(String query, String name) {
        return Arrays.stream(query.split("&"))
            .map(value -> value.split("=", 2))
            .filter(value -> value.length == 2 && name.equals(value[0]))
            .map(value -> value[1])
            .findFirst()
            .orElseThrow();
    }
}
