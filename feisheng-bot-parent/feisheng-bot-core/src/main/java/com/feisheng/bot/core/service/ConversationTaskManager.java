package com.feisheng.bot.core.service;

import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Applies validated model task actions to a bounded, legacy-compatible task collection. */
@Service
public class ConversationTaskManager {
    private static final int MAX_TASKS = 8;

    public TaskSnapshot apply(Long conversationId, ContextDecision decision,
                              ConversationStateService.Snapshot legacyState) {
        ConversationStateService.Snapshot state = legacyState == null
                ? ConversationStateService.Snapshot.idle(0L) : legacyState;
        if (decision == null) return snapshot(state.tasks(), state.selectedTaskId(), state);

        LinkedHashMap<String, TaskState> tasks = new LinkedHashMap<>(state.tasks());
        String selectedTaskId = state.selectedTaskId();
        if (tasks.isEmpty() && isLegacyActive(state)) {
            selectedTaskId = "legacy:" + value(conversationId, 0L);
            tasks.put(selectedTaskId, new TaskState(selectedTaskId, state.activeIntent(),
                    state.standaloneQuery(), state.entities(), List.of(),
                    state.status() == ConversationStateService.Status.WAITING_FOR_SLOT
                            ? Status.WAITING_FOR_USER : Status.ACTIVE,
                    state.version(), "legacy_state"));
        }

        ContextDecision.TaskAction action = effectiveAction(decision);
        String targetId = targetTaskId(conversationId, decision, selectedTaskId, state.version());
        switch (action) {
            case CREATE -> {
                pauseActive(tasks, targetId, state.version(), "new_topic");
                put(tasks, task(decision, targetId, Status.ACTIVE, state.version(), "created"));
                selectedTaskId = targetId;
            }
            case CONTINUE -> {
                put(tasks, updated(tasks.get(targetId), decision, targetId,
                        Status.ACTIVE, state.version(), "continued"));
                selectedTaskId = targetId;
            }
            case PAUSE -> {
                put(tasks, updated(tasks.get(targetId), decision, targetId,
                        Status.PAUSED, state.version(), "paused"));
                if (targetId.equals(selectedTaskId)) selectedTaskId = "";
            }
            case RESUME -> {
                pauseActive(tasks, targetId, state.version(), "task_switched");
                put(tasks, updated(tasks.get(targetId), decision, targetId,
                        Status.ACTIVE, state.version(), "resumed"));
                selectedTaskId = targetId;
            }
            case WAIT_FOR_USER -> {
                put(tasks, updated(tasks.get(targetId), decision, targetId,
                        Status.WAITING_FOR_USER, state.version(), "waiting_for_user"));
                selectedTaskId = targetId;
            }
            case COMPLETE -> {
                put(tasks, updated(tasks.get(targetId), decision, targetId,
                        Status.RESOLVED, state.version(), "resolved"));
                if (targetId.equals(selectedTaskId)) selectedTaskId = "";
            }
            case CANCEL -> {
                put(tasks, updated(tasks.get(targetId), decision, targetId,
                        Status.CANCELLED, state.version(), "cancelled"));
                if (targetId.equals(selectedTaskId)) selectedTaskId = "";
            }
            case NONE -> {
                // No task mutation; the validated semantic decision can still drive retrieval.
            }
        }
        trim(tasks);
        ConversationStateService.Snapshot next = legacySnapshot(state, tasks, selectedTaskId);
        return snapshot(tasks, selectedTaskId, next);
    }

    private ContextDecision.TaskAction effectiveAction(ContextDecision decision) {
        if (decision.taskAction() != ContextDecision.TaskAction.NONE) return decision.taskAction();
        return switch (decision.relation()) {
            case NEW_TOPIC -> ContextDecision.TaskAction.CREATE;
            case RESUME_TASK -> ContextDecision.TaskAction.RESUME;
            case FOLLOW_UP, CORRECTION, SLOT_FILL -> ContextDecision.TaskAction.CONTINUE;
            default -> ContextDecision.TaskAction.NONE;
        };
    }

    private String targetTaskId(Long conversationId, ContextDecision decision,
                                String selectedTaskId, long version) {
        if (decision.taskId() != null && !decision.taskId().isBlank()) return decision.taskId();
        if (selectedTaskId != null && !selectedTaskId.isBlank()) return selectedTaskId;
        return "task:" + value(conversationId, 0L) + ":" + (version + 1L);
    }

    private boolean isLegacyActive(ConversationStateService.Snapshot state) {
        return state.status() == ConversationStateService.Status.ACTIVE
                || state.status() == ConversationStateService.Status.WAITING_FOR_SLOT;
    }

    private void pauseActive(LinkedHashMap<String, TaskState> tasks, String exceptId,
                             long version, String reason) {
        tasks.replaceAll((id, task) -> !id.equals(exceptId) && task.status() == Status.ACTIVE
                ? task.withStatus(Status.PAUSED, version, reason) : task);
    }

    private TaskState task(ContextDecision decision, String taskId, Status status,
                           long version, String reason) {
        return new TaskState(taskId, decision.intent(), decision.resolvedQuery(), Map.of(),
                decision.originalRequirements(), status, version, reason);
    }

