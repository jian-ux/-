package com.feisheng.bot.admin.controller;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.admin.entity.BotDailyStatistics;
import com.feisheng.bot.admin.entity.BotTicket;
import com.feisheng.bot.admin.entity.BotConversation;
import com.feisheng.bot.admin.mapper.BotConversationMapper;
import com.feisheng.bot.admin.mapper.BotDailyStatisticsMapper;
import com.feisheng.bot.admin.mapper.BotTicketMapper;
import com.feisheng.bot.common.vo.R;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Map;
@RestController
@RequestMapping("/api/admin/statistics")
public class StatisticsController {
    private final BotConversationMapper convMapper; private final BotTicketMapper ticketMapper; private final BotDailyStatisticsMapper statsMapper;
    public StatisticsController(BotConversationMapper cm, BotTicketMapper tm, BotDailyStatisticsMapper sm) { convMapper=cm; ticketMapper=tm; statsMapper=sm; }
    @GetMapping("/overview") public R<Map<String,Object>> overview() {
        Map<String,Object> m = new HashMap<>();
        m.put("conversationCount", convMapper.selectCount(null));
        m.put("activeCount", convMapper.selectCount(new LambdaQueryWrapper<BotConversation>().apply("status = 'active'")));
        m.put("pendingTickets", ticketMapper.selectCount(new LambdaQueryWrapper<BotTicket>().eq(BotTicket::getStatus, "pending")));
        return R.ok(m);
    }
    @GetMapping("/daily") public R<Page<BotDailyStatistics>> daily(@RequestParam(defaultValue="1") int p, @RequestParam(defaultValue="30") int s) {
        return R.ok(statsMapper.selectPage(new Page<>(p,s), new LambdaQueryWrapper<BotDailyStatistics>().orderByDesc(BotDailyStatistics::getStatDate)));
    }
}
