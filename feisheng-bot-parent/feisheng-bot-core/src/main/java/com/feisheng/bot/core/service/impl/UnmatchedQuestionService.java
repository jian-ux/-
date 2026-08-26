package com.feisheng.bot.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.core.entity.BotUnmatchedQuestion;
import com.feisheng.bot.core.mapper.BotUnmatchedQuestionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

@Service
public class UnmatchedQuestionService {
    private static final Logger log = LoggerFactory.getLogger(UnmatchedQuestionService.class);

    private final BotUnmatchedQuestionMapper mapper;

    public UnmatchedQuestionService(BotUnmatchedQuestionMapper mapper) {
        this.mapper = mapper;
    }

    public void record(String question) {
        recordBadCase(question, Set.of("NO_ANSWER"), BadCaseContext.empty());
    }

    public void recordBadCase(String question, Collection<String> triggerTypes,
                              BadCaseContext context) {
        String normalized = normalize(question);
        Set<String> normalizedTriggers = normalizeTriggers(triggerTypes);
        if (normalized.length() < 2 || normalizedTriggers.isEmpty()) return;
        try {
            BotUnmatchedQuestion existing = mapper.selectOne(
                new LambdaQueryWrapper<BotUnmatchedQuestion>()
                    .eq(BotUnmatchedQuestion::getQuestion, normalized)
                    .last("LIMIT 1"));
            if (existing == null) {
                BotUnmatchedQuestion created = new BotUnmatchedQuestion();
                created.setQuestion(normalized);
                created.setSimilarCount(1);
                created.setIsResolved(0);
                created.setTriggerTypes(String.join(",", normalizedTriggers));
                created.setReviewStatus("PENDING");
                applyContext(created, context);
                mapper.insert(created);
            } else {
                existing.setSimilarCount(existing.getSimilarCount() == null
                    ? 1 : existing.getSimilarCount() + 1);
                // A resolved question that appears again is a reopened bad case,
                // not a new question. Keep its history and start a fresh review.
                existing.setIsResolved(0);
                existing.setTriggerTypes(mergeTriggers(
                    existing.getTriggerTypes(), normalizedTriggers));
                // A new occurrence must be reviewed against the latest decision.
                existing.setReviewStatus("PENDING");
                applyContext(existing, context);
                existing.setUpdateTime(new Date());
                mapper.updateById(existing);
            }
        } catch (Exception e) {
            log.warn("Failed to record bad case: {}", e.getMessage());
        }
    }

    private void applyContext(BotUnmatchedQuestion target, BadCaseContext context) {
        if (context == null) return;
        target.setConversationId(context.conversationId());
        target.setLastAnswerStatus(context.answerStatus());
        if (context.answerDecision() != null) {
            target.setLastAnswerDecision(context.answerDecision());
        }
        if (context.reasonCode() != null) {
            target.setLastReasonCode(context.reasonCode());
        }
        target.setLastSource(context.source());
        target.setLastConfidence(context.confidence());
        target.setLastLatencyMs(context.latencyMs());
        target.setLastCsatScore(context.csatScore());
    }

    private Set<String> normalizeTriggers(Collection<String> triggerTypes) {
        Set<String> result = new LinkedHashSet<>();
        if (triggerTypes == null) return result;
        triggerTypes.stream()
            .map(value -> value == null ? "" : value.trim().toUpperCase(Locale.ROOT))
            .filter(value -> value.matches("[A-Z][A-Z0-9_]{1,49}"))
            .forEach(result::add);
        return result;
    }

    private String mergeTriggers(String existing, Set<String> additions) {
        Set<String> merged = new LinkedHashSet<>();
        if (existing != null && !existing.isBlank()) {
            Arrays.stream(existing.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .forEach(merged::add);
        }
        merged.addAll(additions);
        return String.join(",", merged);
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("\\s+", " ");
    }

    public record BadCaseContext(Long conversationId, String answerStatus,
                                 String source, Double confidence,
                                 Integer latencyMs, Integer csatScore,
                                 String answerDecision, String reasonCode) {
        public BadCaseContext(Long conversationId, String answerStatus,
                              String source, Double confidence,
                              Integer latencyMs, Integer csatScore) {
            this(conversationId, answerStatus, source, confidence, latencyMs,
                csatScore, null, null);
        }

        public static BadCaseContext empty() {
            return new BadCaseContext(null, null, null, null, null, null, null, null);
        }
    }
}
