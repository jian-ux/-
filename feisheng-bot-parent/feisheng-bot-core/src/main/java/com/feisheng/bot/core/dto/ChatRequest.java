package com.feisheng.bot.core.dto;
import java.util.List;
public class ChatRequest {
    private String model; private List<Message> messages; private Double temperature;
    public String getModel() { return model; } public void setModel(String m) { model=m; }
    public List<Message> getMessages() { return messages; } public void setMessages(List<Message> m) { messages=m; }
    public Double getTemperature() { return temperature; } public void setTemperature(Double t) { temperature=t; }
    public static class Message { private String role; private String content;
        public Message() {} public Message(String role, String content) { this.role=role; this.content=content; }
        public String getRole() { return role; } public void setRole(String r) { role=r; }
        public String getContent() { return content; } public void setContent(String c) { content=c; }
    }
}