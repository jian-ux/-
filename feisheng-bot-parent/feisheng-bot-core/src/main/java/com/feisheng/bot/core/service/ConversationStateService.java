package com.feisheng.bot.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.core.entity.BotConversation;
import com.feisheng.bot.core.entity.BotMessage;
import com.feisheng.bot.core.service.IntentUnderstandingService.Route;
import com.feisheng.bot.core.service.IntentUnderstandingService.Understanding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ConversationStateService {
    private static final Logger log = LoggerFactory.getLogger(ConversationStateService.class);
    private static final int SCHEMA_VERSION = 1;
    private static final int ACTIVE_STATE_TURNS = 4;

    private final ConversationServiceImpl conversationService;
    private final ObjectMapper objectMapper;

    public ConversationStateService(ConversationServiceImpl conversationService,
                                    ObjectMapper objectMapper) {
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
    }

    public Snapshot load(BotConversation conversation, List<BotMessage> messages) {
        long version = conversation == null || conversation.getDialogStateVersion() == null
            ? 0L : Math.max(0L, conversation.getDialogStateVersion());
        if (conversation != null && hasText(conversation.getDialogState())) {
            try {
                return parse(objectMapper.readTree(conversation.getDialogState()), version);
            } catch (Exception e) {
                log.warn("Could not parse dialog state for conversation {}: {}",
                    conversation.getId(), e.getClass().getSimpleName());
            }
        }
        Snapshot legacy = legacyPending(messages, version);
        return legacy == null ? Snapshot.idle(version) : legacy;
    }

    public Map<String, Object> modelContext(Snapshot state) {
        if (state == null || state.status() == Status.IDLE
                || (state.status() == Status.ACTIVE && state.remainingTurns() <= 0)) {
            return Map.of();
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("status", state.status().name());
        context.put("active_intent", state.activeIntent());
        context.put("standalone_query", state.standaloneQuery());
        context.put("entities", state.entities());
        context.put("missing_slots", state.missingSlots());
        context.put("remaining_turns", state.remainingTurns());
        if (state.pending() != null) {
            context.put("pending_clarification", Map.of(
                "intent_code", state.pending().intentCode(),
                "missing_slot", state.pending().missingSlot(),
                "attempt", state.pending().attempt(),
                "max_attempts", state.pending().maxAttempts()));
        }
        return context;
    }

    public Map<String, Object> turnContext(Snapshot existing, MergeResult merge,
                                           String currentQuestion) {
        if (merge == null) return Map.of();
        if (merge.retainPending()) return modelContext(existing);

        Understanding understanding = merge.understanding();
        if (understanding == null || !understanding.knowledge()) return Map.of();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("status", Status.ACTIVE.name());
        if (hasText(understanding.intentCode())) {
            context.put("active_intent", understanding.intentCode());
        }
        context.put("standalone_query", hasText(understanding.standaloneQuery())
            ? understanding.standaloneQuery() : currentQuestion);
        if (!understanding.entities().isEmpty()) {
            context.put("entities", understanding.entities());
        }
        if (!understanding.missingSlots().isEmpty()) {
            context.put("missing_slots", understanding.missingSlots());
        }
        return context;
    }

    public MergeResult merge(Snapshot state, String currentQuestion,
                             Understanding suggestion,
                             NlpIntentClassifier.IntentAnalysis deterministicIntent) {
        Snapshot safeState = state == null ? Snapshot.idle(0L) : state;
        Understanding safeSuggestion = suggestion == null
            ? Understanding.notAttempted("service_unavailable") : suggestion;
        String businessSystem = businessSystem(currentQuestion);

        if ("SYSTEM_INTEGRATION".equals(safeState.activeIntent())
                && businessSystem != null
                && isIntegrationFollowUp(currentQuestion, businessSystem)) {
            Map<String, String> entities = new LinkedHashMap<>(safeState.entities());
            entities.put("business_system", businessSystem);
            return new MergeResult(syntheticKnowledge(
                safeSuggestion, "SYSTEM_INTEGRATION",
                integrationQuery(businessSystem), entities, true), false,
                "java_state_entity_replacement");
        }

        if (safeState.pending() != null && deterministicIntent != null
                && deterministicIntent.intentCode() != NlpIntentClassifier.IntentCode.UNKNOWN
                && !deterministicIntent.needsClarification()) {
            Map<String, String> entities = businessSystem == null
                ? Map.of() : Map.of("business_system", businessSystem);
            String standalone = hasText(deterministicIntent.retrievalQuery())
                ? deterministicIntent.retrievalQuery() : currentQuestion;
            return new MergeResult(syntheticKnowledge(
                safeSuggestion, deterministicIntent.intentCode().name(), standalone,
                entities, false), false, "java_deterministic_intent");
        }

        if (safeState.pending() == null) {
            if (safeSuggestion.knowledge() || safeSuggestion.outOfScope()) {
                return new MergeResult(safeSuggestion, false, "model_complete_intent");
            }
            return new MergeResult(safeSuggestion, false, "no_pending_state");
        }
        if (isInvalidClarificationReply(currentQuestion)) {
            return new MergeResult(safeSuggestion, true, "java_invalid_slot_reply");
        }
        if (safeSuggestion.knowledge() || safeSuggestion.outOfScope()) {
            return new MergeResult(safeSuggestion, false, "model_complete_intent");
        }
        if (looksLikeSlotReply(safeState.pending(), currentQuestion)) {
            return new MergeResult(safeSuggestion, true, "java_slot_candidate");
        }
        if (looksLikeIndependentQuestion(currentQuestion)) {
            return new MergeResult(safeSuggestion, false, "java_new_question_reset");
        }
        return new MergeResult(safeSuggestion, true, "pending_state_retained");
    }

    public void synchronizeResponse(Map<String, Object> response, String currentQuestion) {
        if (response == null || !(response.get("conversationId") instanceof Number idValue)) return;
        BotConversation conversation = conversationService.getById(idValue.longValue());
        if (conversation == null) return;
        Snapshot existing = load(conversation, List.of());
        Snapshot next = nextState(existing, response, currentQuestion);
        if (next == null || next.equalsIgnoringVersion(existing)) return;
        try {
            String json = objectMapper.writeValueAsString(next.toMap());
            if (!conversationService.updateDialogState(
                    conversation, json, existing.version())) {
                log.warn("Dialog state update conflict for conversation {}",
                    conversation.getId());
            }
        } catch (Exception e) {
            log.warn("Could not persist dialog state for conversation {}: {}",
                conversation.getId(), e.getClass().getSimpleName());
        }
    }

    private Snapshot nextState(Snapshot existing, Map<String, Object> response,
                               String currentQuestion) {
        Map<?, ?> semantic = map(response.get("intentUnderstanding"));
        Map<?, ?> deterministic = map(response.get("nlpIntent"));
        Map<?, ?> pendingMap = map(response.get("pendingClarification"));
        String semanticIntent = text(semantic, "intentCode");
        String deterministicIntent = text(deterministic, "intentCode");
        if ("HISTORY_RECALL".equals(semanticIntent)) {
            // Recalling history is informational and must not replace the
            // active business topic for the next customer turn.
            return existing;
        }
        String activeIntent = usefulIntent(semanticIntent)
            ? semanticIntent : usefulIntent(deterministicIntent)
            ? deterministicIntent : existing.activeIntent();
        String businessSystem = businessSystem(currentQuestion);
        Map<String, String> entities = semanticEntities(semantic);
        if (entities.isEmpty()) entities = new LinkedHashMap<>(existing.entities());
        if (businessSystem != null && "SYSTEM_INTEGRATION".equals(activeIntent)) {
            entities = new LinkedHashMap<>(entities);
            entities.put("business_system", businessSystem);
        }

        if (pendingMap != null) {
            PendingState pending = PendingState.fromMap(pendingMap, currentQuestion);
            List<String> missingSlots = pending == null
                ? List.of("context") : List.of(pending.missingSlot());
            return new Snapshot(Status.WAITING_FOR_SLOT, activeIntent, entities,
                missingSlots, currentQuestion, pending,
                pending == null ? 1 : pending.attempt(), 1, existing.version());
        }

        String answerDecision = value(response.get("answerDecision"));
        if ("HANDOFF".equals(answerDecision)
                || "handoff".equals(value(response.get("source")))) {
            return new Snapshot(Status.HANDOFF_PENDING, activeIntent, entities,
                List.of(), currentQuestion, null, 0, 0, existing.version());
        }

        String route = text(semantic, "route");
        if ("KNOWLEDGE".equals(route) || usefulIntent(deterministicIntent)) {
            String standalone = text(semantic, "standaloneQuery");
            if (!hasText(standalone)) standalone = currentQuestion;
            return new Snapshot(Status.ACTIVE, activeIntent, entities, List.of(),
                standalone, null, 0, ACTIVE_STATE_TURNS, existing.version());
        }
        if ("OUT_OF_SCOPE".equals(route)
                || (existing.pending() != null && looksLikeIndependentQuestion(currentQuestion))) {
            return Snapshot.idle(existing.version());
        }
        if (existing.status() == Status.ACTIVE) {
            int remaining = Math.max(0, existing.remainingTurns() - 1);
            return remaining == 0 ? Snapshot.idle(existing.version())
                : new Snapshot(Status.ACTIVE, existing.activeIntent(), existing.entities(),
                    List.of(), existing.standaloneQuery(), null, 0, remaining,
                    existing.version());
        }
        return existing;
    }

    private Understanding syntheticKnowledge(Understanding source, String intentCode,
                                             String standaloneQuery,
                                             Map<String, String> entities,
                                             boolean contextDependent) {
        return new Understanding(
            source.attempted(), true, Route.KNOWLEDGE, intentCode,
            standaloneQuery, entities, List.of(), contextDependent,
            Math.max(0.90, source.confidence()), "java_state_merge",
            source.model(), source.providerCode(), source.inputTokens(),
            source.outputTokens(), source.latencyMs());
    }

    private Snapshot parse(JsonNode root, long version) {
        if (root == null || !root.isObject()) return Snapshot.idle(version);
        Status status;
        try {
            status = Status.valueOf(root.path("status").asText("IDLE"));
        } catch (IllegalArgumentException e) {
            status = Status.IDLE;
        }
        Map<String, String> entities = new LinkedHashMap<>();
        JsonNode entityNode = root.path("entities");
        if (entityNode.isObject()) {
            entityNode.fields().forEachRemaining(entry -> {
                if (entry.getValue().isTextual() && hasText(entry.getValue().asText())) {
                    entities.put(entry.getKey(), entry.getValue().asText().trim());
                }
            });
        }
        List<String> missingSlots = new ArrayList<>();
        JsonNode slots = root.path("missingSlots");
        if (slots.isArray()) slots.forEach(slot -> {
            if (slot.isTextual() && hasText(slot.asText())) missingSlots.add(slot.asText());
        });
        PendingState pending = PendingState.fromJson(root.path("pending"));
        Snapshot parsed = new Snapshot(status, root.path("activeIntent").asText(""),
            entities, missingSlots, root.path("standaloneQuery").asText(""),
            pending, Math.max(0, root.path("clarificationAttempts").asInt(0)),
            Math.max(0, root.path("remainingTurns").asInt(0)), version);
        return parsed.status() == Status.ACTIVE && parsed.remainingTurns() <= 0
            ? Snapshot.idle(version) : parsed;
    }

    private Snapshot legacyPending(List<BotMessage> messages, long version) {
        if (messages == null) return null;
        for (int aiIndex = messages.size() - 1; aiIndex >= 0; aiIndex--) {
            BotMessage message = messages.get(aiIndex);
            if (message == null || !"ai".equals(message.getRole())
                    || !hasText(message.getMetadata())) continue;
            try {
                JsonNode pendingNode = objectMapper.readTree(message.getMetadata())
                    .path("pendingClarification");
                PendingState pending = PendingState.fromJson(pendingNode);
                if (pending == null) return null;
                String previousQuestion = previousUserQuestion(messages, aiIndex);
                pending = pending.withSourceQuestion(previousQuestion);
                return new Snapshot(Status.WAITING_FOR_SLOT, pending.intentCode(),
                    Map.of(), List.of(pending.missingSlot()), previousQuestion,
                    pending, pending.attempt(), 1, version);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private String previousUserQuestion(List<BotMessage> messages, int beforeIndex) {
        for (int index = beforeIndex - 1; index >= 0; index--) {
            BotMessage message = messages.get(index);
            if (message != null && "user".equals(message.getRole())
                    && hasText(message.getContent())) return message.getContent().trim();
        }
        return "";
    }

    private Map<String, String> semanticEntities(Map<?, ?> semantic) {
        Map<?, ?> values = semantic == null ? null : map(semantic.get("entities"));
        if (values == null) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key != null && value != null && hasText(value.toString())) {
                result.put(key.toString(), value.toString().trim());
            }
        });
        return result;
    }

    private String businessSystem(String question) {
        String normalized = normalize(question);
        if (normalized.contains("crm") || normalized.contains("客户管理系统")) return "CRM";
        if (normalized.contains("erp")) return "ERP";
        if (normalized.contains("hrm") || normalized.contains("人力资源系统")) return "HRM";
        if (normalized.contains("oa") || normalized.contains("办公系统")) return "OA";
        if (normalized.contains("采购系统")) return "采购系统";
        if (normalized.contains("销售系统")) return "销售系统";
        if (normalized.contains("自研系统") || normalized.contains("自主开发系统")) return "自研系统";
        return null;
    }

    private boolean isIntegrationFollowUp(String question, String businessSystem) {
        String normalized = normalize(question);
        return businessSystem != null && (normalized.contains("呢")
            || normalized.contains("可以用") || normalized.contains("能用")
            || normalized.contains("接入") || normalized.contains("集成")
            || normalized.contains("嵌入") || normalized.contains("对接"));
    }

    private String integrationQuery(String businessSystem) {
        return "点签电子签章是否支持通过API集成到" + businessSystem + "系统？";
    }

    private boolean looksLikeIndependentQuestion(String question) {
        String normalized = normalize(question);
        return normalized.contains("点签") || normalized.startsWith("你们")
            || normalized.contains("是什么") || normalized.contains("干什么")
            || normalized.contains("可以用") || normalized.contains("能用")
            || normalized.contains("接入") || normalized.contains("集成")
            || normalized.contains("嵌入") || normalized.contains("对接");
    }

    private boolean isInvalidClarificationReply(String question) {
        String normalized = normalize(question);
        return normalized.isBlank() || normalized.equals("不知道")
            || normalized.equals("还是不知道") || normalized.equals("不清楚")
            || normalized.equals("没看懂") || normalized.equals("都不是")
            || normalized.equals("我不知道") || normalized.equals("没有");
    }

    private boolean looksLikeSlotReply(PendingState pending, String question) {
        if (pending == null) return false;
        String normalized = normalize(question);
        return switch (pending.missingSlot()) {
            case "contractType" -> normalized.contains("合同");
            case "operation" -> containsAny(normalized,
                "发起", "创建", "上传", "签署", "签字", "盖章", "认证");
            case "accountAction" -> containsAny(normalized,
                "注册", "登录", "认证", "实名", "密码", "找回", "重置");
            case "draftingGoal" -> containsAny(normalized,
                "合同内容", "条款", "起草", "模板", "发起签署");
            case "contractFileState" -> containsAny(normalized,
                "发起签署", "纸质归档", "已签", "未签", "归档");
            case "userType" -> containsAny(normalized,
                "企业", "公司", "组织", "个人", "本人");
            case "errorMessage", "productVersion" -> !normalized.isBlank();
            default -> false;
        };
    }

    private boolean containsAny(String value, String... markers) {
        for (String marker : markers) {
            if (value.contains(marker)) return true;
        }
        return false;
    }

    private boolean usefulIntent(String value) {
        return hasText(value) && !"UNKNOWN".equals(value) && !"OUT_OF_SCOPE".equals(value)
            && !"OTHER_KNOWLEDGE".equals(value) && !"HISTORY_RECALL".equals(value);
    }

    private String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> values ? values : null;
    }

    private String text(Map<?, ?> values, String key) {
        return values == null ? "" : value(values.get(key));
    }

    private String value(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    public enum Status {
        IDLE,
        ACTIVE,
        WAITING_FOR_SLOT,
        HANDOFF_PENDING
    }

    public record PendingState(
            String intentCode, String missingSlot, String queryTemplate,
            String question, int attempt, int maxAttempts, String reasonCode,
            String sourceQuestion) {
        public PendingState {
            intentCode = intentCode == null ? "UNKNOWN" : intentCode;
            missingSlot = missingSlot == null ? "context" : missingSlot;
            queryTemplate = queryTemplate == null ? "{context}" : queryTemplate;
            question = question == null ? "" : question;
            attempt = Math.max(1, attempt);
            maxAttempts = Math.max(1, maxAttempts);
            reasonCode = reasonCode == null ? "missing_" + missingSlot : reasonCode;
            sourceQuestion = sourceQuestion == null ? "" : sourceQuestion;
        }

        public static PendingState fromMap(Map<?, ?> values, String sourceQuestion) {
            if (values == null || values.get("missingSlot") == null) return null;
            return new PendingState(
                string(values.get("intentCode"), "UNKNOWN"),
                string(values.get("missingSlot"), "context"),
                string(values.get("queryTemplate"), "{context}"),
                string(values.get("question"), ""),
                integer(values.get("attempt"), 1),
                integer(values.get("maxAttempts"), 2),
                string(values.get("reasonCode"), "missing_context"),
                sourceQuestion);
        }

        public static PendingState fromJson(JsonNode node) {
            if (node == null || !node.isObject()
                    || !node.path("missingSlot").isTextual()) return null;
            return new PendingState(
                node.path("intentCode").asText("UNKNOWN"),
                node.path("missingSlot").asText("context"),
                node.path("queryTemplate").asText("{context}"),
                node.path("question").asText(""),
                node.path("attempt").asInt(1), node.path("maxAttempts").asInt(2),
                node.path("reasonCode").asText("missing_context"),
                node.path("sourceQuestion").asText(""));
        }

        public PendingState withSourceQuestion(String value) {
            return new PendingState(intentCode, missingSlot, queryTemplate, question,
                attempt, maxAttempts, reasonCode, value);
        }

        public CustomerServiceDecisionEngine.ClarificationPlan toPlan() {
            return new CustomerServiceDecisionEngine.ClarificationPlan(
                intentCode, missingSlot, queryTemplate, question,
                attempt, maxAttempts, reasonCode);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("intentCode", intentCode);
            values.put("missingSlot", missingSlot);
            values.put("queryTemplate", queryTemplate);
            values.put("question", question);
            values.put("attempt", attempt);
            values.put("maxAttempts", maxAttempts);
            values.put("reasonCode", reasonCode);
            values.put("sourceQuestion", sourceQuestion);
            return values;
        }

        private static String string(Object value, String fallback) {
            return value == null || value.toString().isBlank() ? fallback : value.toString();
        }

        private static int integer(Object value, int fallback) {
            return value instanceof Number number ? number.intValue() : fallback;
        }
    }

    public record Snapshot(
            Status status, String activeIntent, Map<String, String> entities,
            List<String> missingSlots, String standaloneQuery, PendingState pending,
            int clarificationAttempts, int remainingTurns, long version) {
        public Snapshot {
            status = status == null ? Status.IDLE : status;
            activeIntent = activeIntent == null ? "" : activeIntent;
            entities = entities == null ? Map.of() : Map.copyOf(entities);
            missingSlots = missingSlots == null ? List.of() : List.copyOf(missingSlots);
            standaloneQuery = standaloneQuery == null ? "" : standaloneQuery;
            clarificationAttempts = Math.max(0, clarificationAttempts);
            remainingTurns = Math.max(0, remainingTurns);
            version = Math.max(0L, version);
        }

        public static Snapshot idle(long version) {
            return new Snapshot(Status.IDLE, "", Map.of(), List.of(), "",
                null, 0, 0, version);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("schemaVersion", SCHEMA_VERSION);
            values.put("status", status.name());
            values.put("activeIntent", activeIntent);
            values.put("entities", entities);
            values.put("missingSlots", missingSlots);
            values.put("standaloneQuery", standaloneQuery);
            values.put("pending", pending == null ? null : pending.toMap());
            values.put("clarificationAttempts", clarificationAttempts);
            values.put("remainingTurns", remainingTurns);
            return values;
        }

        public boolean equalsIgnoringVersion(Snapshot other) {
            return other != null && toMap().equals(other.toMap());
        }
    }

    public record MergeResult(
            Understanding understanding, boolean retainPending, String reasonCode) {}
}
