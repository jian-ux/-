package com.feisheng.bot.core.service.tool;

import com.feisheng.bot.core.entity.BotMessage;
import com.feisheng.bot.core.service.BusinessDataProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

@Component
public class OrderQueryTool implements CustomerServiceTool {
    private static final Pattern INTENT = Pattern.compile(
        "查(?:询)?(?:一下)?(?:订单|这个单|我的单)|订单(?:状态|情况|详情|进度|怎么样|咋样)"
            + "|单子(?:状态|情况|详情|进度|怎么样|咋样)|支付状态|付款状态|有没有支付|是否支付");

    private final BusinessDataProvider provider;
    private final OrderReferenceResolver referenceResolver;

    @Value("${business.display-zone:Asia/Shanghai}")
    private String displayZone = "Asia/Shanghai";

    public OrderQueryTool(BusinessDataProvider provider,
                          OrderReferenceResolver referenceResolver) {
        this.provider = provider;
        this.referenceResolver = referenceResolver;
    }

    @Override
    public String name() {
        return "order.query";
    }

    @Override
    public boolean matches(String question, List<BotMessage> recentMessages) {
        if (question != null && INTENT.matcher(question).find()) return true;
        return referenceResolver.hasReference(question)
            && lastAssistantAskedFor(recentMessages, "订单号", "物流");
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionContext context) {
        BusinessDataProvider.QueryResult<BusinessDataProvider.OrderView> result =
            provider.findOrder(context.identity(), context.orderNo(), context.requestId());
        return switch (result.status()) {
            case FOUND -> new ToolExecutionResult(result.status(), format(result.data()), provider.providerCode());
            case NOT_FOUND -> new ToolExecutionResult(result.status(),
                "未查询到对应订单，请检查订单号后重新发送。", provider.providerCode());
            case FORBIDDEN -> new ToolExecutionResult(result.status(),
                "当前账号无法验证该订单的归属，已为您转接人工客服核实。", provider.providerCode());
            case UNAVAILABLE, ERROR -> new ToolExecutionResult(result.status(),
                "订单查询服务暂时不可用，已为您转接人工客服处理。", provider.providerCode());
        };
    }

    private String format(BusinessDataProvider.OrderView order) {
        StringBuilder reply = new StringBuilder("订单 ").append(order.orderNo());
        if (hasText(order.status())) reply.append(" 当前状态：").append(order.status());
        if (hasText(order.paymentStatus())) reply.append("；支付状态：").append(order.paymentStatus());
        if (hasText(order.itemSummary())) reply.append("；商品：").append(order.itemSummary());
        if (order.amountCents() != null) {
            String currency = hasText(order.currency()) ? order.currency().toUpperCase(Locale.ROOT) : "CNY";
            reply.append("；金额：").append("CNY".equals(currency) ? "¥" : currency + " ")
                .append(String.format(Locale.ROOT, "%.2f", order.amountCents() / 100.0));
        }
        if (order.orderTime() != null) {
            reply.append("；下单时间：")
                .append(formatDate(order.orderTime()));
        }
        return reply.append("。").toString();
    }

    private boolean lastAssistantAskedFor(List<BotMessage> messages,
                                          String marker, String excludedMarker) {
        if (messages == null) return false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            BotMessage message = messages.get(i);
            if (message == null || !"ai".equals(message.getRole())) continue;
            String content = message.getContent();
            return content != null && content.contains(marker)
                && (excludedMarker == null || !content.contains(excludedMarker));
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
