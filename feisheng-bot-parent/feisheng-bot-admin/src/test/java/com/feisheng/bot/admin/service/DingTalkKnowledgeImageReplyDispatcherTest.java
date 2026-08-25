package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.admin.entity.BotChannelConfig;
import com.feisheng.bot.admin.mapper.BotChannelConfigMapper;
import com.feisheng.bot.gateway.client.DingTalkClient;
import com.feisheng.bot.gateway.service.DingTalkImageReplyDispatcher.ReplyTarget;
import com.feisheng.bot.knowledge.service.KnowledgeImageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DingTalkKnowledgeImageReplyDispatcherTest {
    private static final byte[] IMAGE = {1, 2, 3};
    @Mock private DingTalkClient client;
    @Mock private KnowledgeImageService imageService;
    @Mock private BotChannelConfigMapper channelConfigMapper;

    private DingTalkKnowledgeImageReplyDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new DingTalkKnowledgeImageReplyDispatcher(
            client, imageService, channelConfigMapper, new ObjectMapper(), "", "", "");
    }

    @Test
    void uploadsAndSendsIndependentImageToUser() {
        arrangeImage();

        dispatcher.dispatch(result(), new ReplyTarget("staff-1", "cid-1", "1", ""));

        verify(client).uploadImage(
            "app-key", "app-secret", IMAGE, "product.png", "image/png");
        verify(client).sendImageToUser(
            "app-key", "app-secret", "robot-code", "staff-1", "@lAL-image");
        verify(client, never()).sendImageToGroup(
            any(), any(), any(), any(), any());
    }

    @Test
    void uploadsAndSendsIndependentImageToGroupConversation() {
        arrangeImage();

        dispatcher.dispatch(result(), new ReplyTarget("staff-1", "cid-group", "2", ""));

        verify(client).sendImageToGroup(
            "app-key", "app-secret", "robot-code", "cid-group", "@lAL-image");
        verify(client, never()).sendImageToUser(
            any(), any(), any(), any(), any());
    }

    @Test
    void imageUploadFailureDoesNotBreakTextReplyFlow() {
        when(channelConfigMapper.selectOne(any(Wrapper.class))).thenReturn(config());
        when(imageService.load(42L)).thenReturn(new KnowledgeImageService.ImageContent(
            IMAGE, "image/png", "product.png"));
        when(client.uploadImage(
            "app-key", "app-secret", IMAGE, "product.png", "image/png"))
            .thenThrow(new IllegalStateException("upload failed"));

        assertDoesNotThrow(() -> dispatcher.dispatch(
            result(), new ReplyTarget("staff-1", "cid-1", "1", "")));

        verify(client, never()).sendImageToUser(
            any(), any(), any(), any(), any());
    }

    private void arrangeImage() {
        when(channelConfigMapper.selectOne(any(Wrapper.class))).thenReturn(config());
        when(imageService.load(42L)).thenReturn(new KnowledgeImageService.ImageContent(
            IMAGE, "image/png", "product.png"));
        when(client.uploadImage(
            "app-key", "app-secret", IMAGE, "product.png", "image/png"))
            .thenReturn("@lAL-image");
    }

    private Map<String, Object> result() {
        return Map.of("attachments", List.of(Map.of(
            "type", "image", "documentId", 42L, "title", "产品图", "url", "/image/42")));
    }

    private BotChannelConfig config() {
        BotChannelConfig config = new BotChannelConfig();
        config.setId(5L);
        config.setChannelType("dingtalk");
        config.setStatus(1);
        config.setConfigJson("{\"clientId\":\"app-key\","
            + "\"clientSecret\":\"app-secret\",\"robotCode\":\"robot-code\"}");
        return config;
    }
}
