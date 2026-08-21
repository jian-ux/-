package com.feisheng.bot.admin.controller;

import com.feisheng.bot.admin.entity.SysPermission;
import com.feisheng.bot.admin.entity.SysUser;
import com.feisheng.bot.admin.mapper.SysPermissionMapper;
import com.feisheng.bot.admin.mapper.SysUserMapper;
import com.feisheng.bot.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PermissionControllerTest {
    @Test
    void assignmentReplacesPermissionsAndRemovesDuplicates() {
        SysPermissionMapper permissionMapper = mock(SysPermissionMapper.class);
        SysUserMapper userMapper = mock(SysUserMapper.class);
        when(userMapper.selectById(8L)).thenReturn(new SysUser());
        when(permissionMapper.selectBatchIds(List.of(2L, 3L)))
            .thenReturn(List.of(enabledPermission(2L), enabledPermission(3L)));
        PermissionController controller = new PermissionController(permissionMapper, userMapper);

        controller.assignUserPermissions(8L, List.of(2L, 2L, 3L));

        verify(userMapper).deleteDirectPermissions(8L);
        verify(userMapper).insertDirectPermission(8L, 2L);
        verify(userMapper).insertDirectPermission(8L, 3L);
    }

    @Test
    void invalidPermissionDoesNotClearExistingAssignment() {
        SysPermissionMapper permissionMapper = mock(SysPermissionMapper.class);
        SysUserMapper userMapper = mock(SysUserMapper.class);
        when(userMapper.selectById(8L)).thenReturn(new SysUser());
        when(permissionMapper.selectBatchIds(List.of(99L))).thenReturn(List.of());
        PermissionController controller = new PermissionController(permissionMapper, userMapper);

        assertThrows(BusinessException.class,
            () -> controller.assignUserPermissions(8L, List.of(99L)));

        verify(userMapper, never()).deleteDirectPermissions(8L);
    }

    private SysPermission enabledPermission(Long id) {
        SysPermission permission = new SysPermission();
        permission.setId(id);
        permission.setStatus(1);
        return permission;
    }
}
