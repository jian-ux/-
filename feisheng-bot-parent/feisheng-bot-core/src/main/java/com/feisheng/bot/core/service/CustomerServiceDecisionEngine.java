package com.feisheng.bot.core.service;

import com.feisheng.bot.core.entity.BotMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes the customer-service routing decision before reply generation and
 * normalizes legacy reply states into the four public decisions.
 */
@Service
public class CustomerServiceDecisionEngine {
    private static final int DEFAULT_MAX_CLARIFICATION_ATTEMPTS = 2;
    private static final Pattern CONTRACT_TYPE = Pattern.compile(
        "([\\p{IsHan}A-Za-z0-9]{2,18}合同)");
    private static final Set<String> GENERIC_CONTRACT_TYPES = Set.of(
        "合同", "电子合同", "线上合同", "纸质合同", "具体合同", "合同类型");
    private static final List<String> EXPLICIT_ACCOUNT_ACTIONS = List.of(
        "注册", "开通", "申请账号", "创建账号", "登录", "认证", "实名",
        "密码", "重置", "找回", "注销", "修改手机号");
    private static final List<String> NEW_QUESTION_MARKERS = List.of(
        "怎么", "如何", "为什么", "能否", "是否", "可以吗", "多少钱",
        "流程", "材料", "登录", "发票", "合同", "价格", "认证");

    private final ObjectMapper objectMapper;

