package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.admin.entity.BotFaqDraft;
import com.feisheng.bot.admin.entity.BotFaqRegressionRun;
import com.feisheng.bot.admin.mapper.BotFaqDraftMapper;
import com.feisheng.bot.admin.mapper.BotFaqRegressionRunMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class FaqRegressionService {
    private static final int MAX_DRAFTS = 500;
    private static final int MAX_CASES = 100;

    private final BotFaqDraftMapper draftMapper;
    private final BotFaqRegressionRunMapper regressionRunMapper;
    private final DialogEvaluationService evaluationService;
    private final ObjectMapper objectMapper;

    public FaqRegressionService(BotFaqDraftMapper draftMapper,
                                BotFaqRegressionRunMapper regressionRunMapper,
                                DialogEvaluationService evaluationService,
                                ObjectMapper objectMapper) {
        this.draftMapper = draftMapper;
        this.regressionRunMapper = regressionRunMapper;
        this.evaluationService = evaluationService;
        this.objectMapper = objectMapper;
    }

    public RegressionReport evaluate(List<Long> draftIds, String promptVersion) {
        Set<Long> requestedIds = new LinkedHashSet<>();
        if (draftIds != null) draftIds.stream().filter(Objects::nonNull).forEach(requestedIds::add);
        if (requestedIds.size() > MAX_DRAFTS) {
            throw new FaqDraftService.FaqDraftException(400, "单次最多选择500个FAQ草稿");
        }

        QueryWrapper<BotFaqDraft> query = new QueryWrapper<BotFaqDraft>()
            .eq("status", FaqDraftService.PUBLISHED)
            .isNotNull("published_item_id")
            .in(!requestedIds.isEmpty(), "id", requestedIds)
            .orderByDesc("published_at")
            .orderByDesc("id")
            .last("LIMIT " + MAX_DRAFTS);
        List<BotFaqDraft> drafts = draftMapper.selectList(query);
        if (drafts == null || drafts.isEmpty()) {
            throw new FaqDraftService.FaqDraftException(400, "当前没有已发布FAQ可用于回归验证");
        }

        LinkedHashMap<String, CaseSeed> seeds = new LinkedHashMap<>();
        for (BotFaqDraft draft : drafts) {
            addSeed(seeds, draft, draft.getQuestion(), "ANSWER");
        }
        for (BotFaqDraft draft : drafts) {
            for (String question : similarQuestions(draft.getSimilarQuestionsJson())) {
                // A cluster question can broaden the scope beyond the canonical FAQ.
                // Preserve safe partial answers instead of requiring unsupported facts.
                addSeed(seeds, draft, question, "ANSWER_OR_PARTIAL");
            }
        }
        List<CaseSeed> dataset = new ArrayList<>(seeds.values());
        List<DialogEvaluationService.DialogEvaluationCase> cases = dataset.stream()
            .limit(MAX_CASES)
            .map(seed -> new DialogEvaluationService.DialogEvaluationCase(
                "faq-" + seed.draftId() + "-" + seed.sequence(), seed.question(), true,
                null, seed.expectedAnswerDecision(), "faq", seed.publishedItemId(),
                List.of(), List.of(),
                List.of(), false, null))
            .toList();
        if (cases.isEmpty()) {
            throw new FaqDraftService.FaqDraftException(400, "已发布FAQ中没有可用的回归问题");
        }

        DialogEvaluationService.DialogEvaluationReport evaluation;
        try {
            evaluation = evaluationService.evaluate(
                new DialogEvaluationService.DialogEvaluationRequest(
                    "published-faq-regression", promptVersion, cases));
        } catch (IllegalArgumentException error) {
            throw new FaqDraftService.FaqDraftException(400, error.getMessage());
        }
        List<DialogEvaluationService.DialogCaseResult> failedCases = evaluation.cases().stream()
            .filter(result -> !passed(result))
            .toList();
        int executed = evaluation.cases().size();
        RegressionReport report = new RegressionReport(failedCases.isEmpty(), drafts.size(), dataset.size(),
            executed, dataset.size() > executed, executed - failedCases.size(),
            failedCases.size(), evaluation, failedCases, null, null);
        return persist(report, promptVersion);
    }

    private RegressionReport persist(RegressionReport report, String promptVersion) {
        BotFaqRegressionRun run = new BotFaqRegressionRun();
        run.setPassed(report.passed() ? 1 : 0);
        run.setPromptVersion(promptVersion);
        run.setPublishedDraftCount(report.publishedDraftCount());
        run.setDatasetCaseCount(report.datasetCaseCount());
        run.setExecutedCaseCount(report.executedCaseCount());
        run.setPassedCaseCount(report.passedCaseCount());
        run.setFailedCaseCount(report.failedCaseCount());
        run.setTruncated(report.truncated() ? 1 : 0);
        run.setFailedCasesJson(writeFailures(report.failedCases()));
        Date now = new Date();
        run.setCreateTime(now);
        run.setUpdateTime(now);
        regressionRunMapper.insert(run);
        return new RegressionReport(report.passed(), report.publishedDraftCount(),
            report.datasetCaseCount(), report.executedCaseCount(), report.truncated(),
            report.passedCaseCount(), report.failedCaseCount(), report.evaluation(),
            report.failedCases(), run.getId(), now);
    }

    private String writeFailures(List<DialogEvaluationService.DialogCaseResult> failedCases) {
        List<FailureSnapshot> failures = failedCases.stream()
            .map(result -> new FailureSnapshot(result.id(), result.question(), failureReasons(result)))
            .toList();
        try {
            return objectMapper.writeValueAsString(failures);
        } catch (Exception error) {
            return "[]";
        }
    }

    private List<String> failureReasons(DialogEvaluationService.DialogCaseResult result) {
        List<String> reasons = new ArrayList<>();
        if (!result.decisionCorrect()) reasons.add("回答决策不符合预期");
        if (Boolean.FALSE.equals(result.groundingMatched())) reasons.add("FAQ引用不正确");
        if (Boolean.FALSE.equals(result.handoffCorrect())) reasons.add("转人工判断不正确");
        if (!result.missingRequiredPhrases().isEmpty()) reasons.add("缺少必要内容");
        if (!result.forbiddenPhrasesFound().isEmpty()) reasons.add("出现禁止内容");
        if (result.piiLeak()) reasons.add("检测到隐私泄露");
        if (result.modelError()) reasons.add("模型调用失败");
        return reasons;
    }

    private void addSeed(LinkedHashMap<String, CaseSeed> seeds,
                         BotFaqDraft draft, String question, String expectedAnswerDecision) {
        String normalized = question == null ? "" : question.trim();
        if (normalized.isBlank() || draft.getPublishedItemId() == null) return;
        seeds.computeIfAbsent(normalized, ignored -> new CaseSeed(
            draft.getId(), draft.getPublishedItemId(), normalized, seeds.size() + 1,
            expectedAnswerDecision));
    }

    private List<String> similarQuestions(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private boolean passed(DialogEvaluationService.DialogCaseResult result) {
        return result.decisionCorrect()
            && Boolean.TRUE.equals(result.groundingMatched())
            && Boolean.TRUE.equals(result.handoffCorrect())
            && result.missingRequiredPhrases().isEmpty()
            && result.forbiddenPhrasesFound().isEmpty()
            && !result.piiLeak()
            && !result.modelError();
    }

    private record CaseSeed(Long draftId, Long publishedItemId,
                            String question, int sequence, String expectedAnswerDecision) {}

    public record RegressionReport(
        boolean passed, int publishedDraftCount, int datasetCaseCount,
        int executedCaseCount, boolean truncated, int passedCaseCount,
        int failedCaseCount, DialogEvaluationService.DialogEvaluationReport evaluation,
        List<DialogEvaluationService.DialogCaseResult> failedCases,
        Long runId, Date runAt) {}
    public record FailureSnapshot(String caseId, String question, List<String> reasons) {}
}
