package com.feisheng.bot.core.service.impl;

import com.feisheng.bot.core.entity.BotMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves short follow-up messages into standalone retrieval queries. */
@Component
public class ContextualQueryResolver {
    private static final int MAX_ACTIVE_PRODUCT_MESSAGES = 6;
    private static final List<String> REFERENCE_MARKERS = List.of(
        "这个", "那个", "这款", "那款", "它", "该产品", "上述", "前面", "刚才", "这种", "那种");
    private static final List<String> SUBJECTLESS_FOLLOW_UPS = List.of(
        "包邮吗", "包不包邮", "有货吗", "现货吗", "多少钱", "多久", "几天能到",
        "怎么弄", "怎么办", "可以吗", "行吗", "能退吗", "支持吗", "还有吗", "然后呢");
    private static final List<String> FOLLOW_UP_PREFIXES = List.of("那", "那么", "然后");
    private static final List<String> BUSINESS_SUBJECTS = List.of(
        "管理员", "企业", "公司", "单位", "机构", "组织", "个人", "员工", "用户", "法人");
    private static final List<String> ACCESS_CHANNELS = List.of(
        "企业微信", "微信端", "PC端", "电脑端", "网页端", "手机端", "移动端",
        "小程序", "钉钉", "微信", "手机", "APP", "API");
    private static final List<List<String>> SWITCH_DIMENSIONS = List.of(
        BUSINESS_SUBJECTS, ACCESS_CHANNELS);
    private static final List<String> FOLLOW_UP_SUFFIXES = List.of(
        "的呢", "呢", "怎么办", "怎么操作", "可以吗", "行吗", "能用吗", "怎么用");
    private static final List<String> OPERATION_TERMS = List.of(
        "登录", "登陆", "注册", "认证", "操作", "使用", "发起", "签署", "盖章",
        "下载", "查看", "查询", "续费", "购买", "开通", "管理", "配置", "设置",
        "修改", "解绑", "注销", "切换");
    private static final Pattern PRODUCT_ATTRIBUTE_FOLLOW_UP = Pattern.compile(
        "^(?:(?:主要)?有(?:什么|哪些)(?:功能|作用|能力|优势|特点|用途)|"
            + "(?:主要)?(?:功能|作用|能力|优势|特点|用途)(?:是什么|有哪些|呢)|"
            + "(?:可以|能|支持)?(?:在)?(?:哪里|哪儿|哪些地方)(?:可以)?使用|"
            + "(?:可以|能)?在哪(?:里|儿)?使用|"
            + "(?:支持|可以用)(?:哪些|什么)(?:方式|平台|渠道|终端|端))$");
    private static final List<String> BUSINESS_OBJECTS = List.of(
        "企业", "公司", "单位", "个人", "员工", "管理员", "用户", "账号", "账户",
        "合同", "签名", "签章", "印章", "文件", "数据", "套餐", "会员");
    private static final List<String> ACTIVE_PRODUCT_MARKERS = List.of(
        "点签电子合同", "点签平台", "点签");
    private static final List<String> OTHER_PRODUCT_MARKERS = List.of(
        "CA锁", "实体锁", "UKey", "U-Key", "安全控件", "守信签", "翔晟电子签章");
    private static final List<String> TOPIC_RESET_MARKERS = List.of(
        "换个问题", "另外一个问题", "另一个问题", "不说这个", "聊点别的");
    private static final Pattern LEADING_FIRST_PERSON = Pattern.compile(
        "^(?:请问|麻烦问一下|想问一下|咨询一下)?(?:我们|我)?(?:想要|需要|想|要)?");

    public Resolution resolve(List<BotMessage> messages, String currentQuestion) {
        String question = currentQuestion == null ? "" : currentQuestion.trim();
        boolean contextDependent = isContextDependent(question);
        String activeProduct = activeProduct(messages, question);
        boolean inheritProduct = activeProduct != null && shouldInheritProduct(question);
        if (!contextDependent && !inheritProduct) {
            return new Resolution(question, false, null, null, null);
        }

        String previousQuestion = previousUserQuestion(messages, question);
        String switchEntity = switchEntity(question);
        String resolved = switchEntity == null || previousQuestion == null
            ? question
            : rewriteSubject(previousQuestion, switchEntity);
        if (inheritProduct && !containsAny(resolved, ACTIVE_PRODUCT_MARKERS)) {
            resolved = activeProduct + " " + resolved;
        }
        return new Resolution(resolved, true, previousQuestion, switchEntity,
            inheritProduct ? activeProduct : null);
    }

