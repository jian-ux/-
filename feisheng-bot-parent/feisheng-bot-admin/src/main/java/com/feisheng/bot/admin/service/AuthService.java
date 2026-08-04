package com.feisheng.bot.admin.service;
import com.feisheng.bot.admin.vo.LoginVO;
public interface AuthService { LoginVO login(String username, String password); LoginVO.UserInfo getUserInfo(Long userId); }