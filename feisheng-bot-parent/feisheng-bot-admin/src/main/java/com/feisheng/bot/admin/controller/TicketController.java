package com.feisheng.bot.admin.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.admin.entity.BotTicket;
import com.feisheng.bot.admin.entity.BotTicketRecord;
import com.feisheng.bot.admin.service.HumanHandoffService;
import com.feisheng.bot.common.vo.R;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/ticket")
public class TicketController {
    private final HumanHandoffService handoffService;

    public TicketController(HumanHandoffService handoffService) {
        this.handoffService = handoffService;
    }

    @GetMapping("/list")
    public R<Page<BotTicket>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean mine,
            Authentication authentication) {
        return R.ok(handoffService.list(page, size, status,
            mine ? operatorId(authentication) : null));
    }

    @GetMapping("/{id}")
    public R<BotTicket> get(@PathVariable Long id) {
        return R.ok(handoffService.get(id));
    }

    @GetMapping("/conversation/{conversationId}")
    public R<BotTicket> byConversation(@PathVariable Long conversationId) {
        return R.ok(handoffService.findByConversation(conversationId));
    }

    @GetMapping("/{id}/records")
    public R<List<BotTicketRecord>> records(@PathVariable Long id) {
        return R.ok(handoffService.records(id));
    }

    @PostMapping("/{id}/claim")
    public R<BotTicket> claim(@PathVariable Long id, Authentication authentication) {
        return R.ok(handoffService.claim(id, operatorId(authentication)));
    }

    @PostMapping("/{id}/reply")
    public R<HumanHandoffService.ReplyResult> reply(
            @PathVariable Long id, @RequestBody Map<String, String> body,
            Authentication authentication) {
        return R.ok(handoffService.reply(
            id, operatorId(authentication), body.get("content")));
    }

    @PostMapping("/{id}/resolve")
    public R<HumanHandoffService.ResolveResult> resolve(
            @PathVariable Long id, @RequestBody(required = false) Map<String, String> body,
            Authentication authentication) {
        String resolution = body == null ? null : body.get("resolution");
        return R.ok(handoffService.resolve(
            id, operatorId(authentication), resolution));
    }

    private Long operatorId(Authentication authentication) {
        return authentication == null ? null : (Long) authentication.getPrincipal();
    }
}
