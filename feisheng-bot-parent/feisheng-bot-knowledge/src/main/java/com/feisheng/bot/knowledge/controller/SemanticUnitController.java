package com.feisheng.bot.knowledge.controller;

import com.feisheng.bot.common.vo.R;
import com.feisheng.bot.knowledge.service.StructuredKnowledgeUnitIndexService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge/semantic-unit")
public class SemanticUnitController {
    private final StructuredKnowledgeUnitIndexService indexService;

    public SemanticUnitController(StructuredKnowledgeUnitIndexService indexService) {
        this.indexService = indexService;
    }

    @PostMapping("/search")
    public R<List<Map<String, Object>>> semanticUnitSearch(
            @RequestBody Map<String, Object> body) {
        List<Double> embedding = embedding(body.get("embedding"));
        if (embedding.isEmpty()) return R.ok(Collections.emptyList());
        int topK = body.get("topK") instanceof Number number
            ? Math.max(1, Math.min(50, number.intValue())) : 10;
        double minScore = body.get("minScore") instanceof Number number
            ? number.doubleValue() : -1;
        return R.ok(indexService.search(embedding, topK, minScore, filters(body)));
    }

    private List<Double> embedding(Object value) {
        if (!(value instanceof List<?> values)) return Collections.emptyList();
        List<Double> embedding = new ArrayList<>(values.size());
        for (Object element : values) {
            if (!(element instanceof Number number)) return Collections.emptyList();
            embedding.add(number.doubleValue());
        }
        return List.copyOf(embedding);
    }

    private Map<String, Object> filters(Map<String, Object> body) {
        if (!(body.get("filters") instanceof Map<?, ?> raw) || raw.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key instanceof String stringKey) result.put(stringKey, value);
        });
        return result;
    }
}