    private TaskState updated(TaskState existing, ContextDecision decision, String taskId,
                              Status status, long version, String reason) {
        Map<String, String> slots = existing == null ? Map.of() : existing.slots();
        List<String> requirements = decision.originalRequirements().isEmpty() && existing != null
                ? existing.originalRequirements() : decision.originalRequirements();
        String intent = decision.intent().isBlank() && existing != null ? existing.intent() : decision.intent();
        String topic = decision.resolvedQuery().isBlank() && existing != null ? existing.topic()
                : decision.resolvedQuery();
        return new TaskState(taskId, intent, topic, slots, requirements, status, version, reason);
    }

    private void put(LinkedHashMap<String, TaskState> tasks, TaskState task) {
        tasks.remove(task.taskId());
        tasks.put(task.taskId(), task);
    }

    private void trim(LinkedHashMap<String, TaskState> tasks) {
        while (tasks.size() > MAX_TASKS) {
            String removable = tasks.entrySet().stream()
                    .filter(entry -> entry.getValue().status() == Status.RESOLVED
                            || entry.getValue().status() == Status.CANCELLED
                            || entry.getValue().status() == Status.PAUSED)
                    .map(Map.Entry::getKey).findFirst().orElse(tasks.keySet().iterator().next());
            tasks.remove(removable);
        }
    }

    private ConversationStateService.Snapshot legacySnapshot(
            ConversationStateService.Snapshot existing, Map<String, TaskState> tasks,
            String selectedTaskId) {
        TaskState active = activeTask(tasks, selectedTaskId);
        if (active == null) {
            return new ConversationStateService.Snapshot(
                    ConversationStateService.Status.IDLE, "", Map.of(), List.of(), "",
                    null, 0, 0, existing.version(), tasks, "");
        }
        ConversationStateService.Status legacyStatus = active.status() == Status.WAITING_FOR_USER
                ? ConversationStateService.Status.WAITING_FOR_SLOT
                : ConversationStateService.Status.ACTIVE;
        return new ConversationStateService.Snapshot(legacyStatus, active.intent(), active.slots(),
                legacyStatus == ConversationStateService.Status.WAITING_FOR_SLOT
                        ? List.of("context") : List.of(),
                active.topic(), existing.pending(), existing.clarificationAttempts(),
                Math.max(1, existing.remainingTurns()), existing.version(), tasks, selectedTaskId);
    }

    private TaskSnapshot snapshot(Map<String, TaskState> tasks, String selectedTaskId,
                                  ConversationStateService.Snapshot legacyState) {
        Map<String, TaskState> immutable = Collections.unmodifiableMap(new LinkedHashMap<>(tasks));
        return new TaskSnapshot(activeTask(immutable, selectedTaskId), immutable,
                selectedTaskId == null ? "" : selectedTaskId, legacyState);
    }

    private TaskState activeTask(Map<String, TaskState> tasks, String selectedTaskId) {
        TaskState selected = selectedTaskId == null ? null : tasks.get(selectedTaskId);
        if (selected != null && (selected.status() == Status.ACTIVE
                || selected.status() == Status.WAITING_FOR_USER)) return selected;
        return tasks.values().stream().filter(task -> task.status() == Status.ACTIVE
                || task.status() == Status.WAITING_FOR_USER).findFirst().orElse(null);
    }

    private long value(Long value, long fallback) {
        return value == null ? fallback : value;
    }

    public enum Status { ACTIVE, WAITING_FOR_USER, PAUSED, RESOLVED, CANCELLED }

    public record TaskState(String taskId, String intent, String topic, Map<String, String> slots,
                            List<String> originalRequirements, Status status, long updatedVersion,
                            String reason) {
        public TaskState {
            taskId = taskId == null ? "" : taskId;
            intent = intent == null ? "UNKNOWN" : intent;
            topic = topic == null ? "" : topic;
            slots = slots == null ? Map.of() : Map.copyOf(slots);
            originalRequirements = originalRequirements == null ? List.of()
                    : List.copyOf(originalRequirements);
            status = status == null ? Status.ACTIVE : status;
            updatedVersion = Math.max(0L, updatedVersion);
            reason = reason == null ? "" : reason;
        }

        public TaskState withStatus(Status nextStatus, long version, String nextReason) {
            return new TaskState(taskId, intent, topic, slots, originalRequirements,
                    nextStatus, version, nextReason);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("taskId", taskId);
            values.put("intent", intent);
            values.put("topic", topic);
            values.put("slots", slots);
            values.put("originalRequirements", originalRequirements);
            values.put("status", status.name());
            values.put("updatedVersion", updatedVersion);
            values.put("reason", reason);
            return values;
        }
    }

    public record TaskSnapshot(TaskState activeTask, Map<String, TaskState> tasks,
                               String selectedTaskId,
                               ConversationStateService.Snapshot legacyState) {
        public Map<String, Object> serializedLegacyState() {
            return legacyState.toMap();
        }
    }
}
