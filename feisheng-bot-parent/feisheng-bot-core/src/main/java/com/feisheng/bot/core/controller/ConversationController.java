package com.feisheng.bot.core.controller;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.core.entity.BotConversation;
import com.feisheng.bot.core.entity.BotMessage;
import com.feisheng.bot.core.service.ConversationServiceImpl;
import com.feisheng.bot.core.service.impl.DialogServiceImpl;
import com.feisheng.bot.core.service.impl.TransferServiceImpl;
import com.feisheng.bot.core.service.impl.MessageServiceImpl;
import com.feisheng.bot.common.vo.R;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
@RestController
@RequestMapping("/api/core/conversation")
public class ConversationController {
    private final DialogServiceImpl dialogService;
    private final TransferServiceImpl transferService;
    private final ConversationServiceImpl conversationService;
    private final MessageServiceImpl messageService;
    public ConversationController(DialogServiceImpl ds, TransferServiceImpl ts,
                                   ConversationServiceImpl cs, MessageServiceImpl ms) {
        dialogService=ds; transferService=ts; conversationService=cs; messageService=ms;
    }
    @PostMapping("/send")
    public R<Map<String,Object>> send(@RequestBody Map<String,String> body) {
        return R.ok(dialogService.send(
            body.get("channelType"), body.get("channelUserId"),
            body.get("content"), body.get("title")));
    }
    @GetMapping("/list") public R<Page<BotConversation>> list(@RequestParam(defaultValue="1") int p, @RequestParam(defaultValue="20") int s) { return R.ok(conversationService.list(p,s)); }
    @GetMapping("/{id}/messages") public R<List<BotMessage>> messages(@PathVariable Long id) { return R.ok(messageService.getByConversation(id)); }
    @PostMapping("/transfer")
    public R<Map<String,Object>> transfer(@RequestBody Map<String,Object> body) {
        Object id = body.get("conversationId");
        Long conversationId = id instanceof Number number
            ? number.longValue() : id == null ? null : Long.valueOf(id.toString());
        String reason = body.get("reason") == null ? null : body.get("reason").toString();
        return R.ok(transferService.transfer(conversationId, reason));
    }
    @PostMapping("/close") public R<Void> close(@RequestBody Map<String,Long> body) { conversationService.close(body.get("conversationId")); return R.ok(); }
}
