package com.feisheng.bot.core.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Matches the concrete fact shape explicitly requested by a customer. */
final class EvidenceAnswerTypeMatcher {
    private static final Pattern PRICE_QUERY = Pattern.compile(
        "多少钱|价格|价钱|收费|费用|报价|单价|价位");
    private static final Pattern DURATION_QUERY = Pattern.compile(
        "多久|多长时间|几天|几个月|几年|有效期|时效|期限|什么时候到期");
    private static final Pattern QUANTITY_QUERY = Pattern.compile(
        "多少份|多少个|多少次|多少人|最多|最少|上限|数量|额度|份数|次数");
    private static final Pattern PRICE_AMOUNT_FACT = Pattern.compile(
        "(?:人民币|￥|¥)?\\s*[0-9]+(?:\\.[0-9]+)?\\s*(?:元|万元|块钱?)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern FREE_PRICE_FACT = Pattern.compile("免费");
    private static final Pattern DURATION_FACT = Pattern.compile(
        "[0-9一二三四五六七八九十百千万两]+\\s*(?:秒|分钟|小时|天|日|周|个月|月|年|工作日)");
    private static final Pattern QUANTITY_FACT = Pattern.compile(
        "[0-9一二三四五六七八九十百千万两]+\\s*(?:份|个|次|人|家公司|家|张|页|mb|gb)",
        Pattern.CASE_INSENSITIVE);

    private EvidenceAnswerTypeMatcher() {}

    static List<Requirement> requirements(String query) {
        String normalized = normalize(query);
        List<Requirement> result = new ArrayList<>();
        if (PRICE_QUERY.matcher(normalized).find()) result.add(Requirement.PRICE);
        if (DURATION_QUERY.matcher(normalized).find()) result.add(Requirement.DURATION);
        if (QUANTITY_QUERY.matcher(normalized).find()) result.add(Requirement.QUANTITY);
        return List.copyOf(result);
    }

    static boolean matches(Requirement requirement, String evidence) {
        return matchCount(requirement, evidence) > 0;
    }

    static int matchCount(Requirement requirement, String evidence) {
        String normalized = normalize(evidence);
        return switch (requirement) {
            case PRICE -> count(PRICE_AMOUNT_FACT, normalized)
                + count(FREE_PRICE_FACT, normalized);
            case DURATION -> count(DURATION_FACT, normalized);
            case QUANTITY -> count(QUANTITY_FACT, normalized);
        };
    }

    static int specificity(Requirement requirement, String evidence) {
        String normalized = normalize(evidence);
        return switch (requirement) {
            case PRICE -> count(PRICE_AMOUNT_FACT, normalized) * 2
                + Math.min(1, count(FREE_PRICE_FACT, normalized));
            case DURATION -> count(DURATION_FACT, normalized);
            case QUANTITY -> count(QUANTITY_FACT, normalized);
        };
    }

    private static int count(Pattern pattern, String value) {
        int result = 0;
        var matcher = pattern.matcher(value);
        while (matcher.find()) result++;
        return result;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    enum Requirement { PRICE, DURATION, QUANTITY }
}
