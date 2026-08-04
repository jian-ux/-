package com.feisheng.bot.admin.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
@EqualsAndHashCode(callSuper = true)
@Data
@TableName("bot_ai_model_config")
public class BotAiModelConfig extends BaseEntity {
    private String modelName;
    private String provider;
    private String apiUrl;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String apiKey;
    private String modelType;
    private String parameters;
    private Integer status;
    private Integer isDefault;
}
