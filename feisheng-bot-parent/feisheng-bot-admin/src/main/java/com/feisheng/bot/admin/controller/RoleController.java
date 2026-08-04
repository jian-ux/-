package com.feisheng.bot.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.admin.entity.SysRole;
import com.feisheng.bot.admin.entity.SysUserRole;
import com.feisheng.bot.admin.mapper.SysRoleMapper;
import com.feisheng.bot.admin.mapper.SysUserRoleMapper;
import com.feisheng.bot.common.exception.BusinessException;
import com.feisheng.bot.common.vo.R;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/role")
public class RoleController {
    private final SysRoleMapper mapper;
    private final SysUserRoleMapper userRoleMapper;

    public RoleController(SysRoleMapper mapper, SysUserRoleMapper userRoleMapper) {
        this.mapper = mapper;
        this.userRoleMapper = userRoleMapper;
    }

    @GetMapping("/list")
    public R<Page<SysRole>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(mapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<SysRole>().orderByDesc(SysRole::getCreateTime)));
    }

    @PostMapping("/save")
    public R<Void> save(@RequestBody SysRole role) {
        if (role.getId() != null) {
            SysRole existing = mapper.selectById(role.getId());
            if (existing == null) {
                throw new BusinessException(404, "角色不存在");
            }
            if ("admin".equalsIgnoreCase(existing.getRoleKey())) {
                role.setRoleKey("admin");
                role.setStatus(1);
            }
            mapper.updateById(role);
        } else {
            mapper.insert(role);
        }
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        SysRole role = mapper.selectById(id);
        if (role == null) {
            throw new BusinessException(404, "角色不存在");
        }
        if ("admin".equalsIgnoreCase(role.getRoleKey())) {
            throw new BusinessException(400, "不能删除超级管理员角色");
        }
        Long userCount = userRoleMapper.selectCount(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, id));
        if (userCount > 0) {
            throw new BusinessException(400, "请先解除所有用户与该角色的关联");
        }
        mapper.deleteById(id);
        return R.ok();
    }

    @GetMapping("/all")
    public R<List<SysRole>> all() {
        return R.ok(mapper.selectList(
                new LambdaQueryWrapper<SysRole>().eq(SysRole::getStatus, 1)));
    }
}
