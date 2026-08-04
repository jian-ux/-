package com.feisheng.bot.core.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.core.entity.BotIntent;
import com.feisheng.bot.core.mapper.BotIntentMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class IntentService {
    private final BotIntentMapper mapper;

    public IntentService(BotIntentMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<IntentMatch> match(String text) {
        if (!StringUtils.hasText(text)) return Optional.empty();
        String normalizedText = normalize(text);
        List<BotIntent> intents = mapper.selectList(
            new LambdaQueryWrapper<BotIntent>()
                .eq(BotIntent::getStatus, 1)
                .orderByAsc(BotIntent::getId));
        if (intents == null || intents.isEmpty()) return Optional.empty();

        return intents.stream()
            .filter(intent -> Integer.valueOf(1).equals(intent.getStatus()))
            .filter(intent -> StringUtils.hasText(intent.getReplyTemplate()))
            .flatMap(intent -> keywords(intent).stream()
                .filter(keyword -> normalizedText.contains(normalize(keyword)))
                .map(keyword -> new Candidate(intent, keyword)))
            .min(Comparator
                .comparingInt((Candidate candidate) -> candidate.keyword().length()).reversed()
                .thenComparing(candidate -> candidate.intent().getId(),
                    Comparator.nullsLast(Comparator.naturalOrder())))
            .map(candidate -> toMatch(candidate.intent(), candidate.keyword()));
    }

    private List<String> keywords(BotIntent intent) {
        if (!StringUtils.hasText(intent.getIntentKeywords())) return List.of();
        return List.of(intent.getIntentKeywords().split("[,，\\r\\n]+"))
            .stream()
            .map(String::trim)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    }

    private IntentMatch toMatch(BotIntent intent, String keyword) {
        String reply = intent.getReplyTemplate().trim()
            .replace("{{keyword}}", keyword)
            .replace("{{intent}}", intent.getIntentName() == null ? "" : intent.getIntentName());
        return new IntentMatch(intent.getId(), intent.getIntentName(), keyword, reply);
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT);
    }

    private record Candidate(BotIntent intent, String keyword) {
    }

    public record IntentMatch(Long intentId, String intentName, String keyword, String reply) {
    }
}
