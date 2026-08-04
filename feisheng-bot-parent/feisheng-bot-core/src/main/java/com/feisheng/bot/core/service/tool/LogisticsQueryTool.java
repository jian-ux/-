package com.feisheng.bot.core.service.tool;

import com.feisheng.bot.core.entity.BotMessage;
import com.feisheng.bot.core.service.BusinessDataProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Pattern;

@Component
public class LogisticsQueryTool implements CustomerServiceTool {
    private static final Pattern INTENT = Pattern.compile(
        "物流|快递|配送进度|发货到哪|到哪了|什么时候到|预计送达|运单"
            + "|发货了吗|什么时候发货|啥时候发货|多久发货");

    private final BusinessDataProvider provider;
    private final OrderReferenceResolver referenceResolver;

    @Value("${business.display-zone:Asia/Shanghai}")
    private String displayZone = "Asia/Shanghai";

    public LogisticsQueryTool(BusinessDataProvider provider,
                              OrderReferenceResolver referenceResolver) {
        this.provider = provider;
        this.referenceResolver = referenceResolver;
    }

    @Override
    public String name() {
        return "logistics.query";
    }

    @Override
    public boolean matches(String question, List<BotMessage> recentMessages) {
        if (question != null && INTENT.matcher(question).find()) return true;
        return referenceResolver.hasReference(question)
            && lastAssistantAskedForLogistics(recentMessages);
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionContext context) {
        BusinessDataProvider.QueryResult<BusinessDataProvider.LogisticsView> result =
            provider.findLogistics(context.identity(), context.orderNo(), context.requestId());
        return switch (result.status()) {
            case FOUND -> new ToolExecutionResult(result.status(), format(result.data()), provider.providerCode());
            case NOT_FOUND -> new ToolExecutionResult(result.status(),
                "未查询到该订单的物流信息，请检查订单号或稍后再试。", provider.providerCode());
            case FORBIDDEN -> new ToolExecutionResult(result.status(),
                "当前账号无法验证该订单的归属，已为您转接人工客服核实。", provider.providerCode());
            case UNAVAILABLE, ERROR -> new ToolExecutionResult(result.status(),
                "物流查询服务暂时不可用，已为您转接人工客服处理。", provider.providerCode());
        };
    }

    private String format(BusinessDataProvider.LogisticsView logistics) {
        StringBuilder reply = new StringBuilder("订单 ").append(logistics.orderNo());
        if (hasText(logistics.carrier())) reply.append(" 由").append(logistics.carrier()).append("承运");
        if (hasText(logistics.trackingNo())) reply.append("，运单号：").append(logistics.trackingNo());
        if (hasText(logistics.status())) reply.append("；物流状态：").append(logistics.status());
        if (hasText(logistics.latestEvent())) reply.append("；最新进展：").append(logistics.latestEvent());
        if (logistics.latestEventTime() != null) {
            reply.append("（")
                .append(formatDate(logistics.latestEventTime()))
                .append("）");
        }
        if (logistics.estimatedDeliveryTime() != null) {
            reply.append("；预计送达：")
                .append(formatDate(logistics.estimatedDeliveryTime()));
        }
        return reply.append("。").toString();
    }

    private boolean lastAssistantAskedForLogistics(List<BotMessage> messages) {
        if (messages == null) return false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            BotMessage message = messages.get(i);
            if (message == null || !"ai".equals(message.getRole())) continue;
            String content = message.getContent();
            return content != null && content.contains("订单号") && content.contains("物流");
        }
        return false;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String formatDate(java.util.Date value) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        format.setTimeZone(TimeZone.getTimeZone(displayZone));
        return format.format(value);
    }
}
