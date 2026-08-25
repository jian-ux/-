package com.feisheng.bot.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.admin.entity.BotConversation;
import com.feisheng.bot.admin.entity.BotMessage;
import com.feisheng.bot.admin.entity.BotConversationTag;
import com.feisheng.bot.admin.mapper.BotConversationMapper;
import com.feisheng.bot.admin.mapper.BotMessageMapper;
import com.feisheng.bot.admin.mapper.BotConversationTagMapper;
import com.feisheng.bot.admin.service.ConversationImageService;
import com.feisheng.bot.common.vo.R;
import com.feisheng.bot.core.service.impl.UnmatchedQuestionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/conversation")
public class ConversationAdminController {
    private final BotConversationMapper mapper;
    private final BotMessageMapper messageMapper;
    private final BotConversationTagMapper tagMapper;
    private final ConversationImageService imageService;
    private final UnmatchedQuestionService badCaseService;

    @Value("${rag.bad-case.low-rating-threshold:2}")
    private int badCaseLowRatingThreshold = 2;

    public ConversationAdminController(BotConversationMapper m, BotMessageMapper mm,
                                       BotConversationTagMapper tm,
                                       ConversationImageService imageService,
                                       UnmatchedQuestionService badCaseService) {
        mapper = m;
        messageMapper = mm;
        tagMapper = tm;
        this.imageService = imageService;
        this.badCaseService = badCaseService;
    }

    @GetMapping("/list")
    public R<Page<BotConversation>> list(@RequestParam(defaultValue="1") int page,
                                          @RequestParam(defaultValue="10") int size,
                                          @RequestParam(required=false) String status,
                                          @RequestParam(required=false) String emotionLabel,
                                          @RequestParam(required=false) String emotionRisk,
                                          @RequestParam(required=false) String channelType,
                                          @RequestParam(required=false) String customerName) {
        String normalizedEmotion = StringUtils.hasText(emotionLabel)
            ? emotionLabel.trim().toUpperCase() : null;
        String normalizedRisk = StringUtils.hasText(emotionRisk)
            ? emotionRisk.trim().toUpperCase() : null;
        String normalizedChannel = normalize(channelType);
        String normalizedCustomer = normalize(customerName);
        return R.ok(mapper.selectMonitorPage(
            new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)),
            normalize(status), normalizedEmotion, normalizedRisk,
            normalizedChannel, normalizedCustomer));
    }

    @GetMapping("/{id}/detail")
    public R<BotConversation> detail(@PathVariable Long id) {
        return R.ok(mapper.selectMonitorById(id));
    }

    @GetMapping("/{id}/messages")
    public R<List<BotMessage>> messages(@PathVariable Long id) {
        List<BotMessage> messages = messageMapper.selectList(
            new LambdaQueryWrapper<BotMessage>()
                .eq(BotMessage::getConversationId, id)
                .orderByAsc(BotMessage::getCreateTime));
        messages.forEach(message -> message.setMediaUrl(imageService.url(message)));
        return R.ok(messages);
    }

    @PutMapping("/{id}/priority")
    public R<Void> updatePriority(@PathVariable Long id, @RequestBody Map<String, String> body) {
        BotConversation cv = mapper.selectById(id);
        if (cv != null) {
            String priority = body.get("priority");
            cv.setPriority(priority);
            // Recalculate SLA based on new priority
            long now = System.currentTimeMillis();
            int minutes = switch (priority) {
                case "P0" -> 30;
                case "P1" -> 60;
                case "P3" -> 240;
                default -> 90; // P2
            };
            cv.setSlaDeadline(new Date(now + minutes * 60_000L));
            mapper.updateById(cv);
        }
        return R.ok();
    }

    @PutMapping("/{id}/csat")
    public R<Void> updateCsat(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        BotConversation cv = mapper.selectById(id);
        if (cv != null) {
            Integer previousScore = cv.getCsatScore();
            Integer score = body.get("csatScore") instanceof Number value
                ? value.intValue() : null;
            if (score != null) cv.setCsatScore(score);
            if (body.get("csatFeedback") != null)
                cv.setCsatFeedback((String) body.get("csatFeedback"));
            mapper.updateById(cv);
            if (score != null && score <= badCaseLowRatingThreshold
                    && (previousScore == null || previousScore > badCaseLowRatingThreshold)) {
                recordLowRating(id, score);
            }
        }
        return R.ok();
    }

    private void recordLowRating(Long conversationId, int score) {
        BotMessage latestQuestion = messageMapper.selectOne(
            new LambdaQueryWrapper<BotMessage>()
                .eq(BotMessage::getConversationId, conversationId)
                .eq(BotMessage::getRole, "user")
                .orderByDesc(BotMessage::getCreateTime)
                .last("LIMIT 1"));
        if (latestQuestion == null || !StringUtils.hasText(latestQuestion.getContent())) return;
        badCaseService.recordBadCase(latestQuestion.getContent(), Set.of("LOW_RATING"),
            new UnmatchedQuestionService.BadCaseContext(
                conversationId, "rated", "csat", null, null, score));
    }

    // ================== Tags ==================

    @GetMapping("/{id}/tags")
    public R<List<String>> getTags(@PathVariable Long id) {
        return R.ok(tagMapper.selectList(
                new LambdaQueryWrapper<BotConversationTag>()
                        .eq(BotConversationTag::getConversationId, id))
                .stream().map(BotConversationTag::getTagName).toList());
    }

    @PostMapping("/{id}/tags")
    public R<Void> addTag(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String tagName = body.get("tagName");
        if (tagName == null || tagName.isBlank()) {
            return R.fail(400, "标签名称不能为空");
        }
        BotConversationTag tag = new BotConversationTag();
        tag.setConversationId(id);
        tag.setTagName(tagName.trim());
        tagMapper.insert(tag);
        return R.ok();
    }

    @DeleteMapping("/{id}/tags/{tagName}")
    public R<Void> removeTag(@PathVariable Long id, @PathVariable String tagName) {
        tagMapper.delete(new LambdaQueryWrapper<BotConversationTag>()
                .eq(BotConversationTag::getConversationId, id)
                .eq(BotConversationTag::getTagName, tagName));
        return R.ok();
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
