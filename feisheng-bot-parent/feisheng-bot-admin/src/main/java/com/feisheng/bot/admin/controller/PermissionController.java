package com.feisheng.bot.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.admin.entity.SysPermission;
import com.feisheng.bot.admin.entity.SysUser;
import com.feisheng.bot.admin.mapper.SysPermissionMapper;
import com.feisheng.bot.admin.mapper.SysUserMapper;
import com.feisheng.bot.common.exception.BusinessException;
import com.feisheng.bot.common.vo.R;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/admin/permission")
public class PermissionController {
    private final SysPermissionMapper mapper;
    private final SysUserMapper userMapper;

    public PermissionController(SysPermissionMapper mapper, SysUserMapper userMapper) {
        this.mapper = mapper;
        this.userMapper = userMapper;
    }

    @GetMapping("/tree")
    public R<List<Map<String, Object>>> tree() {
        List<SysPermission> all = mapper.selectList(
                new LambdaQueryWrapper<SysPermission>()
                        .eq(SysPermission::getStatus, 1)
                        .orderByAsc(SysPermission::getSort));

        Map<Long, Map<String, Object>> nodeMap = new LinkedHashMap<>();
        for (SysPermission p : all) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", p.getId());
            n.put("label", p.getName());
            n.put("parentId", p.getParentId());
            n.put("permission", p.getPermission());
            n.put("path", p.getPath());
            n.put("icon", p.getIcon());
            n.put("type", p.getType());
            n.put("sort", p.getSort());
            n.put("children", new ArrayList<>());
            nodeMap.put(p.getId(), n);
        }

        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> n : nodeMap.values()) {
            Long parentId = (Long) n.get("parentId");
            if (parentId == null || parentId == 0 || !nodeMap.containsKey(parentId)) {
                roots.add(n);
            } else {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> children = (List<Map<String, Object>>) nodeMap.get(parentId).get("children");
                children.add(n);
            }
        }
        return R.ok(roots);
    }

    @GetMapping("/users")
    public R<List<PermissionUser>> users() {
        return R.ok(userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .orderByDesc(SysUser::getCreateTime)).stream()
            .filter(user -> !userMapper.selectRolesByUserId(user.getId()).contains("ROLE_ADMIN"))
            .map(user -> new PermissionUser(
                user.getId(), user.getUsername(), user.getRealName(), user.getStatus()))
            .toList());
    }

    @GetMapping("/user/{id}")
    public R<List<Long>> userPermissions(@PathVariable Long id) {
        requireUser(id);
        return R.ok(userMapper.selectDirectPermissionIds(id));
    }

    @PutMapping("/user/{id}")
    @Transactional
    public R<Void> assignUserPermissions(
            @PathVariable Long id,
            @RequestBody(required = false) List<Long> permissionIds) {
        requireUser(id);
        List<Long> ids = Optional.ofNullable(permissionIds).orElseGet(List::of).stream()
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (!ids.isEmpty()) {
            List<SysPermission> permissions = mapper.selectBatchIds(ids).stream()
                .filter(permission -> Integer.valueOf(1).equals(permission.getStatus()))
                .toList();
            if (permissions.size() != ids.size()) {
                throw new BusinessException(400, "包含无效或已停用的模块权限");
            }
        }
        userMapper.deleteDirectPermissions(id);
        ids.forEach(permissionId -> userMapper.insertDirectPermission(id, permissionId));
        return R.ok();
    }

    @PostMapping("/save")
    public R<Void> save(@RequestBody SysPermission permission) {
        if (permission.getId() != null) {
            mapper.updateById(permission);
        } else {
            mapper.insert(permission);
        }
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        mapper.deleteById(id);
        return R.ok();
    }

    private void requireUser(Long id) {
        if (userMapper.selectById(id) == null) {
            throw new BusinessException(404, "用户不存在");
        }
    }

    public record PermissionUser(Long id, String username, String realName, Integer status) {}
}
