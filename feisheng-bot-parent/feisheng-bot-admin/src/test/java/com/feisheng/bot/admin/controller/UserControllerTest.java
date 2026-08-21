package com.feisheng.bot.admin.controller;

import com.feisheng.bot.admin.mapper.SysUserMapper;
import com.feisheng.bot.admin.mapper.SysUserRoleMapper;
import com.feisheng.bot.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserControllerTest {
    @Test
    void deleteRejectsSuperAdministrator() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        when(userMapper.selectRolesByUserId(1L)).thenReturn(List.of("ROLE_ADMIN"));
        UserController controller = new UserController(
            userMapper, mock(PasswordEncoder.class), mock(SysUserRoleMapper.class));

        assertThrows(BusinessException.class, () -> controller.delete(1L));

        verify(userMapper, never()).deleteDirectPermissions(1L);
        verify(userMapper, never()).deleteById(1L);
    }
}
