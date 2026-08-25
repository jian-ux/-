package com.feisheng.bot.admin.service;

import com.feisheng.bot.admin.entity.BotUnmatchedQuestion.ImprovementAdvice;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class BadCaseImprovementAdvisor {
    private static final Map<String, ImprovementAdvice> ADVICE = Map.of(
        "NO_ANSWER", new ImprovementAdvice("NO_ANSWER", "补充知识",
            "补充经过审核的知识答案和相似问法，发布前检查引用来源。", "KNOWLEDGE"),
        "GUARDRAIL", new ImprovementAdvice("GUARDRAIL", "复核护栏",
            "先确认拦截是否正确；正确则优化提示，误拦截则缩小规则范围，不能直接绕过安全规则。", "SAFETY_RULE"),
        "LOW_CONFIDENCE", new ImprovementAdvice("LOW_CONFIDENCE", "补强证据",
            "检查召回结果、关键词和相似问法，补强直接证据后再评估置信度阈值。", "RETRIEVAL"),
        "SLOW_RESPONSE", new ImprovementAdvice("SLOW_RESPONSE", "排查耗时",
            "查看检索、重排和模型各阶段耗时，优先修复最慢阶段并重新测试。", "PERFORMANCE"),
        "LOW_RATING", new ImprovementAdvice("LOW_RATING", "复盘回答",
            "结合原问题、最近回答和客户反馈，复盘事实、表达方式和转人工时机。", "ANSWER_REVIEW"));

    private BadCaseImprovementAdvisor() {}

    public static List<ImprovementAdvice> advise(String triggerTypes) {
        Set<String> types = new LinkedHashSet<>();
        if (triggerTypes != null) {
            Arrays.stream(triggerTypes.split(","))
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .forEach(types::add);
        }
        if (types.isEmpty()) types.add("NO_ANSWER");
        return types.stream().map(type -> ADVICE.getOrDefault(type,
            new ImprovementAdvice(type, "人工复盘",
                "检查最近一次问题、回答和诊断信息，确认根因后再选择知识、规则或性能改进。",
                "MANUAL_REVIEW"))).toList();
    }
}
