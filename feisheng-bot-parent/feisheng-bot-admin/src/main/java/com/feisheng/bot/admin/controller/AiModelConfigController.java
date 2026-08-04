package com.feisheng.bot.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.admin.entity.BotAiModelConfig;
import com.feisheng.bot.admin.mapper.BotAiModelConfigMapper;
import com.feisheng.bot.common.vo.R;
import org.springframework.web.bind.annotation.*;
import org.springframework.util.StringUtils;

import java.util.List;

@RestController
@RequestMapping("/api/admin/ai/model")
public class AiModelConfigController {
    private final BotAiModelConfigMapper mapper;

    public AiModelConfigController(BotAiModelConfigMapper m) { mapper = m; }

    @RequestMapping(value = "/save", method = {RequestMethod.POST, RequestMethod.PUT})
    public R<Void> save(@RequestBody BotAiModelConfig c) {
        if (c.getId() != null) {
            if (!StringUtils.hasText(c.getApiKey())) c.setApiKey(null);
            mapper.updateById(c);
        } else {
            mapper.insert(c);
        }
        return R.ok();
    }

    @GetMapping("/list")
    public R<Page<BotAiModelConfig>> list(@RequestParam(defaultValue = "1") int p,
                                           @RequestParam(defaultValue = "20") int s) {
        return R.ok(mapper.selectPage(new Page<>(p, s),
            new LambdaQueryWrapper<BotAiModelConfig>().orderByDesc(BotAiModelConfig::getIsDefault)
                .orderByDesc(BotAiModelConfig::getCreateTime)));
    }

    /** Get all enabled models (for dropdown selection) */
    @GetMapping("/enabled")
    public R<List<BotAiModelConfig>> enabled() {
        List<BotAiModelConfig> models = mapper.selectList(
            new LambdaQueryWrapper<BotAiModelConfig>()
                .eq(BotAiModelConfig::getStatus, 1)
                .orderByDesc(BotAiModelConfig::getIsDefault));
        return R.ok(models.stream()
            .filter(model -> !StringUtils.hasText(model.getModelType())
                || "LLM".equalsIgnoreCase(model.getModelType()))
            .toList());
    }

    /** Set a model as default within its own model type. */
    @PutMapping("/{id}/set-default")
    public R<Void> setDefault(@PathVariable Long id) {
        BotAiModelConfig target = mapper.selectById(id);
        if (target == null) return R.fail(404, "模型不存在");
        String modelType = StringUtils.hasText(target.getModelType()) ? target.getModelType() : "LLM";
        LambdaUpdateWrapper<BotAiModelConfig> clearDefault =
            new LambdaUpdateWrapper<BotAiModelConfig>().set(BotAiModelConfig::getIsDefault, 0);
        if ("LLM".equalsIgnoreCase(modelType)) {
            clearDefault.and(query -> query.eq(BotAiModelConfig::getModelType, "LLM")
                .or().isNull(BotAiModelConfig::getModelType)
                .or().eq(BotAiModelConfig::getModelType, ""));
        } else {
            clearDefault.eq(BotAiModelConfig::getModelType, modelType);
        }
        mapper.update(null, clearDefault);
        BotAiModelConfig model = new BotAiModelConfig();
        model.setId(id);
        model.setIsDefault(1);
        mapper.updateById(model);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { mapper.deleteById(id); return R.ok(); }
}
