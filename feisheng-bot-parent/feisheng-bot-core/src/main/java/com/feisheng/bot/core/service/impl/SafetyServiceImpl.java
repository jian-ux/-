package com.feisheng.bot.core.service.impl;

import com.feisheng.bot.core.dto.SafetyResult;
import com.feisheng.bot.core.service.SafetyRuleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Safety rule engine — pre-check user input + post-check AI output.
 * Rules are loaded from admin module via HTTP (localhost after merge).
 */
@Service
public class SafetyServiceImpl {
    private static final Logger log = LoggerFactory.getLogger(SafetyServiceImpl.class);
    private final ObjectProvider<SafetyRuleProvider> providers;

    /** Cached rules, refreshed periodically */
    private volatile List<RuleEntry> cache = Collections.emptyList();
    private volatile long lastRefresh = 0;

    public SafetyServiceImpl(ObjectProvider<SafetyRuleProvider> providers) {
        this.providers = providers;
    }

    /**
     * Pre-check: scan user input for FORCE_HANDOFF / SENSITIVE_WORD / FORBIDDEN_TOPIC rules.
     */
    public SafetyResult checkUserInput(String text) {
        if (text == null || text.isEmpty()) return SafetyResult.pass();
        List<RuleEntry> rules = getRules();
        String normalized = normalize(text);
        List<String> logOnlyHits = new ArrayList<>();

        for (RuleEntry rule : rules) {
            if (!rule.enabled) continue;
            String type = rule.type;
            // Only pre-check types
            if (!"FORCE_HANDOFF".equals(type) && !"SENSITIVE_WORD".equals(type)
                    && !"FORBIDDEN_TOPIC".equals(type)) continue;

            if (match(normalized, rule)) {
                log.info("Safety pre-check hit: type={} pattern={} action={}", type, rule.pattern, rule.action);
                switch (rule.action) {
                    case "HANDOFF":
                        return SafetyResult.handoff(rule.description != null ? rule.description : rule.pattern);
                    case "BLOCK":
                        return SafetyResult.block(rule.description != null ? rule.description : rule.pattern);
                    case "REPLY_FIXED":
                        return SafetyResult.fixedReply(
                            rule.replyText != null ? rule.replyText : "抱歉，我无法回答这个问题。",
                            rule.description != null ? rule.description : rule.pattern);
                    case "LOG_ONLY":
                        logOnlyHits.add(rule.description != null ? rule.description : rule.pattern);
                        break;
                }
            }
        }
        SafetyResult result = SafetyResult.pass();
        result.getHitRules().addAll(logOnlyHits);
        return result;
    }

    /**
     * Post-check: scan AI output for AI_DISCLAIMER violations.
     */
    public SafetyResult checkAiOutput(String text) {
        if (text == null || text.isEmpty()) return SafetyResult.pass();
        List<RuleEntry> rules = getRules();
        String normalized = normalize(text);
        List<String> logOnlyHits = new ArrayList<>();

        for (RuleEntry rule : rules) {
            if (!rule.enabled) continue;
            if (!"AI_DISCLAIMER".equals(rule.type)) continue;

            if (match(normalized, rule)) {
                log.warn("Safety post-check hit: pattern={} action={}", rule.pattern, rule.action);
                switch (rule.action) {
                    case "BLOCK":
                        return SafetyResult.block("AI 输出命中免责规则: " + rule.description);
                    case "REPLY_FIXED":
                        return SafetyResult.fixedReply(
                            rule.replyText != null ? rule.replyText : "抱歉，我的回答需要修正。",
                            "AI 输出命中免责规则: " + rule.description);
                    case "LOG_ONLY":
                        logOnlyHits.add(rule.description != null ? rule.description : rule.pattern);
                        break;
                    default:
                        break;
                }
            }
        }
        SafetyResult result = SafetyResult.pass();
        result.getHitRules().addAll(logOnlyHits);
        return result;
    }

    /**
     * Load rules from admin API with 1-minute cache.
     */
    @SuppressWarnings("unchecked")
    private List<RuleEntry> getRules() {
        long now = System.currentTimeMillis();
        if (now - lastRefresh < 60_000) return cache;

        try {
            SafetyRuleProvider provider = providers.orderedStream().findFirst().orElse(null);
            if (provider == null) {
                cache = getDefaultRules();
                lastRefresh = now;
                return cache;
            }
            List<Map<String, Object>> records = provider.getEnabledRules();
            List<RuleEntry> rules = new ArrayList<>();
            if (records != null) for (Map<String, Object> r : records) {
                RuleEntry e = new RuleEntry();
                e.type = stringValue(r.get("ruleType"));
                e.pattern = stringValue(r.get("pattern"));
                e.action = stringValue(r.get("action"));
                e.replyText = nullableString(r.get("replyText"));
                e.description = nullableString(r.get("description"));
                e.enabled = intValue(r.get("isEnabled"), 0) == 1;
                e.priority = intValue(r.get("priority"), 0);
                e.isRegex = intValue(r.get("isRegex"), 0) == 1;
                if (!e.pattern.isBlank()) rules.add(e);
            }
            rules.sort(Comparator.comparingInt(a -> a.priority));
            cache = rules.isEmpty() ? getDefaultRules() : rules;
            lastRefresh = now;
            log.debug("Safety rules refreshed: {} rules", cache.size());
        } catch (Exception e) {
            log.warn("Failed to refresh safety rules: {}", e.getMessage());
            if (cache.isEmpty()) {
                cache = getDefaultRules();
            }
            lastRefresh = now;
        }
        return cache;
    }

    private List<RuleEntry> getDefaultRules() {
        List<RuleEntry> rules = new ArrayList<>();
        RuleEntry r = new RuleEntry();
        r.type = "FORCE_HANDOFF"; r.pattern = "退款|投诉|曝光|律师函|起诉";
        r.action = "HANDOFF"; r.enabled = true; r.priority = 1; r.description = "默认转人工规则";
        rules.add(r);
        return rules;
    }

    private boolean match(String text, RuleEntry rule) {
        if (rule.isRegex) {
            try {
                return Pattern.compile(rule.pattern, Pattern.CASE_INSENSITIVE).matcher(text).find();
            } catch (Exception e) {
                return false;
            }
        }
        for (String literal : rule.pattern.toLowerCase().split("\\|")) {
            if (!literal.isBlank() && text.contains(literal)) return true;
        }
        return false;
    }

    /** Normalize Unicode width, remove zero-width chars and strip punctuation. */
    private String normalize(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                   .replaceAll("[\\u200B-\\u200F\\uFEFF]", "")
                   .replaceAll("[\\p{P}\\p{S}]", "")           // punctuation/symbols
                   .toLowerCase()
                   .trim();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String nullableString(Object value) {
        return value == null ? null : value.toString();
    }

    private static int intValue(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    /** Internal rule entry */
    private static class RuleEntry {
        String type;
        String pattern;
        String action;
        String replyText;
        String description;
        boolean enabled;
        boolean isRegex;
        int priority;
    }
}
