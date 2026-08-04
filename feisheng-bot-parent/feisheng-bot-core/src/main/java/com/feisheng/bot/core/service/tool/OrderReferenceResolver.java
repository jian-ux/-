package com.feisheng.bot.core.service.tool;

import com.feisheng.bot.core.entity.BotMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class OrderReferenceResolver {
    private static final Pattern LABELED_ORDER = Pattern.compile(
        "(?i)(?:订单号|订单编号|order\\s*(?:id|no\\.?))\\s*(?:是|为|[:：#])?\\s*([a-z0-9][a-z0-9_-]{5,31})");
    private static final Pattern MIXED_ORDER = Pattern.compile(
        "(?i)(?<![a-z0-9_-])(?=[a-z0-9_-]{8,32}(?![a-z0-9_-]))(?=[a-z0-9_-]*\\d)[a-z][a-z0-9_-]{7,31}");
    private static final Pattern NUMERIC_ORDER = Pattern.compile("(?<!\\d)\\d{12,24}(?!\\d)");

    public String resolve(String question, List<BotMessage> recentMessages) {
        String current = extract(question, true);
        if (current != null) return current;
        if (recentMessages == null) return null;
        int start = Math.max(0, recentMessages.size() - 8);
        for (int i = recentMessages.size() - 1; i >= start; i--) {
            BotMessage message = recentMessages.get(i);
            if (message == null || !"user".equals(message.getRole())) continue;
            String value = extract(message.getContent(), false);
            if (value != null) return value;
        }
        return null;
    }

    public boolean hasReference(String question) {
        return extract(question, true) != null;
    }

    public String mask(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) return "";
        String trimmed = orderNo.trim();
        return trimmed.length() <= 4 ? "****" : "****" + trimmed.substring(trimmed.length() - 4);
    }

    private String extract(String value, boolean allowBareNumeric) {
        if (value == null || value.isBlank()) return null;
        Matcher labeled = LABELED_ORDER.matcher(value);
        if (labeled.find()) return normalize(labeled.group(1));
        Matcher mixed = MIXED_ORDER.matcher(value);
        if (mixed.find()) return normalize(mixed.group());
        if (allowBareNumeric) {
            Matcher numeric = NUMERIC_ORDER.matcher(value);
            if (numeric.find()) return numeric.group();
        }
        return null;
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase();
    }
}
