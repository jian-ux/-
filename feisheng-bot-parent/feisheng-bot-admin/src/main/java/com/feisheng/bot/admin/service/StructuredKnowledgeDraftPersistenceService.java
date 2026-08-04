package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.knowledge.entity.BotKnowledgeSemanticUnit;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeSemanticUnitMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Atomically merges one extraction run into the review queue. */
@Service
public class StructuredKnowledgeDraftPersistenceService {
    private final BotKnowledgeSemanticUnitMapper unitMapper;

    public StructuredKnowledgeDraftPersistenceService(BotKnowledgeSemanticUnitMapper unitMapper) {
        this.unitMapper = unitMapper;
    }

    @Transactional
    public PersistResult replaceDrafts(Long documentId, String sourceHash,
                                       List<BotKnowledgeSemanticUnit> candidates) {
        return replaceDrafts(documentId, sourceHash, candidates, true);
    }

    @Transactional
    public PersistResult replaceDrafts(Long documentId, String sourceHash,
                                       List<BotKnowledgeSemanticUnit> candidates,
                                       boolean retireMissingDrafts) {
        Objects.requireNonNull(documentId, "documentId");
        if (sourceHash == null || sourceHash.isBlank()) {
            throw new IllegalArgumentException("sourceHash must not be blank");
        }

        Map<String, BotKnowledgeSemanticUnit> incoming = new LinkedHashMap<>();
        for (BotKnowledgeSemanticUnit candidate : candidates) {
            validateCandidate(documentId, sourceHash, candidate);
            incoming.putIfAbsent(candidate.getUnitKey(), candidate);
        }

        List<BotKnowledgeSemanticUnit> existing = unitMapper.selectList(
            new LambdaQueryWrapper<BotKnowledgeSemanticUnit>()
                .eq(BotKnowledgeSemanticUnit::getDocumentId, documentId)
                .eq(BotKnowledgeSemanticUnit::getSourceHash, sourceHash));
        if (existing == null) existing = List.of();

        Map<String, BotKnowledgeSemanticUnit> existingByKey = new LinkedHashMap<>();
        for (BotKnowledgeSemanticUnit unit : existing) {
            existingByKey.putIfAbsent(unit.getUnitKey(), unit);
        }

        int inserted = 0;
        int updated = 0;
        int preservedReviewed = 0;
        List<Long> ids = new ArrayList<>();
        for (BotKnowledgeSemanticUnit candidate : incoming.values()) {
            BotKnowledgeSemanticUnit current = existingByKey.get(candidate.getUnitKey());
            if (current == null) {
                unitMapper.insert(candidate);
                inserted++;
                if (candidate.getId() != null) ids.add(candidate.getId());
                continue;
            }
            if (!"DRAFT".equals(current.getStatus())) {
                preservedReviewed++;
                if (current.getId() != null) ids.add(current.getId());
                continue;
            }
            candidate.setId(current.getId());
            candidate.setCreateTime(current.getCreateTime());
            candidate.setStatus("DRAFT");
            unitMapper.updateById(candidate);
            updated++;
            if (candidate.getId() != null) ids.add(candidate.getId());
        }

        Set<String> currentKeys = new LinkedHashSet<>(incoming.keySet());
        int retired = 0;
        for (BotKnowledgeSemanticUnit unit : existing) {
            if (retireMissingDrafts && "DRAFT".equals(unit.getStatus())
                    && !currentKeys.contains(unit.getUnitKey())) {
                unit.setStatus("REJECTED");
                unitMapper.updateById(unit);
                retired++;
            }
        }
        return new PersistResult(incoming.size(), inserted, updated, retired,
            preservedReviewed, List.copyOf(ids));
    }

    private void validateCandidate(Long documentId, String sourceHash,
                                   BotKnowledgeSemanticUnit candidate) {
        if (candidate == null
                || !documentId.equals(candidate.getDocumentId())
                || !sourceHash.equals(candidate.getSourceHash())
                || candidate.getUnitKey() == null || candidate.getUnitKey().isBlank()) {
            throw new IllegalArgumentException("invalid semantic unit candidate");
        }
        if (!"DRAFT".equals(candidate.getStatus())) {
            throw new IllegalArgumentException("only DRAFT semantic units may be persisted");
        }
    }

    public record PersistResult(int distinctCandidates, int inserted, int updated,
                                int retired, int preservedReviewed, List<Long> unitIds) {}
}