    public CustomerServiceDecisionEngine(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ClarificationPlan initialClarification(
            String question, NlpIntentClassifier.IntentAnalysis intent) {
        if (intent == null) return null;
        if (intent.intentCode() == NlpIntentClassifier.IntentCode.CONTRACT_TYPE_CAPABILITY
                && intent.needsClarification()
                && isBareContractCapabilityQuestion(question)) {
            return plan(intent.intentCode().name(), "contractType",
                "点签 是否支持签署 {contractType}",
                "请问您具体想签署哪一种合同，例如劳动合同、租赁合同或买卖合同？",
                "missing_contract_type");
        }
        if (intent.intentCode() == NlpIntentClassifier.IntentCode.ACCOUNT_OPERATION
                && isGenericAccountOperation(question)) {
            return plan(intent.intentCode().name(), "accountAction",
                "账号{accountAction}怎么操作",
                "请问您想咨询账号注册、登录、实名认证，还是密码找回？",
                "missing_account_action");
        }
        return null;
    }

    /**
     * Creates the same bounded clarification state for a low-confidence,
     * context-dependent question that has no specific slot to extract.
     */
    public ClarificationPlan genericClarification(String question) {
        String prompt = question == null || question.isBlank()
            ? defaultQuestion("context") : question.trim();
        return plan("UNKNOWN", "context", "{context}", prompt,
            "unresolved_reference");
    }

    public ClarificationPlan pendingClarification(
            NlpIntentClassifier.IntentAnalysis intent, String reply) {
        if (intent == null || reply == null || reply.isBlank()) return null;
        if (intent.intentCode() == NlpIntentClassifier.IntentCode.CONTRACT_TYPE_CAPABILITY
                && isContractTypeClarificationReply(reply)) {
            return plan(intent.intentCode().name(), "contractType",
                "点签 是否支持签署 {contractType}",
                "请问您具体想签署哪一种合同，例如劳动合同、租赁合同或买卖合同？",
                "missing_contract_type");
        }
        if (intent.intentCode() == NlpIntentClassifier.IntentCode.PRODUCT_USAGE
                && reply.contains("发起合同") && reply.contains("签署合同")
                && reply.contains("企业认证")) {
            return plan(intent.intentCode().name(), "operation",
                "点签 {operation} 怎么操作",
                "请问您具体想进行发起合同、签署合同，还是企业认证？",
                "missing_product_operation");
        }
        if (intent.intentCode() == NlpIntentClassifier.IntentCode.CONTRACT_DRAFTING
                && reply.contains("合同内容") && reply.contains("发起签署")) {
            return plan(intent.intentCode().name(), "draftingGoal",
                "{previousQuestion} {draftingGoal}",
                "请回复“合同内容”或“发起签署”，我会继续为您处理。",
                "missing_contract_drafting_goal");
        }
        if (intent.intentCode() == NlpIntentClassifier.IntentCode.CONTRACT_SIGNING_OPERATION
                && reply.contains("发起签署") && reply.contains("纸质归档")) {
            return plan(intent.intentCode().name(), "contractFileState",
                "{contractFileState}",
                "请回复“发起签署”或“纸质归档”，我会按对应场景说明。",
                "missing_contract_file_state");
        }
        return null;
    }

    public PendingResult resolvePending(
            List<BotMessage> messages, String currentQuestion) {
        int previousAiIndex = immediatelyPreviousAiIndex(messages, currentQuestion);
        if (previousAiIndex < 0 || isExplicitTopicReset(currentQuestion)) {
            return PendingResult.none();
        }
        BotMessage previousAi = messages.get(previousAiIndex);
        ClarificationPlan pending = readPending(previousAi);
        if (pending == null) return PendingResult.none();

        String previousQuestion = previousUserQuestionBefore(messages, previousAiIndex);
        String slotValue = resolveSlot(pending.missingSlot(), currentQuestion);
        if (slotValue != null) {
            if ("draftingGoal".equals(pending.missingSlot())
                    && "合同内容".equals(slotValue)) {
                return PendingResult.handoff(previousQuestion, pending,
                    "contract_drafting_requires_handoff");
            }
            String query = renderQuery(pending, previousQuestion, slotValue);
            return PendingResult.resolved(query, previousQuestion, pending);
        }
        boolean repeatedQuestion = previousQuestion != null
            && normalize(previousQuestion).equals(normalize(currentQuestion));
        boolean genericAmbiguous = "context".equals(pending.missingSlot())
            && isGenericAmbiguousReply(currentQuestion);
        if (looksLikeNewQuestion(currentQuestion) && !repeatedQuestion && !genericAmbiguous) {
            return PendingResult.none();
        }
        if (pending.attempt() >= pending.maxAttempts()) {
            return PendingResult.handoff(previousQuestion, pending,
                "clarification_exhausted");
        }
        return PendingResult.retry(previousQuestion, pending.nextAttempt());
    }

    public void enrich(Map<String, Object> response) {
        if (response == null) return;
        String answerDecision = text(response.get("answerDecision"));
        String answerStatus = text(response.get("answerStatus"));
        String source = text(response.get("source"));
        String fallbackDecision = text(response.get("fallbackDecision"));
        Map<?, ?> pending = map(response.get("pendingClarification"));

        Decision decision;
        if ("HANDOFF".equals(answerDecision) || response.containsKey("handoff")
                || "handoff".equals(source)) {
            decision = Decision.HANDOFF;
        } else if ("CLARIFY".equals(answerDecision) || pending != null
                || "clarify".equals(answerStatus) || fallbackDecision.contains("clarif")) {
            decision = Decision.CLARIFY;
        } else if ("NO_KNOWLEDGE".equals(answerDecision)
                || "no_answer".equals(answerStatus) || "error".equals(answerStatus)
                || "out_of_scope".equals(answerStatus)) {
            decision = Decision.NO_ANSWER;
        } else {
            decision = Decision.ANSWER;
        }

        String intentCode = nestedText(response.get("nlpIntent"), "intentCode");
        if (intentCode.isBlank() && pending != null) {
            intentCode = text(pending.get("intentCode"));
        }
        String missingSlot = pending == null ? "" : text(pending.get("missingSlot"));
        String reasonCode = !fallbackDecision.isBlank()
            && !"not_needed".equals(fallbackDecision) ? fallbackDecision : source;

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("decision", decision.name());
        details.put("reasonCode", reasonCode);
        details.put("intentCode", intentCode);
        details.put("missingSlots", missingSlot.isBlank()
            ? Collections.emptyList() : List.of(missingSlot));
        details.put("confidence", response.getOrDefault("confidence", 0.0));
        response.put("serviceDecision", details);
    }

    /**
     * Decides whether a generative model may be called for the current turn.
     * Deterministic FAQ, tool, safety, and clarification branches are handled
     * before this gate and therefore do not need model permission.
     */
    public GateResult beforeModelCall(
            NlpIntentClassifier.IntentAnalysis intent,
            boolean evidenceAvailable,
            boolean directAnswer,
            double confidence,
            double minimumConfidence,
            boolean outOfScope,
            boolean allowRestrictedFallback) {
        if (outOfScope) {
            return GateResult.block(Decision.NO_ANSWER, "out_of_scope");
        }
        if (directAnswer) {
            return GateResult.allow("direct_answer");
        }
        if (!evidenceAvailable && intent != null
                && intent.riskLevel() == NlpIntentClassifier.RiskLevel.HIGH) {
            return GateResult.block(Decision.HANDOFF, "high_risk_no_evidence");
        }
        if (!evidenceAvailable) {
            return allowRestrictedFallback
                ? GateResult.allow("restricted_fallback")
                : GateResult.block(Decision.NO_ANSWER, "no_reliable_evidence");
        }
        if (confidence < Math.max(0.0, minimumConfidence)) {
            return GateResult.block(Decision.HANDOFF, "low_confidence");
        }
        return GateResult.allow("evidence_available");
    }

    private ClarificationPlan plan(
            String intentCode, String missingSlot, String queryTemplate,
            String question, String reasonCode) {
        return new ClarificationPlan(intentCode, missingSlot, queryTemplate,
            question, 1, DEFAULT_MAX_CLARIFICATION_ATTEMPTS, reasonCode);
    }

    private ClarificationPlan readPending(BotMessage message) {
        if (message == null || message.getMetadata() == null
                || message.getMetadata().isBlank()) {
            return null;
        }
        try {
            JsonNode state = objectMapper.readTree(message.getMetadata())
                .path("pendingClarification");
            if (!state.isObject()) return null;
            String missingSlot = state.path("missingSlot").asText("");
            if (missingSlot.isBlank()) return null;
            return new ClarificationPlan(
                state.path("intentCode").asText("UNKNOWN"),
                missingSlot,
                state.path("queryTemplate").asText(defaultQueryTemplate(missingSlot)),
                state.path("question").asText(defaultQuestion(missingSlot)),
                Math.max(1, state.path("attempt").asInt(1)),
                Math.max(1, state.path("maxAttempts")
                    .asInt(DEFAULT_MAX_CLARIFICATION_ATTEMPTS)),
                state.path("reasonCode").asText("missing_" + missingSlot));
        } catch (Exception ignored) {
            return null;
        }
    }

    private int immediatelyPreviousAiIndex(
            List<BotMessage> messages, String currentQuestion) {
        if (messages == null || messages.isEmpty()) return -1;
        int start = messages.size() - 1;
        BotMessage last = messages.get(start);
        if (last != null && "user".equals(last.getRole())
                && normalize(last.getContent()).equals(normalize(currentQuestion))) {
            start--;
        }
        for (int index = start; index >= 0; index--) {
            BotMessage message = messages.get(index);
            if (message == null || message.getRole() == null) continue;
            if ("ai".equals(message.getRole())) return index;
            if ("user".equals(message.getRole())) return -1;
        }
        return -1;
    }

    private String previousUserQuestionBefore(List<BotMessage> messages, int beforeIndex) {
        for (int index = beforeIndex - 1; index >= 0; index--) {
            BotMessage message = messages.get(index);
            if (message != null && "user".equals(message.getRole())
                    && message.getContent() != null && !message.getContent().isBlank()) {
                return message.getContent().trim();
            }
        }
        return null;
    }

    private String resolveSlot(String missingSlot, String answer) {
        String normalized = normalize(answer);
        return switch (missingSlot) {
            case "contractType" -> contractType(answer);
            case "operation" -> containsAny(normalized, "企业认证", "公司认证")
                ? "企业认证" : containsAny(normalized, "发起", "创建合同", "上传合同")
                ? "发起合同" : containsAny(normalized, "签署", "签合同", "签字", "盖章")
                ? "签署合同" : null;
            case "accountAction" -> containsAny(normalized, "密码", "重置", "找回")
                ? "密码找回" : containsAny(normalized, "实名认证", "实名", "认证")
                ? "实名认证" : containsAny(normalized, "登录", "登不上")
                ? "登录" : containsAny(normalized, "注册", "开通", "申请账号", "创建账号")
                ? "注册" : null;
            case "draftingGoal" -> containsAny(normalized, "发起签署", "发起", "上传签署")
                ? "发起签署" : containsAny(normalized, "合同内容", "条款", "起草", "模板", "内容")
                ? "合同内容" : null;
            case "contractFileState" -> containsAny(normalized,
                "纸质归档", "已经签完", "已签", "线下签完", "归档")
                ? "已签纸质合同怎么上传归档？" : containsAny(normalized,
                "发起签署", "还没签", "未签", "上传签署")
                ? "已有合同文件怎么上传发起签署？" : null;
            default -> null;
        };
    }

    private String contractType(String value) {
        if (value == null) return null;
        String normalized = normalize(value);
        if (containsAny(normalized,
                "能不能", "能签", "可以吗", "是否", "支持什么", "支持哪些")) {
            return null;
        }
        Matcher matcher = CONTRACT_TYPE.matcher(normalized);
        while (matcher.find()) {
            String candidate = matcher.group(1).replaceFirst(
                "^(?:我(?:想|要)?(?:签署|签约|签)?|"
                    + "(?:想|要)(?:签署|签约|签)?|(?:签署|签约|签)|就是|是)", "");
            if (!GENERIC_CONTRACT_TYPES.contains(candidate)) return candidate;
        }
        return null;
    }

    private String renderQuery(
            ClarificationPlan pending, String previousQuestion, String slotValue) {
        String template = pending.queryTemplate();
        if (template == null || template.isBlank()) return slotValue;
        return template
            .replace("{previousQuestion}", previousQuestion == null ? "" : previousQuestion)
            .replace("{" + pending.missingSlot() + "}", slotValue)
            .trim().replaceAll("\\s+", " ");
    }

    private boolean isGenericAccountOperation(String question) {
        String normalized = normalize(question);
        return EXPLICIT_ACCOUNT_ACTIONS.stream().noneMatch(normalized::contains);
    }

    private boolean isBareContractCapabilityQuestion(String question) {
        String normalized = normalize(question);
        return !containsAny(normalized,
            "多少", "几种", "几份", "数量", "批量", "同时", "上限", "最多",
            "这个", "那个", "这种", "那种", "该合同", "上述");
    }

    private boolean isContractTypeClarificationReply(String reply) {
        if (!reply.contains("？") && !reply.contains("?")) return false;
        return reply.contains("合同") && containsAny(reply,
            "什么合同", "哪种合同", "哪类合同", "哪一类合同", "什么类型的合同",
            "商品房买卖合同", "二手房买卖合同");
    }

    private boolean looksLikeNewQuestion(String question) {
        String normalized = normalize(question);
        if (normalized.isBlank() || Set.of("不知道", "不清楚", "没看懂", "都不是")
                .contains(normalized)) {
            return false;
        }
        return NEW_QUESTION_MARKERS.stream().anyMatch(normalized::contains);
    }

    private boolean isGenericAmbiguousReply(String question) {
        String normalized = normalize(question);
        return normalized.length() <= 18 && containsAny(normalized,
            "这个", "那个", "它", "这款", "那款", "然后呢", "怎么操作", "如何操作");
    }

    private boolean isExplicitTopicReset(String question) {
        String normalized = normalize(question);
        return containsAny(normalized,
            "换个问题", "换个话题", "另一个问题", "另外问", "顺便问", "再问一个");
    }

    private String defaultQueryTemplate(String missingSlot) {
        return switch (missingSlot) {
            case "contractType" -> "点签 是否支持签署 {contractType}";
            case "operation" -> "点签 {operation} 怎么操作";
            case "accountAction" -> "账号{accountAction}怎么操作";
            case "draftingGoal" -> "{previousQuestion} {draftingGoal}";
            case "contractFileState" -> "{contractFileState}";
            default -> "{" + missingSlot + "}";
        };
    }

    private String defaultQuestion(String missingSlot) {
        return switch (missingSlot) {
            case "contractType" -> "请问您具体想签署哪一种合同？";
            case "operation" -> "请问您想进行发起合同、签署合同，还是企业认证？";
            case "accountAction" -> "请问您想咨询账号注册、登录、实名认证，还是密码找回？";
            case "draftingGoal" -> "请回复“合同内容”或“发起签署”，我会继续为您处理。";
            case "contractFileState" -> "请回复“发起签署”或“纸质归档”，我会按对应场景说明。";
            case "context" -> "请补充具体的产品、功能或使用场景；如有页面提示或截图，也可以一并发送。";
            default -> "请补充处理该问题所需的关键信息。";
        };
    }

    private String nestedText(Object value, String key) {
        Map<?, ?> values = map(value);
        return values == null ? "" : text(values.get(key));
    }

    private Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> values ? values : null;
    }