    boolean isContextDependent(String question) {
        if (question == null || question.isBlank()) return false;
        String normalized = normalize(question);
        if (REFERENCE_MARKERS.stream().anyMatch(normalized::contains)) return true;
        if (SUBJECTLESS_FOLLOW_UPS.contains(normalized)) return true;
        if (normalized.length() <= 24
                && SUBJECTLESS_FOLLOW_UPS.stream().anyMatch(normalized::endsWith)) {
            return true;
        }
        if (normalized.length() <= 24
                && FOLLOW_UP_PREFIXES.stream().anyMatch(normalized::startsWith)) {
            return true;
        }
        if (switchEntity(normalized) != null) return true;
        return normalized.length() <= 12 && normalized.endsWith("呢");
    }

    private String previousUserQuestion(List<BotMessage> messages, String currentQuestion) {
        if (messages == null || messages.isEmpty()) return null;
        boolean skippedCurrent = false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            BotMessage message = messages.get(i);
            if (message == null || !"user".equals(message.getRole())
                    || message.getContent() == null || message.getContent().isBlank()) {
                continue;
            }
            String content = message.getContent().trim();
            if (!skippedCurrent && normalize(content).equals(normalize(currentQuestion))) {
                skippedCurrent = true;
                continue;
            }
            return content;
        }
        return null;
    }

    private String switchEntity(String question) {
        String normalized = normalize(question);
        String withoutPrefix = normalized
            .replaceFirst("^(?:那如果是|如果是|那么|那)", "");
        for (List<String> dimension : SWITCH_DIMENSIONS) {
            for (String entity : dimension) {
                for (String suffix : FOLLOW_UP_SUFFIXES) {
                    if ((entity + suffix).equalsIgnoreCase(withoutPrefix)) return entity;
                }
            }
        }
        return null;
    }

    private String activeProduct(List<BotMessage> messages, String currentQuestion) {
        if (messages == null || messages.isEmpty()) return null;
        boolean skippedCurrent = false;
        int inspectedMessages = 0;
        for (int i = messages.size() - 1;
                i >= 0 && inspectedMessages < MAX_ACTIVE_PRODUCT_MESSAGES; i--) {
            BotMessage message = messages.get(i);
            if (message == null || message.getContent() == null || message.getContent().isBlank()
                    || (!"user".equals(message.getRole()) && !"ai".equals(message.getRole()))) {
                continue;
            }
            String content = message.getContent().trim();
            if (!skippedCurrent && "user".equals(message.getRole())
                    && normalize(content).equals(normalize(currentQuestion))) {
                skippedCurrent = true;
                continue;
            }
            inspectedMessages++;
            if (containsAny(content, TOPIC_RESET_MARKERS)
                    || containsAny(content, OTHER_PRODUCT_MARKERS)) return null;
            if (containsAny(content, ACTIVE_PRODUCT_MARKERS)) return "点签电子合同";
        }
        return null;
    }

    private boolean shouldInheritProduct(String question) {
        String normalized = normalize(question);
        if (normalized.isBlank() || normalized.length() > 24
                || containsAny(normalized, ACTIVE_PRODUCT_MARKERS)
                || containsAny(normalized, OTHER_PRODUCT_MARKERS)) {
            return false;
        }
        if (PRODUCT_ATTRIBUTE_FOLLOW_UP.matcher(normalized).matches()) return true;
        boolean operation = containsAny(normalized, OPERATION_TERMS);
        boolean businessObject = containsAny(normalized, BUSINESS_OBJECTS);
        if (operation && businessObject) return true;
        return operation && (normalized.matches("^(?:怎么|如何|从哪|在哪里|去哪).+")
            || normalized.endsWith("不了怎么办") || normalized.endsWith("不了"));
    }

    private String rewriteSubject(String previousQuestion, String newEntity) {
        String previous = previousQuestion.trim();
        List<String> dimension = dimensionOf(newEntity);
        for (String oldEntity : dimension) {
            if (containsIgnoreCase(previous, oldEntity)) {
                return previous.replaceFirst("(?i)" + Pattern.quote(oldEntity),
                    Matcher.quoteReplacement(newEntity));
            }
        }

        String base = LEADING_FIRST_PERSON.matcher(previous).replaceFirst("")
            .replaceFirst("^[，,。.!！?？、\\s]+", "")
            .trim();
        if (base.isBlank()) return newEntity + "怎么使用？";
        return newEntity + base;
    }

    private List<String> dimensionOf(String entity) {
        return SWITCH_DIMENSIONS.stream()
            .filter(values -> values.stream().anyMatch(value -> value.equalsIgnoreCase(entity)))
            .findFirst()
            .orElse(List.of());
    }

    private boolean containsIgnoreCase(String value, String expected) {
        return value.toLowerCase().contains(expected.toLowerCase());
    }

    private boolean containsAny(String value, List<String> expectedValues) {
        return expectedValues.stream().anyMatch(expected -> containsIgnoreCase(value, expected));
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase()
            .replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }

    public record Resolution(String query, boolean contextDependent,
                             String previousQuestion, String switchedEntity,
                             String inheritedProduct) {
        public boolean rewritten() {
            return (previousQuestion != null && switchedEntity != null)
                || inheritedProduct != null;
        }
    }
}
