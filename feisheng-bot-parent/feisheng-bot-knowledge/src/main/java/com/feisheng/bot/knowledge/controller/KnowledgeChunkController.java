package com.feisheng.bot.knowledge.controller;

import com.feisheng.bot.common.vo.R;
import com.feisheng.bot.knowledge.service.KnowledgeEvidenceService;
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
@RequestMapping("/api/knowledge/chunk")
public class KnowledgeChunkController {
    private final KnowledgeEvidenceService evidenceService;

    public KnowledgeChunkController(KnowledgeEvidenceService evidenceService) {
        this.evidenceService = evidenceService;
    }

    @PostMapping("/evidence")
    public R<List<Map<String, Object>>> evidenceChunks(
            @RequestBody Map<String, Object> body) {
        return R.ok(evidenceService.findApprovedChunks(
            chunkIds(body.get("chunkIds")), filters(body)));
    }

    private List<Long> chunkIds(Object value) {
        if (!(value instanceof List<?> values)) return Collections.emptyList();
        List<Long> result = new ArrayList<>();
        for (Object element : values) {
            if (element instanceof Number number) result.add(number.longValue());
            else if (element instanceof String string && string.matches("\\d+")) {
                result.add(Long.valueOf(string));
            }
        }
        return result;
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
