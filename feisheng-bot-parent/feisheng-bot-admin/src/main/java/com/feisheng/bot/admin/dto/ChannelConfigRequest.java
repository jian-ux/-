package com.feisheng.bot.admin.dto;

import lombok.Data;

@Data
public class ChannelConfigRequest {
    private Long id;
    private String channelType;
    private String channelName;
    private Integer status;

    private String connectionMode;
    private String clientId;
    private String clientSecret;
    private String robotCode;

    private String corpId;
    private String corpSecret;
    private String agentId;

    private String endpoint;
    private String accessToken;
}
