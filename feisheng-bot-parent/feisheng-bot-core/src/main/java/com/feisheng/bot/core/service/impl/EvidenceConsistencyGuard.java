package com.feisheng.bot.core.service.impl;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Applies deterministic checks where prompt-only grounding is not sufficient. */
final class EvidenceConsistencyGuard {
    private static final Pattern PRICE_QUERY = Pattern.compile(
        "多少钱|价格|价钱|收费|费用|报价|单价|价位");
    private static final Pattern UNIT_PRICE_FLOOR = Pattern.compile(
        "(?:单价|单(?:份|次|个)|每(?:份|次|个))[^0-9。；\\n]{0,12}"
            + "(?:最低(?:仅需)?|低至)[^0-9]{0,6}"
            + "([0-9]+(?:\\.[0-9]+)?)\\s*元",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern REVERSED_UNIT_PRICE_FLOOR = Pattern.compile(
        "(?:最低|低至)[^0-9。；\\n]{0,8}(?:单价|每(?:份|次|个)(?:价格|费用)?)"
            + "[^0-9]{0,6}([0-9]+(?:\\.[0-9]+)?)\\s*元",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern MATERIAL_LIST_QUERY = Pattern.compile(
        "(?:需要|要|应当|应该|准备|提交|提供|上传).{0,8}(?:材料|资料|证件|手续|文件)"
            + "|(?:材料|资料|证件|手续|文件).{0,8}(?:哪些|什么|清单|列表)");
    private static final Pattern PROCEDURAL_QUERY = Pattern.compile(
        "(?:怎么|如何|怎样).{0,12}(?:使用|操作|办理|处理|发起|签署|签约|认证|"
            + "登录|注册|上传|下载|配置|设置|修改|撤回|查看|管理)"
            + "|(?:使用|操作|办理|处理|发起|签署|签约|认证|登录|注册|上传|下载|"
            + "配置|设置|修改|撤回|查看|管理).{0,8}(?:步骤|流程|方法)");
    private static final Pattern ENUMERATED_ITEM = Pattern.compile(
        "(?m)^\\s*(?:[0-9]{1,2}[.、)）]|[-*])\\s*(.+?)\\s*$");
    private static final Pattern STANDALONE_APP_UNAVAILABLE = Pattern.compile(
        "(?:不提供|不支持|没有|暂无)(?:独立)?(?:手机|移动端)?app"
            + "|(?:独立)?(?:手机|移动端)?app(?:暂不提供|不提供|不支持|没有)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern STANDALONE_APP_INSTALL_CLAIM = Pattern.compile(
        "(?:应用商店|下载安装|下载|安装).{0,18}(?:点签)?app"
            + "|(?:点签|手机|移动端)app.{0,12}(?:下载|安装|登录|使用)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern DIRECT_OPERATION_QUERY = Pattern.compile(
        "能否|是否(?:还能|可以|能够)|还能|还可以|可不可以|能不能|可以.{0,12}[吗么]"
            + "|能.{0,12}[吗么]|为什么.{0,12}(?:不能|无法|不可|不支持|不允许)"
            + "|(?:不能|无法|不可|不支持|不允许).{0,16}(?:吗|么|呢|原因|怎么回事)");
    private static final Pattern NEGATIVE_DIRECT_BOUNDARY = Pattern.compile(
        "(?:不能|无法|不可|不支持|不允许).{0,12}直接"
            + "|直接.{0,12}(?:不能|无法|不可|不支持|不允许)");
    private static final Pattern POSITIVE_OPERATION = Pattern.compile(
        "可以|能够|支持|允许|(?:还)?能(?:补|改|加|传|上传|追加|修改|删除|撤回|操作)");
    private static final Pattern NEGATIVE_OPERATION = Pattern.compile(
        "不可以|不能|无法|不可|不支持|不允许");
    private static final Pattern ALTERNATIVE_PATH = Pattern.compile(
        "先.{0,6}(?:撤回|取消)|撤回后|重新发起|重新发送|重发|补充协议|另行");
    private static final Pattern SENTENCE = Pattern.compile("([^。！？!?\\n]+)([。！？!?]|$)");

    private EvidenceConsistencyGuard() {}

    static boolean hasConflictingScalarFacts(String question, String evidence) {
        String normalizedQuestion = normalize(question);
        if (!PRICE_QUERY.matcher(normalizedQuestion).find()) return false;

        Set<String> unitPriceFloors = new LinkedHashSet<>();
        collectValues(UNIT_PRICE_FLOOR, evidence, unitPriceFloors);
        collectValues(REVERSED_UNIT_PRICE_FLOOR, evidence, unitPriceFloors);
        return unitPriceFloors.size() > 1;
    }

    static boolean hasUnsupportedEnumeratedFacts(
            String question, String evidence, String reply) {
        if (!MATERIAL_LIST_QUERY.matcher(normalize(question)).find()) return false;

        List<String> items = enumeratedItems(reply);
        if (items.size() < 3) return false;

        String normalizedEvidence = compact(evidence);
        int unsupported = 0;
        int comparable = 0;
        for (String item : items) {
            String core = materialCore(item);
            if (core.length() < 2) continue;
            comparable++;
            if (!normalizedEvidence.contains(core)) unsupported++;
        }
        return comparable >= 3 && unsupported >= 2 && unsupported * 2 > comparable;
    }

    static boolean hasUnsupportedProceduralSteps(
            String question, String evidence, String reply) {
        if (!PROCEDURAL_QUERY.matcher(compact(question)).find()) return false;

        List<String> steps = enumeratedItems(reply);
        if (steps.size() < 3) return false;

        String normalizedEvidence = compact(evidence);
        int unsupported = 0;
        int comparable = 0;
        for (String step : steps) {
            String core = proceduralCore(step);
            if (core.length() < 4) continue;
            comparable++;
            if (!hasSupportedFragment(core, normalizedEvidence)) unsupported++;
        }
        return comparable >= 3 && unsupported >= 2 && unsupported * 2 > comparable;
    }

    static boolean contradictsStandaloneAppBoundary(String evidence, String reply) {
        String normalizedReply = compact(reply);
        return STANDALONE_APP_UNAVAILABLE.matcher(compact(evidence)).find()
            && !STANDALONE_APP_UNAVAILABLE.matcher(normalizedReply).find()
            && STANDALONE_APP_INSTALL_CLAIM.matcher(normalizedReply).find();
    }

    static boolean contradictsNegativeBoundary(
            String question, String evidence, String reply) {
        String normalizedQuestion = normalize(question);
        if (!DIRECT_OPERATION_QUERY.matcher(normalizedQuestion).find()) return false;
        if (!NEGATIVE_DIRECT_BOUNDARY.matcher(compact(evidence)).find()) return false;

        String opening = firstDeclarativeSentence(reply);
        if (opening.isEmpty() || NEGATIVE_OPERATION.matcher(opening).find()) return false;
        if (ALTERNATIVE_PATH.matcher(opening).find()) return false;
        return POSITIVE_OPERATION.matcher(opening).find();
    }

    static String repairNegativeBoundary(String reply) {
        if (reply == null || reply.isBlank()) return "不能直接这样操作。";
        Matcher matcher = SENTENCE.matcher(reply.strip());
        while (matcher.find()) {
            String sentence = matcher.group(1).strip();
            String punctuation = matcher.group(2);
            if (sentence.isEmpty() || "？".equals(punctuation) || "?".equals(punctuation)) {
                continue;
            }
            String remainder = reply.strip().substring(matcher.end()).stripLeading();
            return remainder.isEmpty()
                ? "不能直接这样操作。"
                : "不能直接这样操作。\n\n" + remainder;
        }
        return "不能直接这样操作。";
    }

    private static void collectValues(
            Pattern pattern, String evidence, Set<String> values) {
        Matcher matcher = pattern.matcher(evidence == null ? "" : evidence);
        while (matcher.find()) values.add(canonicalNumber(matcher.group(1)));
    }

    private static String canonicalNumber(String value) {
        if (value == null) return "";
        try {
            return new java.math.BigDecimal(value).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    private static List<String> enumeratedItems(String reply) {
        List<String> result = new ArrayList<>();
        Matcher matcher = ENUMERATED_ITEM.matcher(reply == null ? "" : reply);
        while (matcher.find()) result.add(matcher.group(1));
        return result;
    }

    private static String materialCore(String item) {
        String core = compact(item)
            .replaceAll("（[^）]*）|\\([^)]*\\)", "")
            .replaceAll("^(?:请)?(?:准备|提交|提供|上传)", "")
            .replaceAll("^(?:企业|个人|申请人|用户)", "")
            .replaceAll("(?:正反面|副本|原件|复印件|扫描件|电子版|纸质版|加盖公章)", "")
            .replaceAll("(?:一份|一张|一套)$", "")
            .replaceAll("[，。；;：:]+$", "");
        return core;
    }

    private static String proceduralCore(String item) {
        return compact(item)
            .replaceAll("^(?:请|先|然后|再|接着|最后)", "")
            .replaceAll("(?:即可|就可以|完成操作)$", "");
    }

    private static boolean hasSupportedFragment(String step, String evidence) {
        int maxLength = Math.min(10, step.length());
        for (int length = maxLength; length >= 4; length--) {
            for (int start = 0; start + length <= step.length(); start++) {
                String fragment = step.substring(start, start + length);
                if (evidence.contains(fragment)) return true;
            }
        }
        return false;
    }

    private static String firstDeclarativeSentence(String reply) {
        Matcher matcher = SENTENCE.matcher(reply == null ? "" : reply.strip());
        while (matcher.find()) {
            String sentence = compact(matcher.group(1));
            String punctuation = matcher.group(2);
            if (sentence.isEmpty() || "？".equals(punctuation) || "?".equals(punctuation)) {
                continue;
            }
            return sentence;
        }
        return "";
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT);
    }

    private static String compact(String value) {
        return normalize(value)
            .replaceAll("[\\s，。！？?、；;：:（）()【】\\[\\]“”\"']+", "");
    }
}
