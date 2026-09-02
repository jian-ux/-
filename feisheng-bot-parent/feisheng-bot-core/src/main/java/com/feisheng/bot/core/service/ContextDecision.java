package com.feisheng.bot.core.service;

import java.util.List;

/** Structured model decision for one immutable turn context. */
public record ContextDecision(
        Relation relation,
        String intent,
        List<String> selectedContextIds,
        List<String> selectedMemoryIds,
        TaskAction taskAction,
        String taskId,
        List<String> originalRequirements,
        String resolvedQuery,
        double confidence,
        boolean needLargeModel) {

    public ContextDecision {
        intent = intent == null ? "UNKNOWN" : intent.trim();
        selectedContextIds = selectedContextIds == null ? List.of() : List.copyOf(selectedContextIds);
        selectedMemoryIds = selectedMemoryIds == null ? List.of() : List.copyOf(selectedMemoryIds);
        taskId = taskId == null ? "" : taskId.trim();
        originalRequirements = originalRequirements == null ? List.of() : List.copyOf(originalRequirements);
        resolvedQuery = resolvedQuery == null ? "" : resolvedQuery.trim();
    }

    public static ContextDecision fallback(String originalQuery) {
        return new ContextDecision(Relation.UNCERTAIN, "UNKNOWN", List.of(), List.of(),
                TaskAction.NONE, "", List.of(), originalQuery, 0D, false);
    }

    public enum Relation {
        NEW_TOPIC,
        FOLLOW_UP,
        CORRECTION,
        SLOT_FILL,
        RESUME_TASK,
        HISTORY_RECALL,
        MULTI_INTENT,
        UNCERTAIN
    }

    public enum TaskAction {
        NONE,
        CREATE,
        CONTINUE,
        PAUSE,
        RESUME,
        WAIT_FOR_USER,
        COMPLETE,
        CANCEL
    }
}
