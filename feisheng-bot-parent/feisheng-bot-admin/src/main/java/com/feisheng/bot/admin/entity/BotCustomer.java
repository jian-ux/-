package com.feisheng.bot.admin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.Date;

@EqualsAndHashCode(callSuper = true)
@Data
@TableName("bot_customer")
public class BotCustomer extends BaseEntity {
    private String name;
    private String phone;
    private String email;
    private String remark;
    private String channelType;
    private String channelUserId;
    private String nickname;
    private String avatar;
    private Integer totalConversations;
    private Date lastContactTime;
}
