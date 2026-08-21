package com.feisheng.bot.admin.service.impl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.admin.entity.SysUser;
import com.feisheng.bot.admin.mapper.SysUserMapper;
import com.feisheng.bot.admin.service.AuthService;
import com.feisheng.bot.admin.util.JwtUtil;
import com.feisheng.bot.admin.vo.LoginVO;
import com.feisheng.bot.common.exception.BusinessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
@Service
public class AuthServiceImpl implements AuthService {
    private final SysUserMapper userMapper; private final JwtUtil jwtUtil; private final PasswordEncoder passwordEncoder;
    public AuthServiceImpl(SysUserMapper m, JwtUtil j, PasswordEncoder pe) { userMapper=m; jwtUtil=j; passwordEncoder=pe; }
    @Override
    public LoginVO login(String username, String password) {
        SysUser u = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (u == null || !passwordEncoder.matches(password, u.getPassword())) throw new BusinessException(401, "账号或密码错误");
        if (u.getStatus() != null && u.getStatus() == 0) throw new BusinessException(403, "账号已禁用");
        String token = jwtUtil.generateToken(u.getId(), u.getUsername());
        return new LoginVO(token, userInfo(u));
    }
    @Override
    public LoginVO.UserInfo getUserInfo(Long userId) {
        SysUser u = userMapper.selectById(userId); if (u == null) throw new BusinessException(404, "用户不存在");
        return userInfo(u);
    }

    private LoginVO.UserInfo userInfo(SysUser user) {
        return new LoginVO.UserInfo(user.getId(), user.getUsername(), user.getRealName(),
            userMapper.selectPermissionsByUserId(user.getId()));
    }
}
