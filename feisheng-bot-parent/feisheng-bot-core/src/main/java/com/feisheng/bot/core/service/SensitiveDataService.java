package com.feisheng.bot.core.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SensitiveDataService {
    private static final Pattern EMAIL = Pattern.compile(
        "(?i)(?<![a-z0-9._%+-])[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}(?![a-z0-9._%+-])");
    private static final Pattern ID_CARD = Pattern.compile(
        "(?<![0-9A-Za-z])(?:[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx]|[1-9]\\d{14})(?![0-9A-Za-z])");
    private static final Pattern PHONE = Pattern.compile(
        "(?<!\\d)(?:(?:\\+?86)[ -]?)?1[3-9]\\d{9}(?!\\d)");
    private static final Pattern BANK_CARD = Pattern.compile(
        "(?<!\\d)[1-9](?:\\d[ -]?){14,17}\\d(?!\\d)");
    private static final Pattern ADDRESS_WITH_COLON = Pattern.compile(
        "(收货地址|联系地址|家庭住址|居住地址|住址|地址)(\\s*[:：]\\s*)([^\\n,，。;；]{4,120})");
    private static final Pattern ADDRESS_WITH_VERB = Pattern.compile(
        "(收货地址|联系地址|家庭住址|居住地址|住址|地址)(\\s*(?:是|为)\\s*)([^\\n,，。;；]{4,120})");
    private static final Pattern WEB_URL = Pattern.compile(
        "(?i)(?:https?://|www\\.)[^\\s<>()]+");

    private final List<String> allowedValues;

    public SensitiveDataService(
            @Value("${security.pii.allowed-values:18689633999}") String allowedValues) {
        this.allowedValues = parseAllowedValues(allowedValues);
    }

    public RedactionResult redact(String value) {
        if (value == null || value.isEmpty()) {
            return new RedactionResult(value, false, Set.of());
        }

        List<String> protectedValues = new ArrayList<>();
        String redacted = protectAllowedValues(value, protectedValues);
        LinkedHashSet<String> types = new LinkedHashSet<>();
        redacted = replace(redacted, EMAIL, "[邮箱已脱敏]", "EMAIL", types);
        redacted = replace(redacted, ID_CARD, "[身份证已脱敏]", "ID_CARD", types);
        redacted = replace(redacted, PHONE, "[手机号已脱敏]", "PHONE", types);
        redacted = replace(redacted, BANK_CARD, "[银行卡已脱敏]", "BANK_CARD", types);
        redacted = replaceAddress(redacted, ADDRESS_WITH_COLON, types);
        redacted = replaceAddress(redacted, ADDRESS_WITH_VERB, types);
        redacted = restoreAllowedValues(redacted, protectedValues);
        return new RedactionResult(redacted, !types.isEmpty(),
            Collections.unmodifiableSet(new LinkedHashSet<>(types)));
    }

    public boolean containsSensitiveData(String value) {
        return redact(value).applied();
    }

    private String replace(String value, Pattern pattern, String replacement,
                           String type, Set<String> types) {
        Matcher matcher = pattern.matcher(value);
        if (!matcher.find()) return value;
        types.add(type);
        return matcher.replaceAll(Matcher.quoteReplacement(replacement));
    }

    private String replaceAddress(String value, Pattern pattern, Set<String> types) {
        Matcher matcher = pattern.matcher(value);
        if (!matcher.find()) return value;
        StringBuffer result = new StringBuffer();
        boolean redacted = false;
        do {
            String replacement = matcher.group(0);
            if (!WEB_URL.matcher(matcher.group(3)).find()) {
                replacement = matcher.group(1) + matcher.group(2) + "[地址已脱敏]";
                redacted = true;
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        } while (matcher.find());
        matcher.appendTail(result);
        if (redacted) types.add("ADDRESS");
        return result.toString();
    }

    private String protectAllowedValues(String value, List<String> protectedValues) {
        String protectedText = value;
        for (String allowed : allowedValues) {
            if (!allowed.isEmpty() && protectedText.contains(allowed)) {
                String marker = "__PII_ALLOWED_" + protectedValues.size() + "__";
                protectedValues.add(allowed);
                protectedText = protectedText.replace(allowed, marker);
            }
        }
        return protectedText;
    }

    private String restoreAllowedValues(String value, List<String> protectedValues) {
        String restored = value;
        for (int i = 0; i < protectedValues.size(); i++) {
            restored = restored.replace("__PII_ALLOWED_" + i + "__", protectedValues.get(i));
        }
        return restored;
    }

    private List<String> parseAllowedValues(String configured) {
        if (configured == null || configured.isBlank()) return List.of();
        List<String> values = new ArrayList<>();
        for (String value : configured.split(",")) {
            if (!value.isBlank()) values.add(value.trim());
        }
        return List.copyOf(values);
    }

    public record RedactionResult(String text, boolean applied, Set<String> types) {}
}
