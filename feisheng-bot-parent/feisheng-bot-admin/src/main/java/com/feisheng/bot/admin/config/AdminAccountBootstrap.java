package com.feisheng.bot.admin.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.admin.entity.SysRole;
import com.feisheng.bot.admin.entity.SysUser;
import com.feisheng.bot.admin.entity.SysUserRole;
import com.feisheng.bot.admin.mapper.SysRoleMapper;
import com.feisheng.bot.admin.mapper.SysUserMapper;
import com.feisheng.bot.admin.mapper.SysUserRoleMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminAccountBootstrap implements ApplicationRunner {
    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;

    public AdminAccountBootstrap(SysUserMapper userMapper, SysRoleMapper roleMapper,
                                 SysUserRoleMapper userRoleMapper, PasswordEncoder passwordEncoder,
                                 @Value("${admin.bootstrap.username:admin}") String username,
                                 @Value("${admin.bootstrap.password}") String password) {
        if (password == null || password.length() < 6) {
            throw new IllegalStateException("管理员初始密码至少需要 6 个字符");
        }
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        SysRole adminRole = roleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
            .eq(SysRole::getRoleKey, "admin"));
        if (adminRole == null) {
            throw new IllegalStateException("Admin role is missing; execute the database seed script first");
        }

        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
            .eq(SysUser::getUsername, username));
        if (user == null) {
            user = new SysUser();
            user.setUsername(username);
            user.setRealName("Administrator");
            user.setStatus(1);
            user.setPassword(passwordEncoder.encode(password));
            userMapper.insert(user);
        } else if (!passwordEncoder.matches(password, user.getPassword())) {
            SysUser passwordUpdate = new SysUser();
            passwordUpdate.setId(user.getId());
            passwordUpdate.setPassword(passwordEncoder.encode(password));
            passwordUpdate.setStatus(1);
            userMapper.updateById(passwordUpdate);
        }

        Long bindingCount = userRoleMapper.selectCount(new LambdaQueryWrapper<SysUserRole>()
            .eq(SysUserRole::getUserId, user.getId())
            .eq(SysUserRole::getRoleId, adminRole.getId()));
        if (bindingCount == 0) {
            SysUserRole binding = new SysUserRole();
            binding.setUserId(user.getId());
            binding.setRoleId(adminRole.getId());
            userRoleMapper.insert(binding);
        }
    }
}
