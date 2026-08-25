package com.feisheng.bot.admin.entity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;
import lombok.Data;
import lombok.EqualsAndHashCode;
@EqualsAndHashCode(callSuper = true)
@Data
@TableName("bot_conversation")
public class BotConversation extends BaseEntity {
    private String channelType;
    private String channelUserId;
    @TableField(exist = false)
    private String channelName;
    @TableField(exist = false)
    private String customerName;
    private String title;
    private String contextSummary;
    private Long summaryMessageId;
    private Date summaryUpdatedAt;
    private String status;
    private String priority;
    private Date slaDeadline;
    private Integer csatScore;
    private String csatFeedback;
    private String emotionLabel;
    private Double emotionScore;
    private String emotionTrend;
    private Integer negativeStreak;
    private String emotionRisk;
    private String handoffStatus;
    private Long assignedAgentId;
    private String assignedAgentName;
    private Date handoffTime;
    private Date acceptedTime;
    private Date resolvedTime;
    private Date lastHumanReplyTime;
}
