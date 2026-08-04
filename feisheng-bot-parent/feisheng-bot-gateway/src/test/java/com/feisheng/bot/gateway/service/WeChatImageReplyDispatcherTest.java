package com.feisheng.bot.gateway.service;

import com.feisheng.bot.gateway.client.WeChatWorkClient;
import com.feisheng.bot.knowledge.service.KnowledgeImageService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WeChatImageReplyDispatcherTest {

    @Test
    void loadsAndSendsEachImageOffTheCallbackPath() {
        WeChatWorkClient client = mock(WeChatWorkClient.class);
        KnowledgeImageService imageService = mock(KnowledgeImageService.class);
        when(imageService.load(42L)).thenReturn(new KnowledgeImageService.ImageContent(
            new byte[] {1, 2, 3}, "image/png", "product.png"));
        when(client.sendImage(eq("user-1"), any(byte[].class),
            eq("product.png"), eq("image/png"))).thenReturn(true);
        WeChatImageReplyDispatcher dispatcher = new WeChatImageReplyDispatcher(
            client, imageService, Runnable::run);

        dispatcher.dispatch(Map.of("attachments", List.of(Map.of(
            "type", "image", "documentId", 42L, "title", "产品图",
            "url", "/api/public/knowledge-images/42"))), "user-1");

        verify(imageService).load(42L);
        verify(client).sendImage(eq("user-1"), any(byte[].class),
            eq("product.png"), eq("image/png"));
    }

    @Test
    void ignoresTextOnlyReplies() {
        WeChatWorkClient client = mock(WeChatWorkClient.class);
        KnowledgeImageService imageService = mock(KnowledgeImageService.class);
        WeChatImageReplyDispatcher dispatcher = new WeChatImageReplyDispatcher(
            client, imageService, Runnable::run);

        dispatcher.dispatch(Map.of("reply", "text only"), "user-1");

        verifyNoInteractions(client, imageService);
    }
}
