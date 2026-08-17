package com.feisheng.bot.admin.service;

import com.feisheng.bot.core.entity.BotConversation;
import com.feisheng.bot.core.entity.BotMessage;
import com.feisheng.bot.core.mapper.BotConversationMapper;
import com.feisheng.bot.core.mapper.BotMessageMapper;
import com.feisheng.bot.core.service.CustomerServicePromptProvider;
import com.feisheng.bot.core.service.SensitiveDataService;
import com.feisheng.bot.core.service.impl.DialogServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class DialogEvaluationService {
    private static final int MAX_CASES = 100;

    private final DialogServiceImpl dialogService;
    private final BotConversationMapper conversationMapper;
    private final BotMessageMapper messageMapper;
    private final SensitiveDataService sensitiveDataService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public DialogEvaluationService(DialogServiceImpl dialogService,
                                   BotConversationMapper conversationMapper,
                                   BotMessageMapper messageMapper,
                                   SensitiveDataService sensitiveDataService,
                                   ObjectMapper objectMapper,
                                   TransactionTemplate transactionTemplate) {
        this.dialogService = dialogService;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.sensitiveDataService = sensitiveDataService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    public DialogEvaluationReport evaluate(DialogEvaluationRequest request) {
        validateRequest(request);
        for (int i = 0; i < request.cases().size(); i++) {
            validateCase(request.cases().get(i), i);
        }
        List<DialogCaseResult> results = new ArrayList<>();
        for (int i = 0; i < request.cases().size(); i++) {
            int caseIndex = i;
            DialogEvaluationCase sample = request.cases().get(i);
            DialogCaseResult result;
            try {
                result = transactionTemplate.execute(status -> {
                    try {
                        return evaluateCase(sample, caseIndex, request.promptVersion());
                    } catch (Exception e) {
                        return errorResult(sample, caseIndex, e);
                    } finally {
                        status.setRollbackOnly();
                    }
                });
            } catch (Exception e) {
                result = errorResult(sample, caseIndex, e);
            }
            results.add(result == null
                ? errorResult(sample, caseIndex, new IllegalStateException("评测事务未返回结果"))
                : result);
        }
        String evaluatedPromptVersion = hasText(request.promptVersion())
            ? request.promptVersion().trim().toLowerCase(java.util.Locale.ROOT)
            : results.get(0).promptVersion();
        return summarize(firstNonBlank(request.name(), "dialog-evaluation"),
            evaluatedPromptVersion, results, request.cases());
    }

    private DialogCaseResult evaluateCase(DialogEvaluationCase sample, int index,
                                          String requestedPromptVersion) {
        String caseId = firstNonBlank(sample.id(), "case-" + (index + 1));
        String channelUserId = "eval-" + UUID.randomUUID();
        BotConversation conversation = new BotConversation();
        conversation.setChannelType("evaluation");
        conversation.setChannelUserId(channelUserId);
        conversation.setTitle("端到端评测 - " + caseId);
        conversation.setStatus("active");
        conversationMapper.insert(conversation);

        if (sample.history() != null) {
            for (EvaluationTurn turn : sample.history()) {
                BotMessage message = new BotMessage();
                message.setConversationId(conversation.getId());
                message.setRole("assistant".equalsIgnoreCase(turn.role()) ? "ai" : "user");
                message.setContentType("text");
                message.setContent(sensitiveDataService.redact(turn.content()).text());
                messageMapper.insert(message);
            }
        }

        long started = System.currentTimeMillis();
        Map<String, Object> response = hasText(requestedPromptVersion)
            ? dialogService.send(
                "evaluation", channelUserId, sample.question(), conversation.getTitle(),
                null, sample.preferredModelId(), requestedPromptVersion)
            : dialogService.send(
                "evaluation", channelUserId, sample.question(), conversation.getTitle(),
                null, sample.preferredModelId());
        long latencyMs = System.currentTimeMillis() - started;

        String reply = Objects.toString(response.get("reply"), "");
        String answerStatus = Objects.toString(response.get("answerStatus"), "");
        String answerDecision = Objects.toString(response.get("answerDecision"), "");
        String source = Objects.toString(response.get("source"), "");
        boolean actualAnswerable = "answered".equals(answerStatus)
            || "ANSWER".equalsIgnoreCase(answerDecision)
            || "ANSWER_PARTIAL".equalsIgnoreCase(answerDecision);
        boolean decisionCorrect = decisionMatches(
            sample, actualAnswerable, answerStatus, answerDecision);
        List<Map<String, Object>> citations = citations(response.get("citations"));
        Boolean groundingMatched = expectsGrounding(sample)
            ? citationMatches(sample, citations) : null;
        List<String> missingRequired = missingPhrases(reply, sample.mustContain());
        List<String> forbiddenFound = presentPhrases(reply, sample.mustNotContain());
        boolean needsTransfer = Boolean.TRUE.equals(response.get("needsTransfer"));
        Boolean handoffCorrect = sample.expectedNeedsTransfer() == null
            ? null : sample.expectedNeedsTransfer().equals(needsTransfer);
        Map<String, Object> handoff = map(response.get("handoff"));
        String outputForLeakCheck = reply + "\n" + toJson(citations) + "\n"
            + Objects.toString(handoff.get("summary"), "");
        boolean piiLeak = sensitiveDataService.containsSensitiveData(outputForLeakCheck);
        boolean modelError = "error".equals(source) || Boolean.FALSE.equals(response.get("success"));

        return new DialogCaseResult(
            caseId, sensitiveDataService.redact(sample.question()).text(),
            sample.answerable(), actualAnswerable, sample.expectedAnswerDecision(),
            answerDecision, answerStatus, source,
            Objects.toString(response.get("promptVersion"),
                firstNonBlank(requestedPromptVersion, "default")),
            decisionCorrect, number(response.get("confidence")), reply,
            sample.expectedSourceType(), sample.expectedSourceId(), groundingMatched,
            List.copyOf(missingRequired), List.copyOf(forbiddenFound),
            sample.expectedNeedsTransfer(), needsTransfer, handoffCorrect,
            handoff.get("ticketId"), Boolean.TRUE.equals(handoff.get("success")),
            piiLeak, Boolean.TRUE.equals(response.get("redactionApplied")),
            stringList(response.get("redactedTypes")), modelError, latencyMs,
            citations, null);
    }

    private DialogCaseResult errorResult(DialogEvaluationCase sample, int index, Exception error) {
        return new DialogCaseResult(
            firstNonBlank(sample.id(), "case-" + (index + 1)),
            sensitiveDataService.redact(sample.question()).text(), sample.answerable(),
            false, sample.expectedAnswerDecision(), "", "error", "evaluation_error", "", false, 0, "",
            sample.expectedSourceType(), sample.expectedSourceId(),
            expectsGrounding(sample) ? false : null,
            redactList(sample.mustContain()), Collections.emptyList(),
            sample.expectedNeedsTransfer(), false,
            sample.expectedNeedsTransfer() == null ? null : !sample.expectedNeedsTransfer(),
            null, false, false, false, Collections.emptyList(), true, 0,
            Collections.emptyList(), sensitiveDataService.redact(
                firstNonBlank(error.getMessage(), error.getClass().getSimpleName())).text());
    }

    private DialogEvaluationReport summarize(String name, String promptVersion,
                                              List<DialogCaseResult> results,
                                              List<DialogEvaluationCase> samples) {
        int decisionExpected = 0;
        int decisionCorrect = 0;
        int groundingExpected = 0;
        int groundingMatched = 0;
        int requiredPhraseTotal = 0;
        int requiredPhraseHit = 0;
        int forbiddenPhraseTotal = 0;
        int forbiddenPhraseViolations = 0;
        int handoffExpected = 0;
        int handoffCorrect = 0;
        int piiLeaks = 0;
        int modelErrors = 0;

        for (DialogCaseResult result : results) {
            decisionExpected++;
            if (result.decisionCorrect()) decisionCorrect++;
            if (result.groundingMatched() != null) {
                groundingExpected++;
                if (result.groundingMatched()) groundingMatched++;
            }
            if (result.handoffCorrect() != null) {
                handoffExpected++;
                if (result.handoffCorrect()) handoffCorrect++;
            }
            piiLeaks += result.piiLeak() ? 1 : 0;
            modelErrors += result.modelError() ? 1 : 0;
        }

        for (int i = 0; i < results.size(); i++) {
            DialogCaseResult result = results.get(i);
            DialogEvaluationCase sample = samples.get(i);
            requiredPhraseTotal += safeList(sample.mustContain()).size();
            requiredPhraseHit += safeList(sample.mustContain()).size() - result.missingRequiredPhrases().size();
            forbiddenPhraseTotal += safeList(sample.mustNotContain()).size();
            forbiddenPhraseViolations += result.forbiddenPhrasesFound().size();
        }

        return new DialogEvaluationReport(
            name, promptVersion, Instant.now().toString(), true, true,
            "评测会调用真实模型并产生 API 成本；会话、消息、工单和日志数据均按样本回滚。",
            results.size(), decisionExpected, decisionCorrect,
            ratio(decisionCorrect, decisionExpected), groundingExpected, groundingMatched,
            ratio(groundingMatched, groundingExpected), requiredPhraseTotal, requiredPhraseHit,
            ratio(requiredPhraseHit, requiredPhraseTotal), forbiddenPhraseTotal,
            forbiddenPhraseViolations, handoffExpected, handoffCorrect,
            ratio(handoffCorrect, handoffExpected), piiLeaks, modelErrors, List.copyOf(results));
    }

    private void validateRequest(DialogEvaluationRequest request) {
        if (request == null || request.cases() == null || request.cases().isEmpty()) {
            throw new IllegalArgumentException("评测样本不能为空");
        }
        if (request.cases().size() > MAX_CASES) {
            throw new IllegalArgumentException("单次评测最多支持 " + MAX_CASES + " 条样本");
        }
        if (hasText(request.promptVersion())
                && !CustomerServicePromptProvider.isSupported(request.promptVersion())) {
            throw new IllegalArgumentException("promptVersion 仅支持 v1 或 v2");
        }
    }

    private void validateCase(DialogEvaluationCase sample, int index) {
        if (sample == null || !hasText(sample.question())) {
            throw new IllegalArgumentException("第 " + (index + 1) + " 条样本缺少 question");
        }
        if (sample.answerable() == null && !hasText(sample.expectedAnswerStatus())
                && !hasText(sample.expectedAnswerDecision())) {
            throw new IllegalArgumentException(
                "第 " + (index + 1)
                    + " 条样本缺少 answerable、expectedAnswerStatus 或 expectedAnswerDecision");
        }
        if (sample.history() != null) {
            for (EvaluationTurn turn : sample.history()) {
                if (turn == null || !hasText(turn.content())) {
                    throw new IllegalArgumentException("第 " + (index + 1) + " 条样本包含空历史消息");
                }
            }
        }
    }

    private boolean decisionMatches(DialogEvaluationCase sample, boolean actualAnswerable,
                                     String answerStatus, String answerDecision) {
        if (hasText(sample.expectedAnswerDecision())) {
            return sample.expectedAnswerDecision().equalsIgnoreCase(answerDecision);
        }
        if (hasText(sample.expectedAnswerStatus())) {
            return sample.expectedAnswerStatus().equalsIgnoreCase(answerStatus);
        }
        return sample.answerable().equals(actualAnswerable);
    }

    private boolean expectsGrounding(DialogEvaluationCase sample) {
        return hasText(sample.expectedSourceType()) || sample.expectedSourceId() != null;
    }

    private boolean citationMatches(DialogEvaluationCase sample,
                                    List<Map<String, Object>> citations) {
        for (Map<String, Object> citation : citations) {
            boolean typeMatches = !hasText(sample.expectedSourceType())
                || sample.expectedSourceType().equalsIgnoreCase(
                    Objects.toString(citation.get("sourceType"), ""));
            boolean idMatches = sample.expectedSourceId() == null
                || sample.expectedSourceId().toString().equals(
                    Objects.toString(citation.get("sourceId"), ""));
            if (typeMatches && idMatches) return true;
        }
        return false;
    }

    private List<String> missingPhrases(String reply, List<String> phrases) {
        List<String> missing = new ArrayList<>();
        String normalizedReply = normalizePhrase(reply);
        for (String phrase : safeList(phrases)) {
            if (!normalizedReply.contains(normalizePhrase(phrase))) {
                missing.add(sensitiveDataService.redact(phrase).text());
            }
        }
        return missing;
    }

    private List<String> presentPhrases(String reply, List<String> phrases) {
        List<String> present = new ArrayList<>();
        for (String phrase : safeList(phrases)) {
            if (containsForbiddenPhrase(reply, phrase)) {
                present.add(sensitiveDataService.redact(phrase).text());
            }
        }
        return present;
    }

    private boolean containsForbiddenPhrase(String reply, String phrase) {
        String normalizedReply = normalizePhrase(reply);
        String normalizedPhrase = normalizePhrase(phrase);
        if (normalizedPhrase.isEmpty()) return false;
        int fromIndex = 0;
        while (true) {
            int index = normalizedReply.indexOf(normalizedPhrase, fromIndex);
            if (index < 0) return false;
            String prefix = normalizedReply.substring(Math.max(0, index - 8), index);
            if (!hasNegation(prefix)) return true;
            fromIndex = index + normalizedPhrase.length();
        }
    }

    private boolean hasNegation(String prefix) {
        return List.of("无法", "不能", "不会", "不支持", "不代表", "并非", "不是",
                "不等于", "不得", "严禁", "未", "没有")
            .stream().anyMatch(prefix::contains);
    }

    private String normalizePhrase(String value) {
        return Objects.toString(value, "")
            .toLowerCase(java.util.Locale.ROOT)
            .replaceAll("\\s+", "")
            .replace("一年", "1年")
            .replace("一个月", "1个月")
            .replace("一小时", "1小时")
            .replace("一个小时", "1小时")
            .replace("一天", "1天")
            .replace("查看", "查阅");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> citations(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list
            : Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value
            : Collections.emptyMap();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return Collections.emptyList();
        return list.stream().map(Objects::toString).toList();
    }

    private List<String> safeList(List<String> values) {
        return values == null ? Collections.emptyList() : values;
    }

    private List<String> redactList(List<String> values) {
        return safeList(values).stream()
            .map(value -> sensitiveDataService.redact(value).text())
            .toList();
    }

    private double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0 : Math.round((double) numerator / denominator * 10000) / 10000.0;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "";
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (hasText(value)) return value;
        return "";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record DialogEvaluationRequest(
            String name, String promptVersion, List<DialogEvaluationCase> cases) {
        public DialogEvaluationRequest(String name, List<DialogEvaluationCase> cases) {
            this(name, null, cases);
        }
    }

    public record DialogEvaluationCase(
        String id, String question, Boolean answerable, String expectedAnswerStatus,
        String expectedAnswerDecision, String expectedSourceType, Long expectedSourceId,
        List<EvaluationTurn> history,
        List<String> mustContain, List<String> mustNotContain,
        Boolean expectedNeedsTransfer, Long preferredModelId) {}

    public record EvaluationTurn(String role, String content) {}

    public record DialogCaseResult(
        String id, String question, Boolean expectedAnswerable, boolean actualAnswerable,
        String expectedAnswerDecision, String answerDecision, String answerStatus,
        String source, String promptVersion, boolean decisionCorrect, double confidence,
        String reply, String expectedSourceType, Long expectedSourceId,
        Boolean groundingMatched, List<String> missingRequiredPhrases,
        List<String> forbiddenPhrasesFound, Boolean expectedNeedsTransfer,
        boolean actualNeedsTransfer, Boolean handoffCorrect, Object ticketId,
        boolean handoffSucceeded, boolean piiLeak, boolean redactionApplied,
        List<String> redactedTypes, boolean modelError, long latencyMs,
        List<Map<String, Object>> citations, String error) {}

    public record DialogEvaluationReport(
        String name, String promptVersion, String evaluatedAt,
        boolean usesRealModel, boolean databaseRolledBack,
        String costNotice, int total, int decisionExpected, int decisionCorrect,
        double decisionAccuracy, int groundingExpected, int groundingMatched,
        double groundingAccuracy, int requiredPhraseTotal, int requiredPhraseHit,
        double requiredPhraseHitRate, int forbiddenPhraseTotal,
        int forbiddenPhraseViolations, int handoffExpected, int handoffCorrect,
        double handoffAccuracy, int piiLeakCount, int modelErrorCount,
        List<DialogCaseResult> cases) {}
}
