package com.feisheng.bot.core.controller;

import com.feisheng.bot.core.dto.ChatResponse;
import com.feisheng.bot.core.dto.SafetyResult;
import com.feisheng.bot.core.service.impl.AiModelServiceImpl;
import com.feisheng.bot.core.service.impl.FaqMatchServiceImpl;
import com.feisheng.bot.core.service.impl.SafetyServiceImpl;
import com.feisheng.bot.core.service.PlainTextReplyFormatter;
import com.feisheng.bot.common.vo.R;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController("corePlaygroundController")
@RequestMapping("/core/playground")
public class PlaygroundController {
    private final FaqMatchServiceImpl faqMatchService;
    private final AiModelServiceImpl aiModelService;
    private final SafetyServiceImpl safetyService;

    public PlaygroundController(FaqMatchServiceImpl fms, AiModelServiceImpl ams, SafetyServiceImpl ss) {
        this.faqMatchService = fms;
        this.aiModelService = ams;
        this.safetyService = ss;
    }

    @PostMapping("/chat")
    public R<Map<String, Object>> chat(@RequestBody Map<String, String> body) {
        String text = body.getOrDefault("text", "");
        if (text == null || text.isEmpty()) {
            return R.fail(400, "消息不能为空");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        long startTime = System.currentTimeMillis();

        // 1. Safety pre-check
        SafetyResult safety = safetyService.checkUserInput(text);
        result.put("safetyPreCheck", Map.of(
            "blocked", safety.isBlocked(),
            "action", safety.getAction(),
            "hitRules", safety.getHitRules()
        ));

        if (safety.isBlocked()) {
            result.put("reply", PlainTextReplyFormatter.format(
                safety.getReplyText() != null ? safety.getReplyText() : "消息被安全规则拦截"));
            result.put("source", "safety");
            result.put("faqMatches", Collections.emptyList());
            result.put("latencyMs", System.currentTimeMillis() - startTime);
            return R.ok(result);
        }

        // 2. FAQ match
        Map<String, Object> faqResult = faqMatchService.match(text);
        boolean faqHit = faqResult != null && faqResult.containsKey("answer");
        List<Map<String, Object>> faqMatches = new ArrayList<>();
        if (faqHit) {
            Map<String, Object> match = new LinkedHashMap<>();
            match.put("answer", faqResult.get("answer"));
            match.put("itemId", faqResult.get("itemId"));
            match.put("confidence", faqResult.get("confidence"));
            faqMatches.add(match);
        }
        result.put("faqMatches", faqMatches);
        result.put("faqHit", faqHit);

        // 3. AI reply
        String replyText;
        Map<String, Object> aiDebug = new LinkedHashMap<>();
        if (faqHit) {
            replyText = (String) faqResult.get("answer");
            result.put("source", "faq");
            aiDebug.put("used", false);
            aiDebug.put("reason", "FAQ命中，跳过AI调用");
        } else {
            ChatResponse aiResp = aiModelService.chat(text);
            replyText = aiResp.getContent();
            result.put("source", "ai");
            aiDebug.put("used", true);
            aiDebug.put("model", aiResp.getModel());
            aiDebug.put("providerCode", aiResp.getProviderCode());
            aiDebug.put("inputTokens", aiResp.getInputTokens());
            aiDebug.put("outputTokens", aiResp.getOutputTokens());
            aiDebug.put("success", aiResp.isSuccess());
            // Cost estimation
            int costCents = 0;
            if ("deepseek".equals(aiResp.getProviderCode())) {
                costCents = (aiResp.getInputTokens() * 5 + aiResp.getOutputTokens() * 15) / 10000;
            } else {
                costCents = (aiResp.getInputTokens() * 15 + aiResp.getOutputTokens() * 60) / 10000;
            }
            aiDebug.put("costCents", costCents);

            // Safety post-check
            SafetyResult postCheck = safetyService.checkAiOutput(replyText);
            aiDebug.put("safetyPostCheck", Map.of(
                "blocked", postCheck.isBlocked(),
                "action", postCheck.getAction(),
                "hitRules", postCheck.getHitRules()
            ));
        }
        result.put("aiDebug", aiDebug);

        // 4. Final reply
        result.put("reply", PlainTextReplyFormatter.format(replyText));
        result.put("latencyMs", System.currentTimeMillis() - startTime);

        return R.ok(result);
    }
}
