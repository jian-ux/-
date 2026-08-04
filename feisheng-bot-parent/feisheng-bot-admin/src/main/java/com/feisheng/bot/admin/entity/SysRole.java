package com.feisheng.bot.admin.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
@EqualsAndHashCode(callSuper = true)
@Data
@TableName("sys_role")
public class SysRole extends BaseEntity {
    private String roleName;
    private String roleKey;
    private Integer status;
}