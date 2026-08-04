package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.admin.entity.BotChannelConfig;
import com.feisheng.bot.admin.entity.BotConversation;
import com.feisheng.bot.admin.mapper.BotChannelConfigMapper;
import com.feisheng.bot.gateway.client.DingTalkClient;
import com.feisheng.bot.gateway.client.WeChatWorkClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;

@Service
public class ChannelReplyDispatcher {
    private final DingTalkClient dingTalkClient;
    private final WeChatWorkClient weChatWorkClient;
    private final BotChannelConfigMapper channelConfigMapper;
    private final ObjectMapper objectMapper;
    private final String configuredDingTalkClientId;
    private final String configuredDingTalkClientSecret;

    public ChannelReplyDispatcher(
            DingTalkClient dingTalkClient,
            WeChatWorkClient weChatWorkClient,
            BotChannelConfigMapper channelConfigMapper,
            ObjectMapper objectMapper,
            @Value("${dingtalk.stream.client-id:}") String dingTalkClientId,
            @Value("${dingtalk.stream.client-secret:}") String dingTalkClientSecret) {
        this.dingTalkClient = dingTalkClient;
        this.weChatWorkClient = weChatWorkClient;
        this.channelConfigMapper = channelConfigMapper;
        this.objectMapper = objectMapper;
        this.configuredDingTalkClientId = dingTalkClientId;
        this.configuredDingTalkClientSecret = dingTalkClientSecret;
    }

    public DispatchResult dispatch(BotConversation conversation, String content) {
        String channel = normalize(conversation.getChannelType());
        if (channel.equals("web") || channel.equals("playground")
                || channel.equals("evaluation")) {
            return DispatchResult.stored(channel);
        }
        try {
            if (channel.equals("wechat") || channel.equals("wecom")
                    || channel.equals("wechat_work")) {
                return dispatchWeChat(conversation, content, channel);
            }
            if (channel.startsWith("dingtalk")) {
                return dispatchDingTalk(conversation, content, channel);
            }
            return DispatchResult.failed(channel, "渠道暂不支持主动发送，回复已保存到会话");
        } catch (Exception e) {
            return DispatchResult.failed(channel, safeError(e));
        }
    }

    private DispatchResult dispatchWeChat(BotConversation conversation, String content,
                                           String channel) {
        Map<String, Object> config = latestConfig("wechat");
        String corpId = firstText(config.get("corpId"));
        String corpSecret = firstText(config.get("corpSecret"));
        String agentId = firstText(config.get("agentId"));
        if (!hasText(corpId) || !hasText(corpSecret) || !hasText(agentId)) {
            boolean sent = weChatWorkClient.sendMessage(
                conversation.getChannelUserId(), content);
            return sent ? DispatchResult.sent(channel)
                : DispatchResult.failed(channel, "企业微信接口未确认发送成功");
        }
        try {
            boolean sent = weChatWorkClient.sendMessage(corpId, corpSecret,
                Long.parseLong(agentId), conversation.getChannelUserId(), content);
            return sent ? DispatchResult.sent(channel)
                : DispatchResult.failed(channel, "企业微信接口未确认发送成功");
        } catch (NumberFormatException e) {
            return DispatchResult.failed(channel, "企业微信 Agent ID 必须是数字");
        }
    }

    private DispatchResult dispatchDingTalk(BotConversation conversation, String content,
                                            String channel) {
        Map<String, Object> config = latestConfig("dingtalk");
        String appKey = firstText(config.get("clientId"), config.get("appKey"),
            configuredDingTalkClientId);
        String appSecret = firstText(config.get("clientSecret"), config.get("appSecret"),
            configuredDingTalkClientSecret);
        String robotCode = firstText(config.get("robotCode"), appKey);
        if (!hasText(appKey) || !hasText(appSecret) || !hasText(robotCode)) {
            return DispatchResult.failed(channel, "钉钉主动发送配置不完整");
        }
        boolean sent = dingTalkClient.sendRobotMessage(
            appKey, appSecret, robotCode, conversation.getChannelUserId(), content);
        return sent ? DispatchResult.sent(channel)
            : DispatchResult.failed(channel, "钉钉接口未确认发送成功");
    }

    private Map<String, Object> latestConfig(String channelType) {
        BotChannelConfig config = channelConfigMapper.selectOne(
            new LambdaQueryWrapper<BotChannelConfig>()
                .eq(BotChannelConfig::getChannelType, channelType)
                .eq(BotChannelConfig::getStatus, 1)
                .orderByDesc(BotChannelConfig::getId)
                .last("LIMIT 1"));
        if (config == null || !hasText(config.getConfigJson())) return Collections.emptyMap();
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

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private String safeError(Exception e) {
        String message = e.getMessage();
        return hasText(message) && message.length() <= 300
            ? message : "渠道发送失败";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record DispatchResult(boolean delivered, String status,
                                 String channel, String error) {
        static DispatchResult stored(String channel) {
            return new DispatchResult(true, "STORED", channel, null);
        }

        static DispatchResult sent(String channel) {
            return new DispatchResult(true, "SENT", channel, null);
        }

        static DispatchResult failed(String channel, String error) {
            return new DispatchResult(false, "FAILED", channel, error);
        }
    }
}
