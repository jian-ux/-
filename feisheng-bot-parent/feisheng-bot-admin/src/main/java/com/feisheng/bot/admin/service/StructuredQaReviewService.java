package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.admin.entity.BotKnowledgeChunk;
import com.feisheng.bot.admin.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.common.util.StructuredQaUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class StructuredQaReviewService {
    private final BotKnowledgeChunkMapper chunkMapper;

    public StructuredQaReviewService(BotKnowledgeChunkMapper chunkMapper) {
        this.chunkMapper = chunkMapper;
    }

    @Transactional
    public UpdateResult updateDirectAnswer(Long chunkId, boolean enabled,
                                           Integer requestedVersion) {
        BotKnowledgeChunk anchor = chunkMapper.selectById(chunkId);
        if (anchor == null) throw new ReviewException(404, "问答分片不存在");
        if (!isStructuredQa(anchor)) {
            throw new ReviewException(400, "只有结构化问答分片可以开启直答");
        }

        List<BotKnowledgeChunk> allQaChunks = chunkMapper.selectList(
            new LambdaQueryWrapper<BotKnowledgeChunk>()
                .eq(BotKnowledgeChunk::getContentType, "QA"));
        String qaKey = StructuredQaUtil.canonicalKey(anchor.getQaQuestion());
        String sourceGroupKey = sourceGroupKey(anchor);
        List<BotKnowledgeChunk> sourceGroup = allQaChunks.stream()
            .filter(chunk -> Objects.equals(anchor.getDocumentId(), chunk.getDocumentId()))
            .filter(chunk -> qaKey.equals(canonicalKey(chunk)))
            .filter(chunk -> sourceGroupKey.equals(sourceGroupKey(chunk)))
            .sorted(Comparator.comparingInt(this::chunkIndex))
            .toList();
        if (sourceGroup.isEmpty()) {
            throw new ReviewException(400, "未找到完整的结构化问答分组");
        }

        int version = requestedVersion == null
            ? sourceGroup.stream().mapToInt(this::version).max().orElse(1)
            : requestedVersion;
        if (version < 1) throw new ReviewException(400, "问答版本必须大于 0");

        String fullAnswer = canonicalAnswer(sourceGroup);
        if (enabled) {
            if (sourceGroup.stream().anyMatch(chunk -> !"APPROVED".equals(chunk.getStatus()))) {
                throw new ReviewException(409, "该问答的所有分片审核通过后才能开启直答");
            }
            if (qaKey.isBlank() || fullAnswer.isBlank()) {
                throw new ReviewException(400, "问答的问题或答案不完整，不能开启直答");
            }
            validateCurrentVersionAndConflicts(
                allQaChunks, sourceGroup, qaKey, version, fullAnswer);
        }

        for (BotKnowledgeChunk chunk : sourceGroup) {
            chunk.setContentType("QA");
            chunk.setQaKey(qaKey);
            chunk.setQaGroupKey(StructuredQaUtil.sourceGroupKey(
                chunk.getQaQuestion(), fullAnswer));
            chunk.setQaVersion(version);
            chunk.setQaAnswer(fullAnswer);
            chunk.setDirectAnswerEnabled(enabled ? 1 : 0);
            chunkMapper.updateById(chunk);
        }
        return new UpdateResult(sourceGroup.size(), enabled, version, fullAnswer.length());
    }

    @Transactional
    public void disableGroup(Long chunkId) {
        BotKnowledgeChunk chunk = chunkMapper.selectById(chunkId);
        if (chunk == null || !isStructuredQa(chunk)
                || !Integer.valueOf(1).equals(chunk.getDirectAnswerEnabled())) return;
        updateDirectAnswer(chunkId, false, chunk.getQaVersion());
    }

    private void validateCurrentVersionAndConflicts(
            List<BotKnowledgeChunk> allQaChunks,
            List<BotKnowledgeChunk> targetGroup,
            String qaKey, int targetVersion, String targetAnswer) {
        Map<String, List<BotKnowledgeChunk>> sourceGroups = new LinkedHashMap<>();
        for (BotKnowledgeChunk chunk : allQaChunks) {
            if (!qaKey.equals(canonicalKey(chunk)) || targetGroup.contains(chunk)) continue;
            sourceGroups.computeIfAbsent(sourceIdentity(chunk), ignored -> new ArrayList<>())
                .add(chunk);
        }

        int highestActiveVersion = targetVersion;
        List<ActiveAnswer> activeAnswers = new ArrayList<>();
        for (List<BotKnowledgeChunk> group : sourceGroups.values()) {
            if (!isActiveGroup(group)) continue;
            int groupVersion = group.stream().mapToInt(this::version).max().orElse(1);
            String answer = canonicalAnswer(group);
            if (answer.isBlank()) continue;
            highestActiveVersion = Math.max(highestActiveVersion, groupVersion);
            activeAnswers.add(new ActiveAnswer(groupVersion, answer));
        }
        if (targetVersion < highestActiveVersion) {
            throw new ReviewException(409,
                "已有更高版本的标准答案，不能启用较低版本直答");
        }

        String targetFingerprint = StructuredQaUtil.answerFingerprint(targetAnswer);
        boolean conflict = activeAnswers.stream()
            .filter(answer -> answer.version() == targetVersion)
            .map(ActiveAnswer::answer)
            .map(StructuredQaUtil::answerFingerprint)
            .anyMatch(fingerprint -> !targetFingerprint.equals(fingerprint));
        if (conflict) {
            throw new ReviewException(409,
                "同一问题的当前版本存在不同答案，请先停用或升级旧答案");
        }
    }

    private boolean isActiveGroup(List<BotKnowledgeChunk> chunks) {
        return !chunks.isEmpty() && chunks.stream().allMatch(chunk ->
            "APPROVED".equals(chunk.getStatus())
                && Integer.valueOf(1).equals(chunk.getDirectAnswerEnabled())
                && isStructuredQa(chunk));
    }

    private String canonicalAnswer(List<BotKnowledgeChunk> chunks) {
        List<String> answers = chunks.stream()
            .map(BotKnowledgeChunk::getQaAnswer)
            .filter(answer -> answer != null && !answer.isBlank())
            .toList();
        if (answers.isEmpty()) return "";
        long distinct = answers.stream()
            .map(StructuredQaUtil::answerFingerprint).distinct().count();
        if (distinct == 1) return answers.get(0).trim();

        List<String> fragments = new ArrayList<>();
        String previousFingerprint = null;
        for (String answer : answers) {
            String fingerprint = StructuredQaUtil.answerFingerprint(answer);
            if (!fingerprint.equals(previousFingerprint)) fragments.add(answer.trim());
            previousFingerprint = fingerprint;
        }
        return String.join("\n", fragments).trim();
    }

    private boolean isStructuredQa(BotKnowledgeChunk chunk) {
        return chunk != null && "QA".equals(chunk.getContentType())
            && chunk.getQaQuestion() != null && !chunk.getQaQuestion().isBlank();
    }

    private String canonicalKey(BotKnowledgeChunk chunk) {
        return StructuredQaUtil.canonicalKey(chunk.getQaQuestion());
    }

    private String sourceIdentity(BotKnowledgeChunk chunk) {
        return chunk.getDocumentId() + ":" + sourceGroupKey(chunk) + ":" + version(chunk);
    }

    private String sourceGroupKey(BotKnowledgeChunk chunk) {
        String stored = chunk.getQaGroupKey();
        if (stored != null && !stored.isBlank()) return stored;
        return StructuredQaUtil.sourceGroupKey(chunk.getQaQuestion(), chunk.getQaAnswer());
    }

    private int version(BotKnowledgeChunk chunk) {
        return chunk.getQaVersion() == null || chunk.getQaVersion() < 1
            ? 1 : chunk.getQaVersion();
    }

    private int chunkIndex(BotKnowledgeChunk chunk) {
        return chunk.getChunkIndex() == null ? Integer.MAX_VALUE : chunk.getChunkIndex();
    }

    private record ActiveAnswer(int version, String answer) {}

    public record UpdateResult(int updatedChunks, boolean enabled,
                               int version, int answerLength) {}

    public static class ReviewException extends RuntimeException {
        private final int status;

        public ReviewException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
