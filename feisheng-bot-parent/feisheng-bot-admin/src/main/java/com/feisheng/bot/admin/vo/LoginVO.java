package com.feisheng.bot.admin.vo;
public class LoginVO {
    private String token; private UserInfo userInfo;
    public LoginVO(String token, UserInfo userInfo) { this.token=token; this.userInfo=userInfo; }
    public String getToken() { return token; } public UserInfo getUserInfo() { return userInfo; }
    public static class UserInfo {
        private Long id; private String username; private String realName;
        public UserInfo(Long id, String username, String realName) { this.id=id; this.username=username; this.realName=realName; }
        public Long getId() { return id; } public String getUsername() { return username; } public String getRealName() { return realName; }
    }
}