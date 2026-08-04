package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.admin.entity.BotKnowledgeChunk;
import com.feisheng.bot.admin.mapper.BotKnowledgeChunkMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class KnowledgeChunkPersistenceService {
    private final BotKnowledgeChunkMapper chunkMapper;

    public KnowledgeChunkPersistenceService(BotKnowledgeChunkMapper chunkMapper) {
        this.chunkMapper = chunkMapper;
    }

    @Transactional
    public void replaceDocumentChunks(Long documentId, List<BotKnowledgeChunk> chunks) {
        chunkMapper.delete(new LambdaQueryWrapper<BotKnowledgeChunk>()
            .eq(BotKnowledgeChunk::getDocumentId, documentId));
        for (BotKnowledgeChunk chunk : chunks) chunkMapper.insert(chunk);
    }
}
