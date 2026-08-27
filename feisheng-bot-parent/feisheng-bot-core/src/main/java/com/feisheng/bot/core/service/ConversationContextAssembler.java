package com.feisheng.bot.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.core.entity.BotMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

@Component
public class ConversationContextAssembler {
    private static final String CURRENT_QUESTION = "【当前问题】";
    private static final String RECENT_CHAT = "【最近聊天】";
    private static final String TASK_STATE = "【当前任务状态】";
    private static final String OLD_SUMMARY = "【旧聊天摘要】";
    private static final String USER_PROFILE = "【与本轮有关的用户信息】";

    private final ObjectMapper objectMapper;
    private final ConversationSummaryFormat summaryFormat;

    public ConversationContextAssembler(ObjectMapper objectMapper,
                                        ConversationSummaryFormat summaryFormat) {
        this.objectMapper = objectMapper;
        this.summaryFormat = summaryFormat;
    }

    public AssembledContext assemble(
            String currentQuestion,
            List<BotMessage> messages,
            Long currentMessageId,
            Long summaryMessageId,
            String summary,
            Map<String, Object> taskState,
            String profileContext,
            int maxRecentMessages,
            UnaryOperator<String> sanitizer) {
        UnaryOperator<String> safeSanitizer = sanitizer == null
            ? UnaryOperator.identity() : sanitizer;
        String question = sanitize(currentQuestion, safeSanitizer);
        List<RecentMessage> recent = recentMessages(
            messages, currentMessageId, summaryMessageId, currentQuestion,
            maxRecentMessages, safeSanitizer);
        String normalizedSummary = summaryFormat.normalizeStoredSummary(summary, Integer.MAX_VALUE);
        return new AssembledContext(
            question,
            recent,
            serializeTaskState(taskState, safeSanitizer),
            sanitize(normalizedSummary, safeSanitizer),
            sanitize(profileContext, safeSanitizer)
        );
    }

    private List<RecentMessage> recentMessages(
            List<BotMessage> messages, Long currentMessageId, Long summaryMessageId,
            String currentQuestion, int maxRecentMessages,
            UnaryOperator<String> sanitizer) {
        if (messages == null || messages.isEmpty() || maxRecentMessages <= 0) {
            return List.of();
        }

        int summaryIndex = -1;
        int currentIndex = -1;
        for (int i = 0; i < messages.size(); i++) {
            BotMessage message = messages.get(i);
            if (message == null) continue;
            if (summaryMessageId != null && summaryMessageId.equals(message.getId())) {
                summaryIndex = i;
            }
            if (currentMessageId != null && currentMessageId.equals(message.getId())) {
                currentIndex = i;
            }
        }
        if (currentIndex < 0) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                BotMessage message = messages.get(i);
                if (message != null && "user".equals(message.getRole())
                        && sameText(message.getContent(), currentQuestion)) {
                    currentIndex = i;
                    break;
                }
            }
        }

        List<RecentMessage> eligible = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            BotMessage message = messages.get(i);
            if (message == null || i == currentIndex || i <= summaryIndex) continue;
            if (summaryIndex < 0 && summaryMessageId != null && message.getId() != null
                    && message.getId() <= summaryMessageId) continue;
            if (!"user".equals(message.getRole()) && !"ai".equals(message.getRole())) continue;
            String content = sanitize(message.getContent(), sanitizer);
            if (content.isBlank()) continue;
            eligible.add(new RecentMessage(
                "user".equals(message.getRole()) ? "用户" : "客服", content));
        }
        int from = Math.max(0, eligible.size() - maxRecentMessages);
        return List.copyOf(eligible.subList(from, eligible.size()));
    }

    private String serializeTaskState(Map<String, Object> taskState,
                                      UnaryOperator<String> sanitizer) {
        if (taskState == null || taskState.isEmpty()) return "无";
        try {
            return sanitize(objectMapper.writeValueAsString(taskState), sanitizer);
        } catch (JsonProcessingException e) {
            return sanitize(taskState.toString(), sanitizer);
        }
    }

    private String sanitize(String value, UnaryOperator<String> sanitizer) {
        if (value == null || value.isBlank()) return "";
        String sanitized = sanitizer.apply(value);
        return sanitized == null ? "" : sanitized.strip();
    }

    private boolean sameText(String first, String second) {
        return first != null && second != null && first.strip().equals(second.strip());
    }

    private record RecentMessage(String role, String content) {
        private String render() {
            return role + "：" + content;
        }
    }

    public static final class AssembledContext {
        private final String currentQuestion;
        private final List<RecentMessage> recentMessages;
        private final String taskState;
        private final String oldSummary;
        private final String userProfile;

        private AssembledContext(String currentQuestion, List<RecentMessage> recentMessages,
                                 String taskState, String oldSummary, String userProfile) {
            this.currentQuestion = currentQuestion == null ? "" : currentQuestion;
            this.recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
            this.taskState = taskState == null || taskState.isBlank() ? "无" : taskState;
            this.oldSummary = oldSummary == null ? "" : oldSummary;
            this.userProfile = userProfile == null ? "" : userProfile;
        }

        public String currentQuestion() {
            return currentQuestion;
        }

        public int mandatoryChars() {
            int from = Math.max(0, recentMessages.size() - 2);
            return renderSections(recentMessages.subList(from, recentMessages.size()),
                false, false).length();
        }

        public String render(int maxChars) {
            int limit = Math.max(1, maxChars);
            List<RecentMessage> recent = new ArrayList<>(recentMessages);
            boolean includeSummary = !oldSummary.isBlank();
            boolean includeProfile = !userProfile.isBlank();
            String rendered = renderSections(recent, includeSummary, includeProfile);
            if (rendered.length() <= limit) return rendered;

            includeSummary = false;
            rendered = renderSections(recent, false, includeProfile);
            while (rendered.length() > limit && recent.size() > 2) {
                recent.remove(0);
                rendered = renderSections(recent, false, includeProfile);
            }
            if (rendered.length() > limit && includeProfile) {
                includeProfile = false;
                rendered = renderSections(recent, false, false);
            }
            return rendered;
        }

        private String renderSections(List<RecentMessage> recent,
                                      boolean includeSummary, boolean includeProfile) {
            List<String> sections = new ArrayList<>();
            sections.add(CURRENT_QUESTION + "\n" + valueOrNone(currentQuestion));
            if (recent != null && !recent.isEmpty()) {
                sections.add(RECENT_CHAT + "\n" + String.join("\n",
                    recent.stream().map(RecentMessage::render).toList()));
            }
            sections.add(TASK_STATE + "\n" + valueOrNone(taskState));
            if (includeSummary) sections.add(OLD_SUMMARY + "\n" + oldSummary);
            if (includeProfile) sections.add(USER_PROFILE + "\n" + userProfile);
            return String.join("\n\n", sections);
        }

        private String valueOrNone(String value) {
            return value == null || value.isBlank() ? "无" : value;
        }
    }
}
