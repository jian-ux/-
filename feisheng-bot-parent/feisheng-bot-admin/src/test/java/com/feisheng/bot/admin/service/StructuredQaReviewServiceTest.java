package com.feisheng.bot.admin.service;

import com.feisheng.bot.admin.entity.BotKnowledgeChunk;
import com.feisheng.bot.admin.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.common.util.StructuredQaUtil;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StructuredQaReviewServiceTest {
    @Test
    void enablesWholeApprovedGroupAndRestoresCompleteAnswer() {
        BotKnowledgeChunkMapper mapper = mock(BotKnowledgeChunkMapper.class);
        String completeAnswer = "第一步。\n第二步。";
        BotKnowledgeChunk first = qa(1L, 10L, 0, "如何开票？", completeAnswer, 1, 0);
        BotKnowledgeChunk second = qa(2L, 10L, 1, "如何开票？", completeAnswer, 1, 0);
        List<BotKnowledgeChunk> chunks = new ArrayList<>(List.of(first, second));
        when(mapper.selectById(1L)).thenReturn(first);
        when(mapper.selectList(any())).thenReturn(chunks);
        StructuredQaReviewService service = new StructuredQaReviewService(mapper);

        StructuredQaReviewService.UpdateResult result =
            service.updateDirectAnswer(1L, true, 2);

        assertEquals(2, result.updatedChunks());
        assertEquals(2, result.version());
        assertEquals(completeAnswer, first.getQaAnswer());
        assertEquals(first.getQaAnswer(), second.getQaAnswer());
        assertEquals(1, first.getDirectAnswerEnabled());
        assertEquals(2, second.getQaVersion());
        verify(mapper).updateById(first);
        verify(mapper).updateById(second);
    }

    @Test
    void rejectsDirectAnswerUntilEveryChunkIsApproved() {
        BotKnowledgeChunkMapper mapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeChunk approved = qa(1L, 10L, 0, "如何开票？", "完整答案", 1, 0);
        BotKnowledgeChunk pending = qa(2L, 10L, 1, "如何开票？", "完整答案", 1, 0);
        pending.setStatus("PENDING");
        when(mapper.selectById(1L)).thenReturn(approved);
        when(mapper.selectList(any())).thenReturn(List.of(approved, pending));
        StructuredQaReviewService service = new StructuredQaReviewService(mapper);

        StructuredQaReviewService.ReviewException error = assertThrows(
            StructuredQaReviewService.ReviewException.class,
            () -> service.updateDirectAnswer(1L, true, 1));

        assertEquals(409, error.status());
    }

    @Test
    void rejectsDifferentAnswerAtSameActiveVersion() {
        BotKnowledgeChunkMapper mapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeChunk target = qa(1L, 10L, 0, "如何开票？", "新答案", 1, 0);
        BotKnowledgeChunk active = qa(2L, 11L, 0, "如何开票？", "旧答案", 1, 1);
        when(mapper.selectById(1L)).thenReturn(target);
        when(mapper.selectList(any())).thenReturn(List.of(target, active));
        StructuredQaReviewService service = new StructuredQaReviewService(mapper);

        StructuredQaReviewService.ReviewException error = assertThrows(
            StructuredQaReviewService.ReviewException.class,
            () -> service.updateDirectAnswer(1L, true, 1));

        assertEquals(409, error.status());
    }

    @Test
    void allowsReviewedHigherVersionToSupersedeOldAnswer() {
        BotKnowledgeChunkMapper mapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeChunk target = qa(1L, 10L, 0, "如何开票？", "新答案", 2, 0);
        BotKnowledgeChunk active = qa(2L, 11L, 0, "如何开票？", "旧答案", 1, 1);
        when(mapper.selectById(1L)).thenReturn(target);
        when(mapper.selectList(any())).thenReturn(List.of(target, active));
        StructuredQaReviewService service = new StructuredQaReviewService(mapper);

        StructuredQaReviewService.UpdateResult result =
            service.updateDirectAnswer(1L, true, 2);

        assertEquals(2, result.version());
        assertEquals(1, target.getDirectAnswerEnabled());
    }

    private BotKnowledgeChunk qa(Long id, Long documentId, int chunkIndex,
                                 String question, String answer, int version, int direct) {
        BotKnowledgeChunk chunk = new BotKnowledgeChunk();
        chunk.setId(id);
        chunk.setDocumentId(documentId);
        chunk.setChunkIndex(chunkIndex);
        chunk.setContent(question + "\n" + answer);
        chunk.setContentType("QA");
        chunk.setQaQuestion(question);
        chunk.setQaAnswer(answer);
        chunk.setQaKey(StructuredQaUtil.canonicalKey(question));
        chunk.setQaGroupKey(StructuredQaUtil.sourceGroupKey(question, answer));
        chunk.setQaVersion(version);
        chunk.setDirectAnswerEnabled(direct);
        chunk.setStatus("APPROVED");
        return chunk;
    }
}
