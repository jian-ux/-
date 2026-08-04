package com.feisheng.bot.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.feisheng.bot.core.entity.BotIntent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BotIntentMapper extends BaseMapper<BotIntent> {
}
