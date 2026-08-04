package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.admin.entity.BotTicket;
import com.feisheng.bot.admin.mapper.BotTicketMapper;
import com.feisheng.bot.core.entity.BotConversation;
import com.feisheng.bot.core.entity.BotMessage;
import com.feisheng.bot.core.mapper.BotConversationMapper;
import com.feisheng.bot.core.mapper.BotMessageMapper;
import com.feisheng.bot.core.service.HandoffCoordinator;
import com.feisheng.bot.core.service.SensitiveDataService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;

@Service
public class TicketHandoffCoordinator implements HandoffCoordinator {
    private static final Set<String> ACTIVE_TICKET_STATUSES = Set.of("pending", "processing");
    private static final int MAX_SUMMARY_MESSAGES = 10;
    private static final int MAX_MESSAGE_CHARS = 300;
    private static final int MAX_SUMMARY_CHARS = 3000;

    private final BotConversationMapper conversationMapper;
    private final BotMessageMapper messageMapper;
    private final BotTicketMapper ticketMapper;
    private final SensitiveDataService sensitiveDataService;

    public TicketHandoffCoordinator(BotConversationMapper conversationMapper,
                                    BotMessageMapper messageMapper,
                                    BotTicketMapper ticketMapper,
                                    SensitiveDataService sensitiveDataService) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.ticketMapper = ticketMapper;
        this.sensitiveDataService = sensitiveDataService;
    }

    @Override
    @Transactional
    public HandoffResult handoff(Long conversationId, String reason, String requestedPriority) {
        if (conversationId == null) return HandoffResult.failed("会话 ID 不能为空");

        BotConversation conversation = conversationMapper.selectOne(
            new LambdaQueryWrapper<BotConversation>()
                .eq(BotConversation::getId, conversationId)
                .last("FOR UPDATE"));
        if (conversation == null) return HandoffResult.failed("会话不存在");

        String priority = normalizePriority(requestedPriority);
        updateConversation(conversation, priority);

        BotTicket existing = ticketMapper.selectOne(new LambdaQueryWrapper<BotTicket>()
            .eq(BotTicket::getConversationId, conversationId)
            .in(BotTicket::getStatus, ACTIVE_TICKET_STATUSES)
            .orderByDesc(BotTicket::getCreateTime)
            .last("LIMIT 1"));
        if (existing != null) {
            updateExistingTicket(existing, conversation, priority);
            String summary = hasText(existing.getDescription())
                ? existing.getDescription() : buildSummary(conversation, reason);
            return new HandoffResult(true, existing.getId(), false, summary, null);
        }

        String summary = buildSummary(conversation, reason);
        BotTicket ticket = new BotTicket();
        ticket.setConversationId(conversationId);
        ticket.setTitle(buildTitle(conversation));
        ticket.setDescription(summary);
        ticket.setStatus("pending");
        ticket.setPriority(priority);
        ticket.setSlaDeadline(conversation.getSlaDeadline());
        ticketMapper.insert(ticket);
        return new HandoffResult(true, ticket.getId(), true, summary, null);
    }

    @Override
    @Transactional
    public void recordUserMessage(Long conversationId, String content) {
        if (conversationId == null || !hasText(content)) return;
        BotTicket ticket = ticketMapper.selectOne(new LambdaQueryWrapper<BotTicket>()
            .eq(BotTicket::getConversationId, conversationId)
            .eq(BotTicket::getStatus, "pending")
            .orderByDesc(BotTicket::getCreateTime)
            .last("LIMIT 1"));
        if (ticket == null) return;

        String supplement = "\n用户补充：" + truncate(
            sensitiveDataService.redact(content.trim()).text(), MAX_MESSAGE_CHARS);
        String existing = hasText(ticket.getDescription()) ? ticket.getDescription() : "转人工原因：待确认";
        int prefixLimit = Math.max(0, MAX_SUMMARY_CHARS - supplement.length());
        String prefix = existing.length() <= prefixLimit
            ? existing : truncate(existing, prefixLimit);
        ticket.setDescription(prefix + supplement);
        ticketMapper.updateById(ticket);
    }

    @Override
    @Transactional
    public boolean cancelWaitingHandoff(Long conversationId, String reason) {
        if (conversationId == null) return false;
        BotTicket ticket = ticketMapper.selectOne(new LambdaQueryWrapper<BotTicket>()
            .eq(BotTicket::getConversationId, conversationId)
            .eq(BotTicket::getStatus, "pending")
            .orderByDesc(BotTicket::getCreateTime)
            .last("LIMIT 1 FOR UPDATE"));
        if (ticket == null) return false;

        Date now = new Date();
        ticket.setStatus("closed");
        ticket.setResolvedTime(now);
        ticket.setResolution(sensitiveDataService.redact(
            hasText(reason) ? reason : "用户取消等待人工客服").text());
        ticketMapper.updateById(ticket);

        BotConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation != null) {
            conversation.setStatus("active");
            conversation.setHandoffStatus("CANCELLED");
            conversation.setAssignedAgentId(null);
            conversation.setAssignedAgentName(null);
            conversation.setResolvedTime(now);
            conversationMapper.updateById(conversation);
        }
        return true;
    }

    private void updateConversation(BotConversation conversation, String priority) {
        String previousPriority = normalizePriority(conversation.getPriority());
        String effectivePriority = higherPriority(previousPriority, priority);
        conversation.setStatus("transferred");
        conversation.setPriority(effectivePriority);
        if (!"PROCESSING".equals(conversation.getHandoffStatus())) {
            conversation.setHandoffStatus("WAITING");
        }
        if (conversation.getHandoffTime() == null) conversation.setHandoffTime(new Date());
        if (conversation.getSlaDeadline() == null
                || priorityRank(effectivePriority) < priorityRank(previousPriority)) {
            conversation.setSlaDeadline(new Date(System.currentTimeMillis()
                + slaMillis(effectivePriority)));
        }
        conversationMapper.updateById(conversation);
    }

    private void updateExistingTicket(BotTicket ticket, BotConversation conversation,
                                      String requestedPriority) {
        String effectivePriority = higherPriority(ticket.getPriority(), requestedPriority);
        boolean changed = !effectivePriority.equals(ticket.getPriority());
        if (changed) ticket.setPriority(effectivePriority);
        if (ticket.getSlaDeadline() == null
                || (conversation.getSlaDeadline() != null
                    && ticket.getSlaDeadline().after(conversation.getSlaDeadline()))) {
            ticket.setSlaDeadline(conversation.getSlaDeadline());
            changed = true;
        }
        if (changed) ticketMapper.updateById(ticket);
    }

    private String buildTitle(BotConversation conversation) {
        String title = hasText(conversation.getTitle()) ? conversation.getTitle().trim() : "客户会话";
        String redacted = sensitiveDataService.redact(title).text();
        return truncate("人工处理：" + redacted, 200);
    }

    private String buildSummary(BotConversation conversation, String reason) {
        List<BotMessage> messages = messageMapper.selectList(
            new LambdaQueryWrapper<BotMessage>()
                .eq(BotMessage::getConversationId, conversation.getId())
                .orderByDesc(BotMessage::getCreateTime)
                .orderByDesc(BotMessage::getId)
                .last("LIMIT " + MAX_SUMMARY_MESSAGES));
        List<BotMessage> chronological = messages == null
            ? new ArrayList<>() : new ArrayList<>(messages);
        Collections.reverse(chronological);

        String safeReason = sensitiveDataService.redact(
            hasText(reason) ? reason.trim() : "客户请求人工客服").text();
        StringBuilder summary = new StringBuilder("转人工原因：")
            .append(truncate(safeReason, MAX_MESSAGE_CHARS));
        appendEmotionSummary(summary, conversation);
        summary.append("\n最近对话：");
        if (chronological.isEmpty()) {
            summary.append("\n- 暂无对话消息");
        } else {
            for (BotMessage message : chronological) {
                if (message == null || !hasText(message.getContent())) continue;
                String content = sensitiveDataService.redact(message.getContent().trim()).text();
                summary.append("\n- ").append(roleLabel(message.getRole())).append("：")
                    .append(truncate(content, MAX_MESSAGE_CHARS));
                if (summary.length() >= MAX_SUMMARY_CHARS) break;
            }
        }
        return truncate(summary.toString(), MAX_SUMMARY_CHARS);
    }

    private void appendEmotionSummary(StringBuilder summary, BotConversation conversation) {
        if (!hasText(conversation.getEmotionLabel())) return;
        summary.append("\n情绪状态：")
            .append(emotionLabel(conversation.getEmotionLabel()))
            .append("；风险：").append(emotionRisk(conversation.getEmotionRisk()))
            .append("；趋势：").append(emotionTrend(conversation.getEmotionTrend()))
            .append("；连续负面：")
            .append(conversation.getNegativeStreak() == null ? 0 : conversation.getNegativeStreak())
            .append(" 轮");
    }

    private String emotionLabel(String value) {
        return switch (value) {
            case "ANGER" -> "愤怒";
            case "ANXIETY" -> "焦虑";
            case "URGENCY" -> "催促";
            case "DISAPPOINTMENT" -> "失望";
            case "SATISFACTION" -> "满意";
            default -> "平静";
        };
    }

    private String emotionRisk(String value) {
        if ("HIGH".equals(value)) return "高";
        if ("MEDIUM".equals(value)) return "中";
        return "低";
    }

    private String emotionTrend(String value) {
        if ("WORSENING".equals(value)) return "恶化";
        if ("PERSISTENT".equals(value)) return "持续";
        if ("EASING".equals(value)) return "缓和";
        if ("IMPROVING".equals(value)) return "改善";
        return "稳定";
    }

    private String roleLabel(String role) {
        if ("user".equalsIgnoreCase(role)) return "用户";
        if ("ai".equalsIgnoreCase(role) || "assistant".equalsIgnoreCase(role)) return "客服";
        return "系统";
    }

    private String normalizePriority(String value) {
        if (value == null) return "P1";
        String normalized = value.trim().toUpperCase();
        return Set.of("P0", "P1", "P2", "P3").contains(normalized) ? normalized : "P1";
    }

    private String higherPriority(String current, String requested) {
        if (!hasText(current)) return requested;
        String normalizedCurrent = normalizePriority(current);
        return priorityRank(requested) < priorityRank(normalizedCurrent) ? requested : normalizedCurrent;
    }

    private int priorityRank(String priority) {
        return switch (priority) {
            case "P0" -> 0;
            case "P1" -> 1;
            case "P2" -> 2;
            default -> 3;
        };
    }

    private long slaMillis(String priority) {
        return switch (priority) {
            case "P0" -> 30 * 60_000L;
            case "P1" -> 2 * 60 * 60_000L;
            case "P2" -> 8 * 60 * 60_000L;
            default -> 24 * 60 * 60_000L;
        };
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) return value;
        return value.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
