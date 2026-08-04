package com.feisheng.bot.admin.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
@EqualsAndHashCode(callSuper = true)
@Data
@TableName("bot_channel_config")
public class BotChannelConfig extends BaseEntity {
    private String channelType;
    private String channelName;
    private String configJson;
    private Integer status;
}