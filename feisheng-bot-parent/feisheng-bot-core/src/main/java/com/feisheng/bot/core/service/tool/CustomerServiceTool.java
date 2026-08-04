package com.feisheng.bot.core.service.tool;

import com.feisheng.bot.core.entity.BotMessage;
import com.feisheng.bot.core.service.BusinessDataProvider;

import java.util.List;

public interface CustomerServiceTool {
    String name();

    boolean matches(String question, List<BotMessage> recentMessages);

    ToolExecutionResult execute(ToolExecutionContext context);

    record ToolExecutionContext(Long conversationId,
                                BusinessDataProvider.QueryIdentity identity,
                                String question, String orderNo,
                                String requestId) {}

    record ToolExecutionResult(BusinessDataProvider.QueryStatus status,
                               String reply, String providerCode) {}
}
