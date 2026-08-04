package com.feisheng.bot.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.core.entity.BotUnmatchedQuestion;
import com.feisheng.bot.core.mapper.BotUnmatchedQuestionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class UnmatchedQuestionService {
    private static final Logger log = LoggerFactory.getLogger(UnmatchedQuestionService.class);

    private final BotUnmatchedQuestionMapper mapper;

    public UnmatchedQuestionService(BotUnmatchedQuestionMapper mapper) {
        this.mapper = mapper;
    }

    public void record(String question) {
        String normalized = normalize(question);
        if (normalized.length() < 2) return;
        try {
            BotUnmatchedQuestion existing = mapper.selectOne(
                new LambdaQueryWrapper<BotUnmatchedQuestion>()
                    .eq(BotUnmatchedQuestion::getQuestion, normalized)
                    .eq(BotUnmatchedQuestion::getIsResolved, 0)
                    .last("LIMIT 1"));
            if (existing == null) {
                BotUnmatchedQuestion created = new BotUnmatchedQuestion();
                created.setQuestion(normalized);
                created.setSimilarCount(1);
                created.setIsResolved(0);
                mapper.insert(created);
            } else {
                existing.setSimilarCount(existing.getSimilarCount() == null
                    ? 1 : existing.getSimilarCount() + 1);
                mapper.updateById(existing);
            }
        } catch (Exception e) {
            log.warn("Failed to record unmatched question: {}", e.getMessage());
        }
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("\\s+", " ");
    }
}
