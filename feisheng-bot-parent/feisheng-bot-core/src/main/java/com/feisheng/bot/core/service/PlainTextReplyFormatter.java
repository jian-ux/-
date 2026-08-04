package com.feisheng.bot.core.service;

import java.util.regex.Pattern;

public final class PlainTextReplyFormatter {
    private static final Pattern FENCED_CODE_MARKER =
        Pattern.compile("(?m)^[ \\t]*`{3,}[^\\n]*$");
    private static final Pattern HEADING_MARKER =
        Pattern.compile("(?m)^[ \\t]{0,3}#{1,6}[ \\t]+");
    private static final Pattern QUOTE_MARKER =
        Pattern.compile("(?m)^[ \\t]{0,3}>+[ \\t]?");
    private static final Pattern DECORATIVE_LINE_PREFIX =
        Pattern.compile("(?m)^[ \\t]*(?:✅|⚠️?|🔍)[ \\t]*");
    private static final Pattern DOT_BULLET =
        Pattern.compile("(?m)^[ \\t]*[•●▪][ \\t]+");
    private static final Pattern STAR_BULLET =
        Pattern.compile("(?m)^[ \\t]*[+*][ \\t]+");
    private static final Pattern MARKDOWN_IMAGE =
        Pattern.compile("!\\[([^]\\n]*)]\\(([^)\\n]+)\\)");
    private static final Pattern MARKDOWN_LINK =
        Pattern.compile("(?<!!)\\[([^]\\n]+)]\\(([^)\\n]+)\\)");
    private static final Pattern BOLD_ITALIC_ASTERISK =
        Pattern.compile("(?<!\\*)\\*{3}(?!\\*)(\\S(?:[^*\\n]*?\\S)?)\\*{3}(?!\\*)");
    private static final Pattern BOLD_ASTERISK =
        Pattern.compile("(?<!\\*)\\*\\*(?!\\*)(\\S(?:[^*\\n]*?\\S)?)\\*\\*(?!\\*)");
    private static final Pattern BOLD_UNDERSCORE =
        Pattern.compile("(?<!_)__(?!_)(\\S(?:[^_\\n]*?\\S)?)__(?!_)");
    private static final Pattern STRIKETHROUGH =
        Pattern.compile("~~(\\S(?:[^~\\n]*?\\S)?)~~");
    private static final Pattern INLINE_CODE =
        Pattern.compile("(?<!`)`([^`\\n]+)`(?!`)");
    private static final Pattern EMPHASIS_ASTERISK =
        Pattern.compile("(?<![\\p{L}\\p{N}*])\\*(\\S(?:[^*\\n]*?\\S)?)\\*(?![\\p{L}\\p{N}*])");
    private static final Pattern EMPHASIS_UNDERSCORE =
        Pattern.compile("(?<![\\p{L}\\p{N}_])_(\\S(?:[^_\\n]*?\\S)?)_(?![\\p{L}\\p{N}_])");
    private static final Pattern NEXT_STEP_LABEL =
        Pattern.compile("(?m)^[ \\t]*下一步(?:[ \\t]*[：:]|[ \\t]+)[ \\t]*");
    private static final Pattern TRAILING_SPACES = Pattern.compile("(?m)[ \\t]+$");
    private static final Pattern EXCESS_BLANK_LINES = Pattern.compile("\\n{3,}");

    private PlainTextReplyFormatter() {}

    public static String format(String value) {
        if (value == null || value.isEmpty()) return value;

        String text = value.replace("\\r\\n", "\\n").replace('\r', '\n');
        text = FENCED_CODE_MARKER.matcher(text).replaceAll("");
        text = HEADING_MARKER.matcher(text).replaceAll("");
        text = QUOTE_MARKER.matcher(text).replaceAll("");
        text = DECORATIVE_LINE_PREFIX.matcher(text).replaceAll("");
        text = DOT_BULLET.matcher(text).replaceAll("- ");
        text = STAR_BULLET.matcher(text).replaceAll("- ");
        text = MARKDOWN_IMAGE.matcher(text).replaceAll("$1（$2）");
        text = MARKDOWN_LINK.matcher(text).replaceAll("$1（$2）");
        text = BOLD_ITALIC_ASTERISK.matcher(text).replaceAll("$1");
        text = BOLD_ASTERISK.matcher(text).replaceAll("$1");
        text = BOLD_UNDERSCORE.matcher(text).replaceAll("$1");
        text = STRIKETHROUGH.matcher(text).replaceAll("$1");
        text = INLINE_CODE.matcher(text).replaceAll("$1");
        text = EMPHASIS_ASTERISK.matcher(text).replaceAll("$1");
        text = EMPHASIS_UNDERSCORE.matcher(text).replaceAll("$1");
        text = NEXT_STEP_LABEL.matcher(text).replaceAll("");
        text = TRAILING_SPACES.matcher(text).replaceAll("");
        return EXCESS_BLANK_LINES.matcher(text).replaceAll("\\n\\n").strip();
    }
}
