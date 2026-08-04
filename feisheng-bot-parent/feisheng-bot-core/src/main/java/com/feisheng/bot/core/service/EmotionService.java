package com.feisheng.bot.core.service;

import com.feisheng.bot.core.entity.BotMessage;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class EmotionService {
    private static final Map<EmotionLabel, List<String>> MARKERS = Map.of(
        EmotionLabel.ANGER, List.of(
            "气死", "愤怒", "生气", "太过分", "离谱", "垃圾", "坑人", "骗子",
            "什么破", "差劲", "投诉", "曝光", "再也不用", "妈的", "fuck", "shit"),
        EmotionLabel.ANXIETY, List.of(
            "焦虑", "担心", "害怕", "慌了", "着急", "急死", "钱没了", "扣款了",
            "造成损失", "不知道怎么办", "该怎么办"),
        EmotionLabel.URGENCY, List.of(
            "赶紧", "马上", "立刻", "尽快", "急用", "等不及", "催一下", "到底什么时候",
            "还要多久", "来不及", "现在就要"),
        EmotionLabel.DISAPPOINTMENT, List.of(
            "失望", "不满意", "太差", "算了", "不想用了", "一直没解决", "又出问题",
            "没想到", "体验不好", "白等了"),
        EmotionLabel.SATISFACTION, List.of(
            "谢谢", "感谢", "满意", "太好了", "解决了", "不错", "可以了", "辛苦了", "很棒")
    );
    private static final List<String> INTENSIFIERS = List.of(
        "非常", "特别", "真的", "太", "一直", "又", "到底", "根本", "完全");
    private static final List<String> NEGATIONS = List.of(
        "不", "没", "别", "无需", "不用", "不要", "并不", "没有");

    public EmotionResult analyze(String text, List<BotMessage> recentMessages,
                                 Long currentMessageId) {
        SingleEmotion current = analyzeSingle(text);
        SingleEmotion previous = latestPreviousUserEmotion(recentMessages, currentMessageId);
        int negativeStreak = negativeStreak(current, recentMessages, currentMessageId);
        EmotionTrend trend = trend(current, previous);
        EmotionRisk risk = risk(current, negativeStreak);
        boolean shouldHandoff = risk == EmotionRisk.HIGH;
        String priority = risk == EmotionRisk.HIGH && current.label() == EmotionLabel.ANGER
            ? "P0" : risk == EmotionRisk.HIGH || risk == EmotionRisk.MEDIUM ? "P1" : "P2";
        return new EmotionResult(
            current.label(), current.confidence(), trend, negativeStreak, risk,
            shouldHandoff, priority, displayLabel(current.label()),
            instruction(current.label()), acknowledgement(current.label()));
    }

    public String adaptDeterministicReply(String reply, EmotionResult emotion) {
        if (reply == null || emotion == null || !emotion.isNegative()) return reply;
        String prefix = emotion.acknowledgement();
        if (prefix == null || prefix.isBlank() || reply.startsWith(prefix)
                || (reply.startsWith("抱歉") && prefix.startsWith("抱歉"))) return reply;
        return prefix + reply;
    }

    private SingleEmotion analyzeSingle(String value) {
        String text = value == null ? "" : value.toLowerCase();
        EnumMap<EmotionLabel, Double> points = new EnumMap<>(EmotionLabel.class);
        for (EmotionLabel label : EmotionLabel.values()) points.put(label, 0.0);
        for (Map.Entry<EmotionLabel, List<String>> entry : MARKERS.entrySet()) {
            for (String marker : entry.getValue()) {
                int from = 0;
                while (from < text.length()) {
                    int index = text.indexOf(marker, from);
                    if (index < 0) break;
                    if (!isNegated(text, index)) {
                        double weight = entry.getKey() == EmotionLabel.ANGER ? 1.2
                            : entry.getKey() == EmotionLabel.URGENCY ? 0.9 : 1.0;
                        points.merge(entry.getKey(), weight, Double::sum);
                    }
                    from = index + marker.length();
                }
            }
        }

        EmotionLabel best = EmotionLabel.NEUTRAL;
        double bestPoints = 0;
        double secondPoints = 0;
        for (EmotionLabel label : List.of(
                EmotionLabel.ANGER, EmotionLabel.ANXIETY, EmotionLabel.URGENCY,
                EmotionLabel.DISAPPOINTMENT, EmotionLabel.SATISFACTION)) {
            double score = points.get(label);
            if (score > bestPoints) {
                secondPoints = bestPoints;
                bestPoints = score;
                best = label;
            } else if (score > secondPoints) {
                secondPoints = score;
            }
        }
        if (best == EmotionLabel.NEUTRAL) return new SingleEmotion(best, 0.90);

        boolean intensified = INTENSIFIERS.stream().anyMatch(text::contains);
        long exclamations = text.chars().filter(ch -> ch == '!' || ch == '！').count();
        double signal = bestPoints + (intensified ? 0.25 : 0)
            + (exclamations >= 2 ? 0.2 : 0);
        double confidence = Math.min(0.98,
            0.52 + signal * 0.13 + Math.max(0, bestPoints - secondPoints) * 0.04);
        return new SingleEmotion(best, round(confidence));
    }

    private SingleEmotion latestPreviousUserEmotion(List<BotMessage> messages,
                                                     Long currentMessageId) {
        if (messages == null) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            BotMessage message = messages.get(i);
            if (!isPreviousUserMessage(message, currentMessageId)) continue;
            return analyzeSingle(message.getContent());
        }
        return null;
    }

    private int negativeStreak(SingleEmotion current, List<BotMessage> messages,
                               Long currentMessageId) {
        if (!isNegative(current.label())) return 0;
        int streak = 1;
        if (messages == null) return streak;
        for (int i = messages.size() - 1; i >= 0 && streak < 6; i--) {
            BotMessage message = messages.get(i);
            if (!isPreviousUserMessage(message, currentMessageId)) continue;
            if (!isNegative(analyzeSingle(message.getContent()).label())) break;
            streak++;
        }
        return streak;
    }

    private boolean isPreviousUserMessage(BotMessage message, Long currentMessageId) {
        if (message == null || !"user".equals(message.getRole())) return false;
        return currentMessageId == null || message.getId() == null
            || !currentMessageId.equals(message.getId());
    }

    private EmotionTrend trend(SingleEmotion current, SingleEmotion previous) {
        if (previous == null) return EmotionTrend.STABLE;
        boolean currentNegative = isNegative(current.label());
        boolean previousNegative = isNegative(previous.label());
        if (current.label() == EmotionLabel.SATISFACTION && previousNegative) {
            return EmotionTrend.IMPROVING;
        }
        if (!currentNegative && previousNegative) return EmotionTrend.EASING;
        if (currentNegative && !previousNegative) return EmotionTrend.WORSENING;
        if (currentNegative) {
            if (current.confidence() > previous.confidence() + 0.08) return EmotionTrend.WORSENING;
            if (current.confidence() + 0.08 < previous.confidence()) return EmotionTrend.EASING;
            return EmotionTrend.PERSISTENT;
        }
        return EmotionTrend.STABLE;
    }

    private EmotionRisk risk(SingleEmotion emotion, int negativeStreak) {
        if (!isNegative(emotion.label())) return EmotionRisk.LOW;
        if ((emotion.label() == EmotionLabel.ANGER && emotion.confidence() >= 0.86)
                || negativeStreak >= 3) return EmotionRisk.HIGH;
        if (emotion.confidence() >= 0.72 || negativeStreak >= 2) return EmotionRisk.MEDIUM;
        return EmotionRisk.LOW;
    }

    private boolean isNegated(String text, int markerIndex) {
        String prefix = text.substring(Math.max(0, markerIndex - 3), markerIndex);
        return NEGATIONS.stream().anyMatch(prefix::endsWith);
    }

    private boolean isNegative(EmotionLabel label) {
        return label == EmotionLabel.ANGER || label == EmotionLabel.ANXIETY
            || label == EmotionLabel.URGENCY || label == EmotionLabel.DISAPPOINTMENT;
    }

    private String displayLabel(EmotionLabel label) {
        return switch (label) {
            case ANGER -> "愤怒";
            case ANXIETY -> "焦虑";
            case URGENCY -> "催促";
            case DISAPPOINTMENT -> "失望";
            case SATISFACTION -> "满意";
            default -> "平静";
        };
    }

    private String instruction(EmotionLabel label) {
        return switch (label) {
            case ANGER -> "用户明显不满。先简短致歉并承认影响，避免辩解，再给出明确处理方案。";
            case ANXIETY -> "用户感到焦虑。先给予确定性的安抚，再说明事实、条件和后续处理方式。";
            case URGENCY -> "用户正在催促。优先给结论和可执行步骤，表达简短，不使用空泛安慰。";
            case DISAPPOINTMENT -> "用户对服务失望。先承认体验未达预期，再直接说明补救方案。";
            case SATISFACTION -> "用户情绪积极。保持简洁友好的语气，不重复冗长说明。";
            default -> "保持专业、直接、清晰的客服语气。";
        };
    }

    private String acknowledgement(EmotionLabel label) {
        return switch (label) {
            case ANGER -> "抱歉给您带来不好的体验。";
            case ANXIETY -> "请放心，我来帮您确认。";
            case URGENCY -> "理解您着急，我直接说明：";
            case DISAPPOINTMENT -> "抱歉这次体验没有达到预期。";
            default -> "";
        };
    }

    private double round(double value) {
        return Math.round(value * 10000) / 10000.0;
    }

    public enum EmotionLabel {
        NEUTRAL, ANGER, ANXIETY, URGENCY, DISAPPOINTMENT, SATISFACTION
    }

    public enum EmotionTrend {
        STABLE, WORSENING, PERSISTENT, EASING, IMPROVING
    }

    public enum EmotionRisk {
        LOW, MEDIUM, HIGH
    }

    private record SingleEmotion(EmotionLabel label, double confidence) {}

    public record EmotionResult(EmotionLabel label, double confidence,
                                EmotionTrend trend, int negativeStreak,
                                EmotionRisk risk, boolean shouldHandoff,
                                String priority, String displayLabel,
                                String instruction, String acknowledgement) {
        public boolean isNegative() {
            return label == EmotionLabel.ANGER || label == EmotionLabel.ANXIETY
                || label == EmotionLabel.URGENCY || label == EmotionLabel.DISAPPOINTMENT;
        }
    }
}
