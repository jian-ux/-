package com.feisheng.bot.admin.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import java.util.Date;
import lombok.Data;
import lombok.EqualsAndHashCode;
@EqualsAndHashCode(callSuper = true)
@Data
@TableName("bot_ticket")
public class BotTicket extends BaseEntity {
    private Long conversationId;
    private String title;
    private String description;
    private String status;
    private String priority;
    private Long assigneeId;
    private Date slaDeadline;
    private Date acceptedTime;
    private Date resolvedTime;
    private Date lastReplyTime;
    private String resolution;
    @TableField(exist = false)
    private String assigneeName;
}
