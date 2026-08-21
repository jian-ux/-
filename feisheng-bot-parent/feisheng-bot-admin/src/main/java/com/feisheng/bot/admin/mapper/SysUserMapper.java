package com.feisheng.bot.admin.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.feisheng.bot.admin.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
    @Select("""
        SELECT DISTINCT CONCAT('ROLE_', UPPER(r.role_key))
        FROM sys_user_role ur
        JOIN sys_role r ON r.id = ur.role_id
        WHERE ur.user_id = #{userId}
          AND r.status = 1
          AND r.deleted = 0
        """)
    List<String> selectRolesByUserId(Long userId);

    @Select("""
        SELECT DISTINCT granted.permission
        FROM (
            SELECT p.permission
            FROM sys_user_role ur
            JOIN sys_role r ON r.id = ur.role_id
            JOIN sys_role_permission rp ON rp.role_id = ur.role_id
            JOIN sys_permission p ON p.id = rp.permission_id
            WHERE ur.user_id = #{userId}
              AND r.status = 1
              AND r.deleted = 0
              AND p.status = 1
              AND p.deleted = 0
            UNION
            SELECT p.permission
            FROM sys_user_permission up
            JOIN sys_permission p ON p.id = up.permission_id
            WHERE up.user_id = #{userId}
              AND p.status = 1
              AND p.deleted = 0
        ) granted
        WHERE granted.permission IS NOT NULL
          AND granted.permission <> ''
        """)
    List<String> selectPermissionsByUserId(Long userId);

    @Select("""
        SELECT up.permission_id
        FROM sys_user_permission up
        JOIN sys_permission p ON p.id = up.permission_id
        WHERE up.user_id = #{userId}
          AND p.status = 1
          AND p.deleted = 0
        """)
    List<Long> selectDirectPermissionIds(Long userId);

    @Delete("DELETE FROM sys_user_permission WHERE user_id = #{userId}")
    int deleteDirectPermissions(Long userId);

    @Insert("INSERT INTO sys_user_permission (user_id, permission_id) VALUES (#{userId}, #{permissionId})")
    int insertDirectPermission(@Param("userId") Long userId, @Param("permissionId") Long permissionId);
}
