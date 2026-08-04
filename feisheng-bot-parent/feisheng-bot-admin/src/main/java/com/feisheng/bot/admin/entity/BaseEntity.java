package com.feisheng.bot.admin.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.util.Date;
@Data
public class BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Date createTime;
    private Date updateTime;
    @TableLogic
    private Integer deleted;
}