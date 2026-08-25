package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.admin.entity.BotChannelConfig;
import com.feisheng.bot.admin.mapper.BotChannelConfigMapper;
import com.feisheng.bot.gateway.client.DingTalkClient;
import com.feisheng.bot.gateway.service.DingTalkImageReplyDispatcher;
import com.feisheng.bot.gateway.util.ReplyAttachmentUtils;
import com.feisheng.bot.knowledge.service.KnowledgeImageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class DingTalkKnowledgeImageReplyDispatcher
        implements DingTalkImageReplyDispatcher {
    private static final Logger log = LoggerFactory.getLogger(
        DingTalkKnowledgeImageReplyDispatcher.class);

    private final DingTalkClient client;
    private final KnowledgeImageService imageService;
    private final BotChannelConfigMapper channelConfigMapper;
    private final ObjectMapper objectMapper;
    private final String environmentClientId;
    private final String environmentClientSecret;
    private final String environmentRobotCode;

    public DingTalkKnowledgeImageReplyDispatcher(
            DingTalkClient client,
            KnowledgeImageService imageService,
            BotChannelConfigMapper channelConfigMapper,
            ObjectMapper objectMapper,
            @Value("${dingtalk.stream.client-id:}") String environmentClientId,
            @Value("${dingtalk.stream.client-secret:${dingtalk.app-secret:}}")
            String environmentClientSecret,
            @Value("${dingtalk.robot-code:}") String environmentRobotCode) {
        this.client = client;
        this.imageService = imageService;
        this.channelConfigMapper = channelConfigMapper;
        this.objectMapper = objectMapper;
        this.environmentClientId = environmentClientId;
        this.environmentClientSecret = environmentClientSecret;
        this.environmentRobotCode = environmentRobotCode;
    }

    @Override
    public void dispatch(Map<String, Object> result, ReplyTarget target) {
        List<ReplyAttachmentUtils.ImageAttachment> attachments =
            ReplyAttachmentUtils.images(result);
        if (attachments.isEmpty() || target == null) return;

        Credentials credentials = credentials(target.robotCode());
        if (credentials == null) {
            log.warn("DingTalk image reply skipped because channel credentials are incomplete");
            return;
        }
        if (target.isGroup() && !hasText(target.conversationId())) {
            log.warn("DingTalk group image reply skipped because conversationId is missing");
            return;
        }
        if (!target.isGroup() && !hasText(target.senderStaffId())) {
            log.warn("DingTalk direct image reply skipped because senderStaffId is missing");
            return;
        }

        for (ReplyAttachmentUtils.ImageAttachment attachment : attachments) {
            send(attachment, target, credentials);
        }
    }

    private void send(ReplyAttachmentUtils.ImageAttachment attachment,
                      ReplyTarget target, Credentials credentials) {
        try {
            KnowledgeImageService.ImageContent image =
                imageService.load(attachment.documentId());
            String mediaId = client.uploadImage(
                credentials.appKey(), credentials.appSecret(), image.bytes(),
                image.fileName(), image.contentType());
            boolean sent = target.isGroup()
                ? client.sendImageToGroup(
                    credentials.appKey(), credentials.appSecret(), credentials.robotCode(),
                    target.conversationId(), mediaId)
                : client.sendImageToUser(
                    credentials.appKey(), credentials.appSecret(), credentials.robotCode(),
                    target.senderStaffId(), mediaId);
            if (!sent) {
                log.warn("DingTalk did not confirm image delivery for document {}",
                    attachment.documentId());
            }
        } catch (RuntimeException e) {
            log.warn("Could not send DingTalk knowledge image {}: {}",
                attachment.documentId(), e.getMessage());
        }
    }

    private Credentials credentials(String callbackRobotCode) {
        Map<String, Object> config = latestConfig();
        String appKey = firstText(config.get("clientId"), config.get("appKey"),
            environmentClientId);
        String appSecret = firstText(config.get("clientSecret"), config.get("appSecret"),
            environmentClientSecret);
        String robotCode = firstText(callbackRobotCode, config.get("robotCode"),
            environmentRobotCode, appKey);
        if (!hasText(appKey) || !hasText(appSecret) || !hasText(robotCode)) return null;
        return new Credentials(appKey, appSecret, robotCode);
    }

    private Map<String, Object> latestConfig() {
        BotChannelConfig config = channelConfigMapper.selectOne(
            new LambdaQueryWrapper<BotChannelConfig>()
                .eq(BotChannelConfig::getChannelType, "dingtalk")
                .eq(BotChannelConfig::getStatus, 1)
                .orderByDesc(BotChannelConfig::getId)
                .last("LIMIT 1"));
        if (config == null || !hasText(config.getConfigJson())) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(config.getConfigJson(), new TypeReference<>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private String firstText(Object... values) {
        for (Object value : values) {
            if (value != null && !value.toString().isBlank()) return value.toString().trim();
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record Credentials(String appKey, String appSecret, String robotCode) {}
}
