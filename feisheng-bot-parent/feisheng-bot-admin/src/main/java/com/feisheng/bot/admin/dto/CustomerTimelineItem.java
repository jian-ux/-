package com.feisheng.bot.admin.dto;

import lombok.Data;

import java.util.Date;

@Data
public class CustomerTimelineItem {
    private Long messageId;
    private Long conversationId;
    private String conversationTitle;
    private String conversationStatus;
    private String role;
    private String contentType;
    private String content;
    private String metadata;
    private Date createTime;
}
