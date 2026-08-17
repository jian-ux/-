package com.feisheng.bot.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.admin.entity.BotConversation;
import com.feisheng.bot.admin.entity.BotMessage;
import com.feisheng.bot.admin.entity.BotConversationTag;
import com.feisheng.bot.admin.mapper.BotConversationMapper;
import com.feisheng.bot.admin.mapper.BotMessageMapper;
import com.feisheng.bot.admin.mapper.BotConversationTagMapper;
import com.feisheng.bot.common.vo.R;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/conversation")
public class ConversationAdminController {
    private final BotConversationMapper mapper;
    private final BotMessageMapper messageMapper;
    private final BotConversationTagMapper tagMapper;

    public ConversationAdminController(BotConversationMapper m, BotMessageMapper mm, BotConversationTagMapper tm) {
        mapper = m;
        messageMapper = mm;
        tagMapper = tm;
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
        return R.ok(messageMapper.selectList(
            new LambdaQueryWrapper<BotMessage>()
                .eq(BotMessage::getConversationId, id)
                .orderByAsc(BotMessage::getCreateTime)));
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
            if (body.get("csatScore") != null)
                cv.setCsatScore(((Number) body.get("csatScore")).intValue());
            if (body.get("csatFeedback") != null)
                cv.setCsatFeedback((String) body.get("csatFeedback"));
            mapper.updateById(cv);
        }
        return R.ok();
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
