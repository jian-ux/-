package com.feisheng.bot.admin.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.feisheng.bot.admin.entity.BotConversation;
import com.feisheng.bot.admin.entity.BotDailyStatistics;
import com.feisheng.bot.admin.entity.BotMessage;
import com.feisheng.bot.admin.mapper.BotConversationMapper;
import com.feisheng.bot.admin.mapper.BotDailyStatisticsMapper;
import com.feisheng.bot.admin.mapper.BotMessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Date;

@Component
public class StatisticsScheduledTask {
    private static final Logger log = LoggerFactory.getLogger(StatisticsScheduledTask.class);

    private final BotConversationMapper convMapper;
    private final BotMessageMapper msgMapper;
    private final BotDailyStatisticsMapper statsMapper;

    public StatisticsScheduledTask(BotConversationMapper convMapper, BotMessageMapper msgMapper,
                                    BotDailyStatisticsMapper statsMapper) {
        this.convMapper = convMapper;
        this.msgMapper = msgMapper;
        this.statsMapper = statsMapper;
    }

    @Scheduled(cron = "0 5 0 * * ?")
    public void aggregateDaily() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDateTime start = yesterday.atStartOfDay();
        LocalDateTime end = yesterday.atTime(LocalTime.MAX);

        try {
            Long convCount = convMapper.selectCount(
                    new QueryWrapper<BotConversation>().between("create_time", start, end));
            Long msgCount = msgMapper.selectCount(
                    new QueryWrapper<BotMessage>().between("create_time", start, end));
            Long transferCount = convMapper.selectCount(
                    new QueryWrapper<BotConversation>().between("update_time", start, end).eq("status", "transferred"));

            BotDailyStatistics existing = statsMapper.selectOne(
                    new LambdaQueryWrapper<BotDailyStatistics>().eq(BotDailyStatistics::getStatDate, yesterday));

            if (existing != null) {
                existing.setConversationCount((int) (long) convCount);
                existing.setMessageCount((int) (long) msgCount);
                existing.setTransferCount((int) (long) transferCount);
                statsMapper.updateById(existing);
            } else {
                BotDailyStatistics s = new BotDailyStatistics();
                s.setStatDate(Date.from(yesterday.atStartOfDay(ZoneId.systemDefault()).toInstant()));
                s.setConversationCount((int) (long) convCount);
                s.setMessageCount((int) (long) msgCount);
                s.setTransferCount((int) (long) transferCount);
                statsMapper.insert(s);
            }

            log.info("Daily statistics aggregated for {}: conv={}, msg={}, transfer={}",
                    yesterday, convCount, msgCount, transferCount);
        } catch (Exception e) {
            log.error("Daily statistics aggregation failed for {}", yesterday, e);
        }
    }
}
