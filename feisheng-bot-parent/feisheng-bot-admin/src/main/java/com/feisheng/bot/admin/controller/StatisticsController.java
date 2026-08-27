package com.feisheng.bot.admin.controller;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.admin.entity.BotDailyStatistics;
import com.feisheng.bot.admin.entity.BotTicket;
import com.feisheng.bot.admin.entity.BotConversation;
import com.feisheng.bot.admin.mapper.BotConversationMapper;
import com.feisheng.bot.admin.mapper.BotCustomerMapper;
import com.feisheng.bot.admin.mapper.BotDailyStatisticsMapper;
import com.feisheng.bot.admin.mapper.BotMessageMapper;
import com.feisheng.bot.admin.mapper.BotTicketMapper;
import com.feisheng.bot.common.vo.R;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Date;
import java.util.Map;
import java.time.LocalDate;
import java.time.ZoneId;
@RestController
@RequestMapping("/api/admin/statistics")
public class StatisticsController {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final BotConversationMapper convMapper;
    private final BotTicketMapper ticketMapper;
    private final BotDailyStatisticsMapper statsMapper;
    private final BotCustomerMapper customerMapper;
    private final BotMessageMapper messageMapper;

    public StatisticsController(BotConversationMapper convMapper,
                                BotTicketMapper ticketMapper,
                                BotDailyStatisticsMapper statsMapper,
                                BotCustomerMapper customerMapper,
                                BotMessageMapper messageMapper) {
        this.convMapper = convMapper;
        this.ticketMapper = ticketMapper;
        this.statsMapper = statsMapper;
        this.customerMapper = customerMapper;
        this.messageMapper = messageMapper;
    }

    @GetMapping("/overview") public R<Map<String,Object>> overview() {
        Map<String,Object> m = new HashMap<>();
        Date today = Date.from(LocalDate.now(BUSINESS_ZONE)
            .atStartOfDay(BUSINESS_ZONE).toInstant());
        long ticketCount = ticketMapper.selectCount(null);
        long resolvedTickets = ticketMapper.selectCount(
            new LambdaQueryWrapper<BotTicket>().eq(BotTicket::getStatus, "resolved"));
        BotDailyStatistics latest = statsMapper.selectOne(
            new LambdaQueryWrapper<BotDailyStatistics>()
                .orderByDesc(BotDailyStatistics::getStatDate)
                .last("LIMIT 1"));

        m.put("conversationCount", convMapper.selectCount(null));
        m.put("todayConversations", convMapper.selectCount(
            new LambdaQueryWrapper<BotConversation>().ge(BotConversation::getCreateTime, today)));
        m.put("activeCount", convMapper.selectCount(
            new LambdaQueryWrapper<BotConversation>().eq(BotConversation::getStatus, "active")));
        m.put("todayMessages", messageMapper.selectCount(
            new LambdaQueryWrapper<com.feisheng.bot.admin.entity.BotMessage>()
                .ge(com.feisheng.bot.admin.entity.BotMessage::getCreateTime, today)));
        m.put("customerCount", customerMapper.selectCount(
            new LambdaQueryWrapper<com.feisheng.bot.admin.entity.BotCustomer>()
                .apply("LOWER(TRIM(channel_type)) <> {0}", "playground")));
        m.put("ticketCount", ticketCount);
        m.put("pendingTickets", ticketMapper.selectCount(
            new LambdaQueryWrapper<BotTicket>().eq(BotTicket::getStatus, "pending")));
        m.put("processingTickets", ticketMapper.selectCount(
            new LambdaQueryWrapper<BotTicket>().eq(BotTicket::getStatus, "processing")));
        m.put("resolvedTickets", resolvedTickets);
        m.put("resolutionRate", ticketCount == 0 ? 0
            : Math.round(resolvedTickets * 1000.0 / ticketCount) / 10.0);
        m.put("faqHitCount", latest == null || latest.getFaqHitCount() == null
            ? 0 : latest.getFaqHitCount());
        return R.ok(m);
    }
    @GetMapping("/daily") public R<Page<BotDailyStatistics>> daily(
            @RequestParam(defaultValue="1") int p,
            @RequestParam(defaultValue="7") int s) {
        return R.ok(statsMapper.selectPage(
            new Page<>(Math.max(p, 1), Math.min(Math.max(s, 1), 100)),
            new LambdaQueryWrapper<BotDailyStatistics>()
                .orderByDesc(BotDailyStatistics::getStatDate)));
    }
}
