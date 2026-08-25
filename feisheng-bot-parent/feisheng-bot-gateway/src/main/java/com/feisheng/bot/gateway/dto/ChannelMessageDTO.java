package com.feisheng.bot.gateway.dto;
public class ChannelMessageDTO {
    private String channelType; private String channelUserId; private String msgId;
    private String senderName; private String senderAvatar;
    private String content; private String msgType; private Long timestamp;
    private String messageContentType; private String messageMetadata;
    public String getChannelType() { return channelType; } public void setChannelType(String c) { channelType=c; }
    public String getChannelUserId() { return channelUserId; } public void setChannelUserId(String c) { channelUserId=c; }
    public String getMsgId() { return msgId; } public void setMsgId(String m) { msgId=m; }
    public String getSenderName() { return senderName; } public void setSenderName(String n) { senderName=n; }
    public String getSenderAvatar() { return senderAvatar; } public void setSenderAvatar(String a) { senderAvatar=a; }
    public String getContent() { return content; } public void setContent(String c) { content=c; }
    public String getMsgType() { return msgType; } public void setMsgType(String m) { msgType=m; }
    public Long getTimestamp() { return timestamp; } public void setTimestamp(Long t) { timestamp=t; }
    public String getMessageContentType() { return messageContentType; }
    public void setMessageContentType(String value) { messageContentType = value; }
    public String getMessageMetadata() { return messageMetadata; }
    public void setMessageMetadata(String value) { messageMetadata = value; }
}
