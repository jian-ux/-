package com.feisheng.bot.core.controller;

import com.feisheng.bot.core.client.KnowledgeClient;
import com.feisheng.bot.core.service.EmbeddingService;
import com.feisheng.bot.common.vo.R;
import com.feisheng.bot.common.util.EmbeddingMetadataUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * RAG 管理控制器：回填向量、查看状态
 */
@RestController
@RequestMapping("/api/core/rag")
public class RagController {
    private static final Logger log = LoggerFactory.getLogger(RagController.class);
    private final EmbeddingService embeddingService;
    private final KnowledgeClient knowledgeClient;

    public RagController(EmbeddingService es, KnowledgeClient kc) {
        this.embeddingService = es;
        this.knowledgeClient = kc;
    }

    /**
     * 回填所有未生成 embedding 的知识条目的向量
     */
    @PostMapping("/backfill-embeddings")
    public R<Map<String,Object>> backfillEmbeddings() {
        if (!embeddingService.isAvailable()) {
            return R.fail(400, "向量服务未配置");
        }

        List<Map<String,Object>> pending = knowledgeClient.getPendingEmbeddingItems();
        if (pending == null || pending.isEmpty()) {
            Map<String,Object> r = new LinkedHashMap<>();
            r.put("total", 0);
            r.put("success", 0);
            r.put("message", "所有知识条目已有 embedding，无需回填");
            return R.ok(r);
        }

        int total = pending.size();
        int success = 0;
        List<Map<String,Object>> errors = new ArrayList<>();

        for (Map<String,Object> item : pending) {
            Object idObj = item.get("id");
            if (idObj == null) continue;
            Long id = ((Number) idObj).longValue();
            String question = (String) item.getOrDefault("question", "");
            String answer = (String) item.getOrDefault("answer", "");
            String keywords = (String) item.getOrDefault("keywords", "");

            // 组合文本：question + keywords + answer（截断防止超token限制）
            StringBuilder sb = new StringBuilder();
            sb.append(question);
            if (keywords != null && !keywords.isEmpty()) sb.append(" ").append(keywords);
            if (answer != null && !answer.isEmpty()) {
                String trimmed = answer.length() > 500 ? answer.substring(0, 500) : answer;
                sb.append(" ").append(trimmed);
            }
            String text = sb.toString();
            if (text.length() > 2000) text = text.substring(0, 2000);

            try {
                List<Double> embedding = embeddingService.embed(text);
                if (!embedding.isEmpty()) {
                    EmbeddingService.EmbeddingDescriptor descriptor = embeddingService.descriptor();
                    boolean ok = knowledgeClient.updateEmbedding(id, embedding,
                        descriptor.model(), descriptor.version(),
                        EmbeddingMetadataUtil.contentHash(text));
                    if (ok) success++;
                    else {
                        Map<String,Object> err = new HashMap<>();
                        err.put("id", id); err.put("reason", "更新 embedding 失败");
                        errors.add(err);
                    }
                } else {
                    Map<String,Object> err = new HashMap<>();
                    err.put("id", id); err.put("reason", "Embedding API 返回空");
                    errors.add(err);
                }
            } catch (Exception e) {
                log.warn("Backfill embedding failed for item {}: {}", id, e.getMessage());
                Map<String,Object> err = new HashMap<>();
                err.put("id", id); err.put("reason", e.getMessage());
                errors.add(err);
            }

            // 避免 API 限流，每个请求间小停顿
            if (pending.size() > 1) {
                try { Thread.sleep(200); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
            }
        }

        Map<String,Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("success", success);
        result.put("failed", total - success);
        result.put("errors", errors);
        return R.ok(result);
    }

    /**
     * 查看 pending 知识条目数量
     */
    @GetMapping("/status")
    public R<Map<String,Object>> status() {
        List<Map<String,Object>> pending = knowledgeClient.getPendingEmbeddingItems();
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("embeddingAvailable", embeddingService.isAvailable());
        result.put("pendingItems", pending != null ? pending.size() : 0);
        return R.ok(result);
    }
}
