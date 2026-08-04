package com.feisheng.bot.knowledge.controller;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.knowledge.entity.BotKnowledgeCategory;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeCategoryMapper;
import com.feisheng.bot.common.vo.R;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.stream.Collectors;
@RestController
@RequestMapping("/api/knowledge/category")
public class KnowledgeCategoryController {
    private final BotKnowledgeCategoryMapper mapper;
    public KnowledgeCategoryController(BotKnowledgeCategoryMapper m) { mapper=m; }
    @PostMapping("/add") public R<Void> add(@RequestBody BotKnowledgeCategory c) { mapper.insert(c); return R.ok(); }
    @PutMapping("/update") public R<Void> update(@RequestBody BotKnowledgeCategory c) { mapper.updateById(c); return R.ok(); }
    @DeleteMapping("/{id}") public R<Void> delete(@PathVariable Long id) { mapper.deleteById(id); return R.ok(); }
    @GetMapping("/tree") public R<List<BotKnowledgeCategory>> tree() {
        List<BotKnowledgeCategory> all = mapper.selectList(null);
        return R.ok(all);
    }
}