    private String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private boolean containsAny(String value, String... markers) {
        if (value == null) return false;
        for (String marker : markers) {
            if (value.contains(marker)) return true;
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT)
            .replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }

    public enum Decision {
        ANSWER,
        CLARIFY,
        NO_ANSWER,
        HANDOFF
    }

    public enum PendingStatus {
        NONE,
        RESOLVED,
        RETRY,
        HANDOFF
    }

    public record GateResult(boolean modelAllowed, Decision decision, String reasonCode) {
        public static GateResult allow(String reasonCode) {
            return new GateResult(true, Decision.ANSWER, reasonCode);
        }

        public static GateResult block(Decision decision, String reasonCode) {
            return new GateResult(false, decision, reasonCode);
        }
    }

    public record ClarificationPlan(
            String intentCode, String missingSlot, String queryTemplate,
            String question, int attempt, int maxAttempts, String reasonCode) {
        public ClarificationPlan nextAttempt() {
            return new ClarificationPlan(intentCode, missingSlot, queryTemplate,
                question, attempt + 1, maxAttempts, reasonCode);
        }

        public Map<String, Object> toState() {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("intentCode", intentCode);
            state.put("missingSlot", missingSlot);
            state.put("queryTemplate", queryTemplate);
            state.put("question", question);
            state.put("attempt", attempt);
            state.put("maxAttempts", maxAttempts);
            state.put("expiresAfterTurns", 1);
            state.put("reasonCode", reasonCode);
            return state;
        }
    }

    public record PendingResult(
            PendingStatus status, String resolvedQuery, String previousQuestion,
            ClarificationPlan clarification, String reasonCode) {
        public static PendingResult none() {
            return new PendingResult(PendingStatus.NONE, null, null, null, null);
        }

        public static PendingResult resolved(
                String query, String previousQuestion, ClarificationPlan clarification) {
            return new PendingResult(PendingStatus.RESOLVED, query, previousQuestion,
                clarification, "clarification_resolved");
        }

        public static PendingResult retry(
                String previousQuestion, ClarificationPlan clarification) {
            return new PendingResult(PendingStatus.RETRY, null, previousQuestion,
                clarification, clarification.reasonCode());
        }

        public static PendingResult handoff(
                String previousQuestion, ClarificationPlan clarification,
                String reasonCode) {
            return new PendingResult(PendingStatus.HANDOFF, null, previousQuestion,
                clarification, reasonCode);
        }
    }
}
