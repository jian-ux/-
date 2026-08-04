package com.feisheng.bot.admin.service;

import com.feisheng.bot.admin.entity.BotKnowledgeChunk;
import com.feisheng.bot.admin.mapper.BotKnowledgeChunkMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class KnowledgeChunkPersistenceServiceTest {
    @Test
    void deletesOldChunksBeforeInsertingCompleteReplacement() {
        BotKnowledgeChunkMapper mapper = mock(BotKnowledgeChunkMapper.class);
        KnowledgeChunkPersistenceService service = new KnowledgeChunkPersistenceService(mapper);
        BotKnowledgeChunk first = new BotKnowledgeChunk();
        first.setChunkIndex(0);
        BotKnowledgeChunk second = new BotKnowledgeChunk();
        second.setChunkIndex(1);

        service.replaceDocumentChunks(9L, List.of(first, second));

        InOrder order = inOrder(mapper);
        order.verify(mapper).delete(any());
        order.verify(mapper).insert(first);
        order.verify(mapper).insert(second);
    }
}
