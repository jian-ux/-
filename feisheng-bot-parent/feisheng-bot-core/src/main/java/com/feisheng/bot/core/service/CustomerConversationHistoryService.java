package com.feisheng.bot.core.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.core.entity.BotConversation;
import com.feisheng.bot.core.entity.BotMessage;
import com.feisheng.bot.core.mapper.BotConversationMapper;
import com.feisheng.bot.core.mapper.BotMessageMapper;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Reads bounded cross-conversation snippets; it never participates in knowledge retrieval. */
@Service
public class CustomerConversationHistoryService {
    private static final String PLAYGROUND = "playground";
    private static final int MAX_MESSAGES = 8;
    private final BotConversationMapper conversationMapper;
    private final BotMessageMapper messageMapper;
    private final SensitiveDataService sensitiveDataService;

    public CustomerConversationHistoryService(BotConversationMapper conversationMapper,
                                              BotMessageMapper messageMapper,
                                              SensitiveDataService sensitiveDataService) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.sensitiveDataService = sensitiveDataService;
    }

    public String contextFor(String channelType, String channelUserId, Long currentConversationId) {
        if (channelType == null || channelUserId == null || channelType.isBlank()
                || channelUserId.isBlank() || PLAYGROUND.equalsIgnoreCase(channelType.trim())) return "";
        List<BotConversation> conversations = conversationMapper.selectList(new LambdaQueryWrapper<BotConversation>()
            .eq(BotConversation::getChannelType, channelType.trim())
            .eq(BotConversation::getChannelUserId, channelUserId.trim())
            .eq(BotConversation::getDeleted, 0)
            .ne(currentConversationId != null, BotConversation::getId, currentConversationId)
            .orderByDesc(BotConversation::getUpdateTime)
            .last("LIMIT 20"));
        if (conversations == null || conversations.isEmpty()) return "";
        List<Long> ids = conversations.stream().map(BotConversation::getId)
            .filter(Objects::nonNull).toList();
        if (ids.isEmpty()) return "";
        List<BotMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<BotMessage>()
            .in(BotMessage::getConversationId, ids)
            .in(BotMessage::getRole, List.of("user", "ai"))
            .orderByDesc(BotMessage::getCreateTime)
            .orderByDesc(BotMessage::getId)
            .last("LIMIT " + MAX_MESSAGES));
        if (messages == null || messages.isEmpty()) return "";
        String body = messages.stream()
            .filter(Objects::nonNull)
            .filter(message -> "user".equalsIgnoreCase(message.getRole())
                || "ai".equalsIgnoreCase(message.getRole()))
            .sorted(Comparator.comparing(BotMessage::getCreateTime,
                Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(BotMessage::getId, Comparator.nullsLast(Comparator.naturalOrder())))
            .map(message -> {
                String content = sanitize(message.getContent());
                if (content.isBlank()) return null;
                String role = "user".equalsIgnoreCase(message.getRole()) ? "客户" : "智能客服";
                return role + "：" + content;
            })
            .filter(Objects::nonNull)
            .collect(Collectors.joining("\n"));
        return body.isBlank() ? "" : "以下为该客户其他会话中的最近片段，仅用于连续性参考，不是知识库事实：\n" + body;
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) return "";
        String redacted = sensitiveDataService.redact(value).text();
        if (redacted == null) return "";
        return redacted.strip().substring(0, Math.min(redacted.strip().length(), 600));
    }
}
