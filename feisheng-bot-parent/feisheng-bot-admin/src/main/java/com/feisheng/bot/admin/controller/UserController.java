package com.feisheng.bot.admin.controller;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.admin.entity.SysUser;
import com.feisheng.bot.admin.entity.SysUserRole;
import com.feisheng.bot.admin.mapper.SysUserMapper;
import com.feisheng.bot.admin.mapper.SysUserRoleMapper;
import com.feisheng.bot.common.vo.R;
import com.feisheng.bot.common.exception.BusinessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/api/admin/user")
public class UserController {
    private final SysUserMapper mapper; private final PasswordEncoder passwordEncoder;
    private final SysUserRoleMapper roleMapper;
    public UserController(SysUserMapper m, PasswordEncoder pe, SysUserRoleMapper rm) { mapper=m; passwordEncoder=pe; roleMapper=rm; }
    @GetMapping("/list") public R<Page<SysUser>> list(
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) String username, @RequestParam(required=false) Integer status) {
        LambdaQueryWrapper<SysUser> q = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(username)) q.like(SysUser::getUsername, username);
        if (status != null) q.eq(SysUser::getStatus, status);
        return R.ok(mapper.selectPage(new Page<>(page, size), q));
    }
    @PostMapping("/add") public R<Long> add(@RequestBody SysUser u) {
        if (!StringUtils.hasText(u.getUsername()) || !StringUtils.hasText(u.getPassword())) {
            throw new BusinessException(400, "登录账号和密码不能为空");
        }
        u.setPassword(passwordEncoder.encode(u.getPassword()));
        mapper.insert(u);
        return R.ok(u.getId());
    }
    @PutMapping("/update") public R<Void> update(@RequestBody SysUser u) {
        if (StringUtils.hasText(u.getPassword())) u.setPassword(passwordEncoder.encode(u.getPassword()));
        else u.setPassword(null);
        mapper.updateById(u);
        return R.ok();
    }
    @DeleteMapping("/{id}") public R<Void> delete(@PathVariable Long id) { mapper.deleteById(id); return R.ok(); }

    @GetMapping("/{id}/roles")
    public R<List<Long>> getUserRoles(@PathVariable Long id) {
        return R.ok(roleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, id))
                .stream().map(SysUserRole::getRoleId).toList());
    }

    @PutMapping("/{id}/roles")
    public R<Void> setUserRoles(@PathVariable Long id, @RequestBody List<Long> roleIds) {
        roleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, id));
        for (Long roleId : roleIds) {
            SysUserRole ur = new SysUserRole();
            ur.setUserId(id);
            ur.setRoleId(roleId);
            roleMapper.insert(ur);
        }
        return R.ok();
    }
}
