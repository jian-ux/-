package com.feisheng.bot.admin.dto;

import lombok.Data;

import java.util.Date;

@Data
public class ChannelConfigView {
    private Long id;
    private String channelType;
    private String channelName;
    private Integer status;
    private Date createTime;
    private Date updateTime;

    private String connectionMode;
    private String clientId;
    private String robotCode;
    private boolean clientSecretConfigured;

    private String corpId;
    private String agentId;
    private boolean corpSecretConfigured;
    private boolean callbackTokenConfigured;
    private boolean callbackAesKeyConfigured;

    private String endpoint;
    private boolean accessTokenConfigured;
    private String configSummary;
    private String connectionStatus;
}
