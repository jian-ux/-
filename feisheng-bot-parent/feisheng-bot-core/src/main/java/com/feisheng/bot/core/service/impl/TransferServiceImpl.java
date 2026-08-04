package com.feisheng.bot.core.service.impl;

import com.feisheng.bot.core.service.HandoffCoordinator;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class TransferServiceImpl {
    private final HandoffCoordinator handoffCoordinator;

    public TransferServiceImpl(HandoffCoordinator handoffCoordinator) {
        this.handoffCoordinator = handoffCoordinator;
    }

    public Map<String, Object> transfer(Long conversationId, String reason) {
        HandoffCoordinator.HandoffResult handoff = handoffCoordinator.handoff(
            conversationId,
            reason == null || reason.isBlank() ? "客户或客服手动转人工" : reason,
            "P1");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", handoff.success());
        result.put("message", handoff.success() ? "已转接人工客服" : handoff.error());
        result.put("ticketId", handoff.ticketId());
        result.put("created", handoff.created());
        result.put("summary", handoff.summary());
        return result;
    }
}
