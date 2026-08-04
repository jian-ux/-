package com.feisheng.bot.admin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
@TableName("bot_forbidden_rule")
public class BotForbiddenRule extends BaseEntity {
    private String ruleType;
    private String pattern;
    private Integer isRegex;
    private String action;
    private String replyText;
    private String description;
    private Integer isEnabled;
    private Integer priority;
}
