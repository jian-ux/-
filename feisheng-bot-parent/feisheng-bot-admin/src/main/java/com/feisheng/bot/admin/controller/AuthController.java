package com.feisheng.bot.admin.controller;
import com.feisheng.bot.admin.dto.LoginRequest;
import com.feisheng.bot.admin.service.AuthService;
import com.feisheng.bot.admin.vo.LoginVO;
import com.feisheng.bot.common.vo.R;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/admin")
public class AuthController {
    private final AuthService authService;
    public AuthController(AuthService a) { authService=a; }
    @PostMapping("/login") public R<LoginVO> login(@RequestBody LoginRequest req) { return R.ok(authService.login(req.getUsername(), req.getPassword())); }
    @GetMapping("/user/info") public R<LoginVO.UserInfo> info(Authentication auth) { return R.ok(authService.getUserInfo((Long)auth.getPrincipal())); }
}