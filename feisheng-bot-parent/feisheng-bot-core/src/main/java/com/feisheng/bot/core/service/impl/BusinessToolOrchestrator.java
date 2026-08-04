package com.feisheng.bot.core.service.impl;

import com.feisheng.bot.core.entity.BotMessage;
import com.feisheng.bot.core.entity.BotToolExecutionLog;
import com.feisheng.bot.core.mapper.BotToolExecutionLogMapper;
import com.feisheng.bot.core.service.BusinessDataProvider;
import com.feisheng.bot.core.service.tool.CustomerServiceTool;
import com.feisheng.bot.core.service.tool.OrderReferenceResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class BusinessToolOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(BusinessToolOrchestrator.class);

    private final List<CustomerServiceTool> tools;
    private final OrderReferenceResolver referenceResolver;
    private final BotToolExecutionLogMapper executionLogMapper;
    private final ObjectMapper objectMapper;

    public BusinessToolOrchestrator(List<CustomerServiceTool> tools,
                                    OrderReferenceResolver referenceResolver,
                                    BotToolExecutionLogMapper executionLogMapper,
                                    ObjectMapper objectMapper) {
        this.tools = tools.stream().sorted(Comparator.comparing(CustomerServiceTool::name)).toList();
        this.referenceResolver = referenceResolver;
        this.executionLogMapper = executionLogMapper;
        this.objectMapper = objectMapper;
    }

    public ToolRoutingResult route(Long conversationId, String channelType,
                                   String channelUserId, String question,
                                   List<BotMessage> recentMessages) {
        List<CustomerServiceTool> selected = tools.stream()
            .filter(tool -> tool.matches(question, recentMessages))
            .toList();
        if (selected.isEmpty()) return ToolRoutingResult.notHandled();

        String orderNo = referenceResolver.resolve(question, recentMessages);
        String rootRequestId = UUID.randomUUID().toString();
        if (orderNo == null) {
            String reply = selected.size() == 1 && "logistics.query".equals(selected.get(0).name())
                ? "请提供需要查询物流的订单号，例如 FS202607170001。"
                : "请提供要查询的订单号，例如 FS202607170001。";
            List<ToolExecutionSummary> summaries = new ArrayList<>();
            for (CustomerServiceTool tool : selected) {
                summaries.add(new ToolExecutionSummary(
                    tool.name(), rootRequestId, "NEEDS_INPUT", "none", 0));
                saveAudit(conversationId, rootRequestId, tool.name(), "none",
                    "NEEDS_INPUT", null, 0);
            }
            return new ToolRoutingResult(true, reply, "needs_input", false, null,
                List.copyOf(summaries));
        }

        BusinessDataProvider.QueryIdentity identity =
            new BusinessDataProvider.QueryIdentity(channelType, channelUserId);
        Set<String> replies = new LinkedHashSet<>();
        List<ToolExecutionSummary> summaries = new ArrayList<>();
        boolean needsTransfer = false;
        String transferReason = null;
        boolean anySuccess = false;
        boolean anyNotFound = false;

        for (int i = 0; i < selected.size(); i++) {
            CustomerServiceTool tool = selected.get(i);
            String requestId = selected.size() == 1 ? rootRequestId : rootRequestId + "-" + (i + 1);
            long started = System.currentTimeMillis();
            CustomerServiceTool.ToolExecutionResult execution;
            try {
                execution = tool.execute(new CustomerServiceTool.ToolExecutionContext(
                    conversationId, identity, question, orderNo, requestId));
            } catch (Exception e) {
                log.error("Customer service tool {} failed, requestId={}", tool.name(), requestId, e);
                execution = new CustomerServiceTool.ToolExecutionResult(
                    BusinessDataProvider.QueryStatus.ERROR,
                    "业务查询服务暂时不可用，已为您转接人工客服处理。", "unknown");
            }
            int latencyMs = (int) Math.min(Integer.MAX_VALUE,
                System.currentTimeMillis() - started);
            replies.add(execution.reply());
            summaries.add(new ToolExecutionSummary(tool.name(), requestId,
                execution.status().name(), execution.providerCode(), latencyMs));
            saveAudit(conversationId, requestId, tool.name(), execution.providerCode(),
                execution.status().name(), orderNo, latencyMs);

            if (execution.status() == BusinessDataProvider.QueryStatus.FOUND) anySuccess = true;
            if (execution.status() == BusinessDataProvider.QueryStatus.NOT_FOUND) anyNotFound = true;
            if (execution.status() == BusinessDataProvider.QueryStatus.FORBIDDEN) {
                needsTransfer = true;
                transferReason = "业务数据归属校验失败";
            } else if (execution.status() == BusinessDataProvider.QueryStatus.UNAVAILABLE
                    || execution.status() == BusinessDataProvider.QueryStatus.ERROR) {
                needsTransfer = true;
                if (transferReason == null) transferReason = "业务查询工具不可用";
            }
        }

        String status = needsTransfer ? "error"
            : anySuccess ? "answered"
            : anyNotFound ? "not_found" : "error";
        return new ToolRoutingResult(true, String.join("\n", replies), status,
            needsTransfer, transferReason, List.copyOf(summaries));
    }

    private void saveAudit(Long conversationId, String requestId, String toolName,
                           String providerCode, String status, String orderNo,
                           int latencyMs) {
        try {
            BotToolExecutionLog audit = new BotToolExecutionLog();
            audit.setConversationId(conversationId);
            audit.setRequestId(requestId);
            audit.setToolName(toolName);
            audit.setProviderCode(providerCode);
            audit.setStatus(status);
            audit.setInputJson(objectMapper.writeValueAsString(Map.of(
                "orderRef", referenceResolver.mask(orderNo))));
            audit.setOutputSummary("status=" + status);
            audit.setLatencyMs(latencyMs);
            executionLogMapper.insert(audit);
        } catch (Exception e) {
            log.warn("Could not persist tool audit for request {}: {}", requestId, e.getMessage());
        }
    }

    public record ToolExecutionSummary(String toolName, String requestId,
                                       String status, String providerCode,
                                       int latencyMs) {}

    public record ToolRoutingResult(boolean handled, String reply, String status,
                                    boolean needsTransfer, String transferReason,
                                    List<ToolExecutionSummary> executions) {
        public static ToolRoutingResult notHandled() {
            return new ToolRoutingResult(false, null, null, false, null, List.of());
        }
    }
}
