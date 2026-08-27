package com.feisheng.bot.core.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.util.Date;
@Data
@TableName("bot_conversation")
public class BotConversation {
    @TableId(type = IdType.AUTO) private Long id;
    private String channelType; private String channelUserId; private String title;
    private String contextSummary; private Long summaryMessageId; private Date summaryUpdatedAt;
    private String dialogState; private Long dialogStateVersion;
    private String status; private String priority; private Date slaDeadline;
    private Integer csatScore; private String csatFeedback;
    private String emotionLabel; private Double emotionScore; private String emotionTrend;
    private Integer negativeStreak; private String emotionRisk;
    private String handoffStatus; private Long assignedAgentId; private String assignedAgentName;
    private Date handoffTime; private Date acceptedTime; private Date resolvedTime;
    private Date lastHumanReplyTime;
    private Date createTime; private Date updateTime;
    @TableLogic private Integer deleted;
}
