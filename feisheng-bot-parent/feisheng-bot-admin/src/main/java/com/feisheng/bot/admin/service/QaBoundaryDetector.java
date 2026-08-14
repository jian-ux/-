package com.feisheng.bot.admin.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared conservative boundary rules for paragraph-based question and answer text. */
final class QaBoundaryDetector {
    private static final Pattern QUESTION_LABEL_RE = Pattern.compile(
        "^(?:问题|问|Q)(?:\\s*\\d+)?\\s*[:：、.．]\\s*(.+)$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern ANSWER_LABEL_RE = Pattern.compile(
        "^(?:答案|答|A)(?:\\s*\\d+)?\\s*[:：、.．]\\s*(.*)$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern QUESTION_SIGNAL_RE = Pattern.compile(
        "^(?:怎么|如何|为什么|为何|是否|能否|可否|有没有|哪里|哪种|哪些|什么|谁|多久|多少)|"
            + "(?:是什么|怎么办|能不能|可不可以|会不会|有没有|收费标准|法律效力|安全吗|靠谱吗|支持.+吗)$");
    private static final Pattern OBJECTION_RE = Pattern.compile(
        "(?:小平台|没听过|没有听过|太贵|价格高|还是签纸质|别人家|别的平台|竞品|不需要电子合同)");
    private static final Pattern SCRIPT_LEAD_RE = Pattern.compile(
        "^(?:点签[:：])?(?:您好|请问).*(?:使用|应用|疑问|问题).*(?:吗|呢)[？?]?$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern NESTED_TOPIC_RE = Pattern.compile(
        "^(?:发起多次后接收不到验证短信|更换法人认证|用户个人签名存在重名|接收不到合同|更换手机号|"
            + "客户反映.*|e登记的客户认证问题|企业对接API接口.*|.*(?:怎么办|如何处理|怎么处理))$",
        Pattern.CASE_INSENSITIVE);

    private QaBoundaryDetector() {}

    static String questionText(String value) {
        String line = value == null ? "" : value.trim();
        Matcher labelMatcher = QUESTION_LABEL_RE.matcher(line);
        if (labelMatcher.matches()) return labelMatcher.group(1).trim();
        if (line.isEmpty() || line.length() > 200) return null;
        if (line.endsWith("？") || line.endsWith("?")) return line;
        if (OBJECTION_RE.matcher(line).find()) return line;
        if (!QUESTION_SIGNAL_RE.matcher(line).find()) return null;

        // Do not split answer prose that merely begins with an interrogative clause.
        return line.matches(".*[。；;！!].*") || line.contains("？") || line.contains("?")
            ? null : line;
    }

    static boolean hasExplicitQuestionLabel(String value) {
        return value != null && QUESTION_LABEL_RE.matcher(value.trim()).matches();
    }

    static String answerText(String value) {
        String line = value == null ? "" : value.trim();
        Matcher matcher = ANSWER_LABEL_RE.matcher(line);
        return matcher.matches() ? matcher.group(1).trim() : line;
    }

    /**
     * Splits a sales/callback script row that embeds several independent support
     * topics. Requiring a script-like lead and at least two topic boundaries keeps
     * ordinary multi-line answers intact.
     */
    static List<NestedQa> nestedQuestionAnswers(String outerQuestion, String answer) {
        if (answer == null || answer.isBlank()) return List.of();
        List<String> lines = answer.lines().map(String::trim)
            .filter(line -> !line.isBlank()).toList();
        if (lines.size() < 5 || !isScriptLead(outerQuestion, lines.get(0))) return List.of();

        List<Integer> boundaries = new ArrayList<>();
        for (int index = 1; index < lines.size(); index++) {
            if (NESTED_TOPIC_RE.matcher(lines.get(index)).matches()) boundaries.add(index);
        }
        if (boundaries.size() < 2) return List.of();

        List<NestedQa> result = new ArrayList<>();
        for (int index = 0; index < boundaries.size(); index++) {
            int start = boundaries.get(index);
            int end = index + 1 < boundaries.size() ? boundaries.get(index + 1) : lines.size();
            if (start + 1 >= end) continue;
            String question = lines.get(start);
            String nestedAnswer = String.join("\n", lines.subList(start + 1, end)).trim();
            if (!nestedAnswer.isBlank()) result.add(new NestedQa(question, nestedAnswer));
        }
        return result.size() >= 2 ? List.copyOf(result) : List.of();
    }

    private static boolean isScriptLead(String outerQuestion, String firstAnswerLine) {
        return SCRIPT_LEAD_RE.matcher(outerQuestion == null ? "" : outerQuestion.trim()).matches()
            || SCRIPT_LEAD_RE.matcher(firstAnswerLine == null ? "" : firstAnswerLine.trim()).matches();
    }

    record NestedQa(String question, String answer) {}
}
