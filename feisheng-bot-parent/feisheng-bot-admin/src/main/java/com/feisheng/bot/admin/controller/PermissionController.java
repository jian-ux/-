package com.feisheng.bot.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.admin.entity.SysPermission;
import com.feisheng.bot.admin.mapper.SysPermissionMapper;
import com.feisheng.bot.common.vo.R;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/admin/permission")
public class PermissionController {
    private final SysPermissionMapper mapper;

    public PermissionController(SysPermissionMapper mapper) {
        this.mapper = mapper;
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
}
