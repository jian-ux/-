package com.feisheng.bot.core.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
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
        "([0-9一二三四五六七八九十百千万两零]+)\\s*(工作日|分钟|小时|个月|秒|天|日|周|月|年)");
    private static final Pattern QUANTITY_FACT = Pattern.compile(
        "([0-9一二三四五六七八九十百千万两零]+)\\s*(家公司|份|个|次|人|家|张|页|mb|gb)",
        Pattern.CASE_INSENSITIVE);

    private EvidenceAnswerTypeMatcher() {}

    static List<Requirement> requirements(String query) {
        String normalized = normalize(query);
        List<Requirement> result = new ArrayList<>();
        if (PRICE_QUERY.matcher(normalized).find()
                || PRICE_AMOUNT_FACT.matcher(normalized).find()
                || FREE_PRICE_FACT.matcher(normalized).find()) {
            result.add(Requirement.PRICE);
        }
        if (DURATION_QUERY.matcher(normalized).find()
                || DURATION_FACT.matcher(normalized).find()) {
            result.add(Requirement.DURATION);
        }
        if (QUANTITY_QUERY.matcher(normalized).find()
                || QUANTITY_FACT.matcher(normalized).find()) {
            result.add(Requirement.QUANTITY);
        }
        return List.copyOf(result);
    }

    static boolean coversConcreteFacts(Requirement requirement, String query,
                                       String evidence) {
        Set<String> requested = concreteFacts(requirement, query);
        return !requested.isEmpty()
            && concreteFacts(requirement, evidence).containsAll(requested);
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

    private static Set<String> concreteFacts(Requirement requirement, String value) {
        String normalized = normalize(value);
        Set<String> result = new LinkedHashSet<>();
        switch (requirement) {
            case PRICE -> {
                if (FREE_PRICE_FACT.matcher(normalized).find()) result.add("price:free");
                Matcher matcher = PRICE_AMOUNT_FACT.matcher(normalized);
                while (matcher.find()) {
                    String match = matcher.group().replaceAll("^(?:人民币|￥|¥)\\s*", "")
                        .replaceAll("\\s+", "");
                    String unit = match.endsWith("万元") ? "万元"
                        : match.endsWith("块钱") ? "块钱"
                        : match.endsWith("块") ? "块" : "元";
                    String amount = match.substring(0, match.length() - unit.length());
                    BigDecimal canonical = new BigDecimal(amount);
                    if ("万元".equals(unit)) canonical = canonical.multiply(BigDecimal.valueOf(10000));
                    result.add("price:" + canonical.stripTrailingZeros().toPlainString() + ":元");
                }
            }
            case DURATION -> addNumberUnitFacts(result, DURATION_FACT, normalized, true);
            case QUANTITY -> addNumberUnitFacts(result, QUANTITY_FACT, normalized, false);
        }
        return Set.copyOf(result);
    }

    private static void addNumberUnitFacts(Set<String> result, Pattern pattern,
                                           String value, boolean duration) {
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            String unit = matcher.group(2).toLowerCase(Locale.ROOT);
            if (duration && "日".equals(unit)) unit = "天";
            result.add((duration ? "duration:" : "quantity:")
                + canonicalNumber(matcher.group(1)) + ":" + unit);
        }
    }

    private static String canonicalNumber(String value) {
        if (value.chars().allMatch(character -> Character.isDigit(character))) {
            return new BigDecimal(value).stripTrailingZeros().toPlainString();
        }
        boolean hasUnit = value.chars().anyMatch(character ->
            character == '十' || character == '百' || character == '千' || character == '万');
        if (!hasUnit) {
            StringBuilder digits = new StringBuilder();
            for (int index = 0; index < value.length(); index++) {
                int digit = chineseDigit(value.charAt(index));
                if (digit >= 0) digits.append(digit);
            }
            return digits.isEmpty() ? value : digits.toString();
        }

        long total = 0;
        long section = 0;
        long number = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            int digit = chineseDigit(character);
            if (digit >= 0) {
                number = digit;
                continue;
            }
            if (character == '万') {
                section += number;
                total += Math.max(section, 1) * 10000;
                section = 0;
                number = 0;
                continue;
            }
            int unit = character == '十' ? 10
                : character == '百' ? 100
                : character == '千' ? 1000 : 0;
            if (unit > 0) {
                section += Math.max(number, 1) * unit;
                number = 0;
            }
        }
        return Long.toString(total + section + number);
    }

    private static int chineseDigit(char value) {
        return switch (value) {
            case '零' -> 0;
            case '一' -> 1;
            case '二', '两' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> -1;
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
