package com.feisheng.bot.core.service;

import com.feisheng.bot.core.entity.BotMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextCandidateSelectorTest {

    @Test
    void prioritizesTheActiveTaskBeforeRecentMessagesAndCustomerMemory() {
        ConversationStateService.Snapshot activeState = new ConversationStateService.Snapshot(
                ConversationStateService.Status.ACTIVE, "PRODUCT_USAGE", Map.of("product", "点签"),
                List.of(), "点签的使用教程", null, 0, 3, 1L);
        BotMessage recent = message(12L, 7L, "user", "上一轮的提问", new Date());
        CustomerContextSnapshot snapshot = new CustomerContextSnapshot(null, null, "", null,
                List.of(new CustomerContextSnapshot.ContextRecord(
                        "memory:role", "memory_fact", "客户是管理员", 1L, null,
                        "web", "customer-1", 0.9D, null, null, null, "long_term_memory")));

        List<ContextCandidate> candidates = new ContextCandidateSelector().select(
                "web", "customer-1", 7L, "有没有视频的？", activeState,
                List.of(recent), snapshot, 3);

        assertEquals("task:active", candidates.get(0).contextId());
        assertEquals("active_task", candidates.get(0).sourceType());
        assertEquals("点签的使用教程", candidates.get(0).content());
        assertEquals("message:12", candidates.get(1).contextId());
        assertEquals("memory:role", candidates.get(2).contextId());
    }

    @Test
    void selectsBoundedTraceableCandidatesWithoutSemanticFollowUpInference() {
        BotMessage newest = message(12L, 7L, "user", "最新问题", Date.from(Instant.parse("2026-09-01T10:00:00Z")));
        BotMessage duplicate = message(12L, 7L, "user", "最新问题", Date.from(Instant.parse("2026-09-01T10:00:00Z")));
        CustomerContextSnapshot snapshot = new CustomerContextSnapshot(
                null, null, "历史片段", null,
                List.of(new CustomerContextSnapshot.ContextRecord(
                        "memory:active", "memory_fact", "客户已确认管理员身份", 77L, 9L,
                        "web", "customer-1", 0.9D, null,
                        Date.from(Instant.parse("2026-09-01T09:00:00Z")), null, "long_term_memory"),
                        new CustomerContextSnapshot.ContextRecord(
                                "memory:expired", "memory_fact", "过期记录", 77L, 10L,
                                "web", "customer-1", 0.9D, null,
                                Date.from(Instant.parse("2026-08-01T09:00:00Z")),
                                Date.from(Instant.parse("2026-08-02T09:00:00Z")), "long_term_memory")));

        List<ContextCandidate> candidates = new ContextCandidateSelector().select(
                "web", "customer-1", 7L, "有没有视频的？", null,
                List.of(newest, duplicate), snapshot, 2);

        assertEquals(2, candidates.size());
        assertEquals("message:12", candidates.get(0).contextId());
        assertEquals("customer-1", candidates.get(0).channelUserId());
        assertEquals(7L, candidates.get(0).sessionId());
        assertEquals(12L, candidates.get(0).messageId());
        assertEquals("memory:active", candidates.get(1).contextId());
        assertFalse(candidates.stream().anyMatch(candidate -> "memory:expired".equals(candidate.contextId())));
        assertTrue(candidates.stream().allMatch(candidate -> candidate.reason() != null && !candidate.reason().isBlank()));
    }

    @Test
    void excludesPlaygroundAndCustomerMismatches() {
        CustomerContextSnapshot snapshot = new CustomerContextSnapshot(
                null, null, "", null,
                List.of(new CustomerContextSnapshot.ContextRecord(
                        "memory:other", "memory_fact", "其他客户", 1L, 2L,
                        "web", "other-customer", 0.9D, null, new Date(), null, "long_term_memory")));

        assertTrue(new ContextCandidateSelector().select(
                "playground", "customer-1", 1L, "原始问题", null, List.of(), snapshot, 8).isEmpty());
        assertTrue(new ContextCandidateSelector().select(
                "web", "customer-1", 1L, "原始问题", null, List.of(), snapshot, 8).isEmpty());
    }

    @Test
    void candidateDoesNotExposeMutableTimestamps() {
        ContextCandidate candidate = new ContextCandidate("memory:role", "memory_fact", "管理员", null,
                null, "web", "customer-1", 0.9D, new Date(100L), new Date(200L), "long_term_memory");

        candidate.createdAt().setTime(1L);
        candidate.expiresAt().setTime(2L);

        assertEquals(100L, candidate.createdAt().getTime());
        assertEquals(200L, candidate.expiresAt().getTime());
    }

    @Test
    void rejectsRecentMessagesWithoutAnExactConversationMatch() {
        BotMessage missingConversation = message(21L, null, "user", "来源不明", new Date());
        BotMessage anotherConversation = message(22L, 99L, "user", "其他会话", new Date());

        List<ContextCandidate> candidates = new ContextCandidateSelector().select(
                "web", "customer-1", 7L, "当前问题", null,
                List.of(missingConversation, anotherConversation), CustomerContextSnapshot.empty(), 8);

        assertTrue(candidates.isEmpty());
    }

    @Test
    void snapshotContextRecordDoesNotExposeMutableTimestamps() {
        CustomerContextSnapshot.ContextRecord record = new CustomerContextSnapshot.ContextRecord(
                "memory:role", "memory_fact", "管理员", 1L, null,
                "web", "customer-1", 0.9D, null, new Date(100L), new Date(200L), "long_term_memory");

        record.createdAt().setTime(1L);
        record.expiresAt().setTime(2L);

        assertEquals(100L, record.createdAt().getTime());
        assertEquals(200L, record.expiresAt().getTime());
    }

    private BotMessage message(Long id, Long conversationId, String role, String content, Date createdAt) {
        BotMessage message = new BotMessage();
        message.setId(id);
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setCreateTime(createdAt);
        return message;
    }
}
