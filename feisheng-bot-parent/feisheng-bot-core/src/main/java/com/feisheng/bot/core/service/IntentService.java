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
import java.util.Set;

@Service
public class IntentService {
    private static final Set<String> QUESTION_MARKERS = Set.of(
        "怎么", "如何", "怎样", "吗", "么", "是否", "能否", "可否", "可以",
        "支持", "查询", "进度", "步骤", "为什么", "什么", "哪个", "哪里", "多少");
    private static final Set<String> EXPLICIT_COMMAND_MARKERS = Set.of(
        "我要", "我想", "申请", "办理", "转人工", "联系客服", "投诉", "退订", "取消", "帮我");

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
                .filter(keyword -> isUsableMatch(normalizedText, keyword))
                .map(keyword -> new Candidate(intent, keyword)))
            .min(Comparator
                .comparingInt((Candidate candidate) -> normalizedLength(candidate.keyword())).reversed()
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
            .toLowerCase(Locale.ROOT)
            .replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }

    private boolean isUsableMatch(String normalizedText, String keyword) {
        String normalizedKeyword = normalize(keyword);
        if (normalizedKeyword.isEmpty() || !normalizedText.contains(normalizedKeyword)) {
            return false;
        }

        // Two-character rules are useful for explicit commands such as
        // "我要退款", but are too broad for substantive questions such as
        // "合同怎么修改". Let the knowledge and contextual pipeline handle
        // those questions instead of returning a static keyword reply.
        if (normalizedKeyword.length() <= 2
                && normalizedText.length() >= normalizedKeyword.length() + 2
                && hasQuestionMarker(normalizedText)
                && (!normalizedText.endsWith(normalizedKeyword)
                    || !hasExplicitCommandMarker(normalizedText))) {
            return false;
        }
        return true;
    }

    private boolean hasQuestionMarker(String normalizedText) {
        return QUESTION_MARKERS.stream().anyMatch(normalizedText::contains);
    }

    private boolean hasExplicitCommandMarker(String normalizedText) {
        return EXPLICIT_COMMAND_MARKERS.stream().anyMatch(normalizedText::contains);
    }

    private int normalizedLength(String value) {
        return normalize(value).length();
    }

    private record Candidate(BotIntent intent, String keyword) {
    }

    public record IntentMatch(Long intentId, String intentName, String keyword, String reply) {
    }
}
