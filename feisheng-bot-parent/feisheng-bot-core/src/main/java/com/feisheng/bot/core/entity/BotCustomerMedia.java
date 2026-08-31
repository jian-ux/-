package com.feisheng.bot.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** Customer-scoped media metadata; OCR remains untrusted customer material. */
@Data
@TableName("bot_customer_media")
public class BotCustomerMedia {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long customerId;
    private Long sourceMessageId;
    private String mediaType;
    private String objectKey;
    private String ocrText;
    private String metadata;
    private String trustLevel;
    private Date createTime;
    private Date updatedAt;
    @TableLogic
    private Integer deleted;
}
