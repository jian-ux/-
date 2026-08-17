package com.feisheng.bot.core.service;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps a conservative Markdown presentation separate from the plain-text contract. */
public final class RichReplyFormatter {
    private static final int MAX_REPLY_CHARS = 8000;
    private static final Pattern SCRIPT_OR_STYLE = Pattern.compile(
        "(?is)<(script|style)[^>]*>.*?</\\1>");
    private static final Pattern HTML_TAG = Pattern.compile(
        "(?is)</?[a-z][^>]{0,200}>");
    private static final Pattern MARKDOWN_LINK = Pattern.compile(
        "(!?)\\[([^]\\n]{0,200})]\\(([^)\\r\\n]{1,1000})\\)");
    private static final Pattern MARKDOWN_STRUCTURE = Pattern.compile(
        "(?m)(?:^#{1,6}\\s+|^\\s*[-*+]\\s+|^\\s*\\d+[.)]\\s+|\\*\\*|__|~~|`[^`\\n]+`|\\[[^]\\n]+]\\([^)]*\\))");

    private RichReplyFormatter() {}

    public static String format(String value) {
        if (value == null || value.isBlank()) return "";
        String text = value.replace("\r\n", "\n").replace('\r', '\n')
            .replaceAll("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]", "");
        text = SCRIPT_OR_STYLE.matcher(text).replaceAll("");
        text = HTML_TAG.matcher(text).replaceAll("");
        text = sanitizeLinks(text);
        text = text.replaceAll("\\n{3,}", "\\n\\n").strip();
        if (text.length() > MAX_REPLY_CHARS) {
            text = text.substring(0, MAX_REPLY_CHARS - 1).stripTrailing() + "…";
        }
        return text;
    }

    public static boolean isRich(String value) {
        String safe = format(value);
        return !safe.isBlank() && MARKDOWN_STRUCTURE.matcher(safe).find();
    }

    private static String sanitizeLinks(String value) {
        Matcher matcher = MARKDOWN_LINK.matcher(value);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String label = matcher.group(2).strip();
            String target = matcher.group(3).strip();
            String replacement;
            if (!matcher.group(1).isEmpty() || !isSafeHttpUrl(target)) {
                replacement = label;
            } else {
                replacement = "[" + label + "](" + target + ")";
            }
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static boolean isSafeHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return uri.isAbsolute()
                && ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
