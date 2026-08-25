package com.feisheng.bot.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.admin.entity.BotKnowledgeItem;
import com.feisheng.bot.admin.entity.BotKnowledgeItemChunk;
import com.feisheng.bot.admin.mapper.BotKnowledgeItemChunkMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeItemMapper;
import com.feisheng.bot.admin.service.EmbeddingService;
import com.feisheng.bot.admin.service.VectorSearchService;
import com.feisheng.bot.admin.util.VectorUtil;
import com.feisheng.bot.common.vo.R;
import com.feisheng.bot.common.util.EmbeddingMetadataUtil;
import com.feisheng.bot.common.util.KnowledgeTextUtil;
import com.feisheng.bot.knowledge.service.KnowledgeIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;

@RestController
@RequestMapping("/api/admin/knowledge/item")
public class KnowledgeItemAdminController {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeItemAdminController.class);
    private static final int MAX_QUESTION_LENGTH = 500;
    private static final int MAX_ANSWER_LENGTH = 20_000;
    private static final int MAX_KEYWORDS_LENGTH = 500;
    private final BotKnowledgeItemMapper mapper;
    private final BotKnowledgeItemChunkMapper itemChunkMapper;
    private final EmbeddingService embeddingService;
    private final VectorSearchService vectorSearch;
    private final KnowledgeIndexService indexService;

    // P1-3: Thread pool for async embedding generation
    private ExecutorService embeddingExecutor;

    @PostConstruct
    public void init() {
        embeddingExecutor = new ThreadPoolExecutor(
            2, 4, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(50),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    @PreDestroy
    public void shutdown() {
        if (embeddingExecutor != null) {
            embeddingExecutor.shutdown();
            try {
                if (!embeddingExecutor.awaitTermination(15, TimeUnit.SECONDS)) {
                    embeddingExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                embeddingExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Autowired
    public KnowledgeItemAdminController(BotKnowledgeItemMapper m, EmbeddingService es,
                                        VectorSearchService vs, KnowledgeIndexService indexService,
                                        BotKnowledgeItemChunkMapper itemChunkMapper) {
        mapper = m;
        embeddingService = es;
        vectorSearch = vs;
        this.indexService = indexService;
        this.itemChunkMapper = itemChunkMapper;
    }

    public KnowledgeItemAdminController(BotKnowledgeItemMapper m, EmbeddingService es,
                                        VectorSearchService vs, KnowledgeIndexService indexService) {
        this(m, es, vs, indexService, null);
    }

    @GetMapping("/search")
    public R<Page<BotKnowledgeItem>> search(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<BotKnowledgeItem> q = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            q.and(w -> w.like(BotKnowledgeItem::getQuestion, keyword)
                    .or().like(BotKnowledgeItem::getKeywords, keyword)
                    .or().like(BotKnowledgeItem::getAlternateQuestions, keyword));
        }
        q.orderByDesc(BotKnowledgeItem::getHitCount)
            .orderByDesc(BotKnowledgeItem::getId);
        Page<BotKnowledgeItem> result = mapper.selectPage(new Page<>(page, size), q);
        result.getRecords().forEach(item ->
            item.setEmbeddingReady(StringUtils.hasText(item.getEmbedding())));
        return R.ok(result);
    }

    @PostMapping("/add")
    public R<Void> add(@RequestBody BotKnowledgeItem item) {
        String validationError = validate(item, false);
        if (validationError != null) return R.fail(400, validationError);

        String question = item.getQuestion().trim();
        if (questionExists(question, null)) return R.fail(409, "相同问题已存在");

        BotKnowledgeItem created = new BotKnowledgeItem();
        created.setCategoryId(item.getCategoryId() == null ? 0L : item.getCategoryId());
        created.setQuestion(question);
        created.setAnswer(item.getAnswer().trim());
        created.setKeywords(trimToNull(item.getKeywords()));
        created.setAlternateQuestions(
            KnowledgeTextUtil.questionAliasesJson(item.getAlternateQuestions(), question));
        created.setHitCount(0);
        created.setStatus(1);
        created.setDirectAnswerEnabled(
            Integer.valueOf(1).equals(item.getDirectAnswerEnabled()) ? 1 : 0);
        mapper.insert(created);
        generateEmbeddingAsync(created.getId());
        return R.ok();
    }

    @PutMapping("/update")
    public R<Void> update(@RequestBody BotKnowledgeItem item) {
        String validationError = validate(item, true);
        if (validationError != null) return R.fail(400, validationError);
        if (mapper.selectById(item.getId()) == null) return R.fail(404, "常见问题不存在");

        String question = item.getQuestion().trim();
        if (questionExists(question, item.getId())) return R.fail(409, "相同问题已存在");

        LambdaUpdateWrapper<BotKnowledgeItem> update = new LambdaUpdateWrapper<>();
        update.eq(BotKnowledgeItem::getId, item.getId())
            .set(BotKnowledgeItem::getQuestion, question)
            .set(BotKnowledgeItem::getAnswer, item.getAnswer().trim())
            .set(BotKnowledgeItem::getKeywords, trimToNull(item.getKeywords()))
            .set(BotKnowledgeItem::getAlternateQuestions,
                KnowledgeTextUtil.questionAliasesJson(item.getAlternateQuestions(), question))
            .set(BotKnowledgeItem::getDirectAnswerEnabled,
                Integer.valueOf(1).equals(item.getDirectAnswerEnabled()) ? 1 : 0)
            .set(BotKnowledgeItem::getEmbedding, null)
            .set(BotKnowledgeItem::getEmbeddingModel, null)
            .set(BotKnowledgeItem::getEmbeddingVersion, null)
            .set(BotKnowledgeItem::getEmbeddingDimensions, null)
            .set(BotKnowledgeItem::getEmbeddingContentHash, null);
        if (mapper.update(null, update) == 0) return R.fail(404, "常见问题不存在");

        deleteFaqChunks(item.getId());
        vectorSearch.removeItem(item.getId());
        indexService.sync();
        generateEmbeddingAsync(item.getId());
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        deleteFaqChunks(id);
        mapper.deleteById(id);
        vectorSearch.removeItem(id);
        indexService.sync();
        return R.ok();
    }

    @PostMapping("/{id}/re-embed")
    public R<Map<String, Object>> reEmbed(@PathVariable Long id) {
        BotKnowledgeItem item = mapper.selectById(id);
        if (item == null) return R.fail(404, "常见问题不存在");
        int dimensions = embedFaqItem(item, true);
        if (dimensions == 0) return R.fail(500, "向量生成失败");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("dimensions", dimensions);
        result.put("success", true);
        return R.ok(result);
    }

    @PostMapping("/re-embed-all")
    public R<Map<String, Object>> reEmbedAll() {
        List<BotKnowledgeItem> items = mapper.selectList(
            new LambdaQueryWrapper<BotKnowledgeItem>().eq(BotKnowledgeItem::getStatus, 1));
        int success = 0, failed = 0;
        List<BotKnowledgeItem> validItems = items.stream()
            .filter(item -> StringUtils.hasText(item.getQuestion()))
            .toList();
        if (validItems.isEmpty()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("total", 0); r.put("success", 0); r.put("failed", 0);
            return R.ok(r);
        }

        for (BotKnowledgeItem item : validItems) {
            if (embedFaqItem(item, false) > 0) success++;
            else failed++;
        }
        log.info("Re-embedded all: {} success, {} failed", success, failed);
        indexService.sync();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", validItems.size());
        result.put("success", success);
        result.put("failed", failed);
        return R.ok(result);
    }

    private void generateEmbeddingAsync(Long itemId) {
        if (itemId == null || embeddingExecutor == null) return;
        embeddingExecutor.submit(() -> {
            try {
                BotKnowledgeItem snapshot = mapper.selectById(itemId);
                if (snapshot == null || !StringUtils.hasText(snapshot.getQuestion())) return;
                if (embedFaqItem(snapshot, true) > 0) {
                    log.info("Embedding generated for item {}", itemId);
                } else {
                    log.warn("Embedding generation returned an empty vector for item {}", itemId);
                }
            } catch (Exception e) {
                log.warn("Async embedding failed for item {}: {}", itemId, e.getMessage());
            }
        });
    }

    private String validate(BotKnowledgeItem item, boolean update) {
        if (item == null) return "FAQ内容不能为空";
        if (update && item.getId() == null) return "FAQ ID不能为空";
        if (!StringUtils.hasText(item.getQuestion())) return "问题不能为空";
        if (!StringUtils.hasText(item.getAnswer())) return "答案不能为空";
        if (item.getQuestion().trim().length() > MAX_QUESTION_LENGTH) return "问题不能超过500个字符";
        if (item.getAnswer().trim().length() > MAX_ANSWER_LENGTH) return "答案不能超过20000个字符";
        if (item.getKeywords() != null && item.getKeywords().trim().length() > MAX_KEYWORDS_LENGTH) {
            return "关键词不能超过500个字符";
        }
        return null;
    }

    private boolean questionExists(String question, Long excludedId) {
        LambdaQueryWrapper<BotKnowledgeItem> query = new LambdaQueryWrapper<BotKnowledgeItem>()
            .eq(BotKnowledgeItem::getQuestion, question);
        if (excludedId != null) query.ne(BotKnowledgeItem::getId, excludedId);
        return mapper.selectCount(query) > 0;
    }

    private int embedFaqItem(BotKnowledgeItem snapshot, boolean syncIndex) {
        List<KnowledgeTextUtil.FaqEmbeddingPart> parts = KnowledgeTextUtil.faqEmbeddingParts(
            snapshot.getQuestion(), snapshot.getKeywords(), snapshot.getAnswer(),
            snapshot.getAlternateQuestions());
        List<float[]> embeddings = embeddingService.embedBatch(
            parts.stream().map(KnowledgeTextUtil.FaqEmbeddingPart::embeddingText).toList());
        if (embeddings == null || embeddings.size() < parts.size()
                || embeddings.stream().limit(parts.size())
                    .anyMatch(value -> value == null || value.length == 0)) {
            return 0;
        }

        BotKnowledgeItem latest = mapper.selectById(snapshot.getId());
        if (latest == null || !faqSourceSignature(snapshot).equals(faqSourceSignature(latest))) {
            log.info("Skipping stale embedding for item {}", snapshot.getId());
            return 0;
        }
        float[] parentVector = embeddings.get(0);
        latest.setEmbedding(VectorUtil.toJson(parentVector));
        applyEmbeddingMetadata(latest, parts.get(0).embeddingText(), parentVector.length);
        mapper.updateById(latest);
        replaceFaqChunks(latest.getId(), parts, embeddings);
        vectorSearch.reloadItem(latest.getId());
        if (syncIndex) indexService.sync();
        return parentVector.length;
    }

    private void replaceFaqChunks(Long itemId,
                                  List<KnowledgeTextUtil.FaqEmbeddingPart> parts,
                                  List<float[]> embeddings) {
        if (itemChunkMapper == null) return;
        deleteFaqChunks(itemId);
        for (int i = 1; i < parts.size(); i++) {
            KnowledgeTextUtil.FaqEmbeddingPart part = parts.get(i);
            float[] embedding = embeddings.get(i);
            BotKnowledgeItemChunk chunk = new BotKnowledgeItemChunk();
            chunk.setItemId(itemId);
            chunk.setChunkIndex(part.index());
            chunk.setContent(part.answerPart());
            chunk.setEmbedding(VectorUtil.toJson(embedding));
            applyEmbeddingMetadata(chunk, part.embeddingText(), embedding.length);
            itemChunkMapper.insert(chunk);
        }
    }

    private void deleteFaqChunks(Long itemId) {
        if (itemChunkMapper == null || itemId == null) return;
        itemChunkMapper.delete(new LambdaQueryWrapper<BotKnowledgeItemChunk>()
            .eq(BotKnowledgeItemChunk::getItemId, itemId));
    }

    private String faqSourceSignature(BotKnowledgeItem item) {
        return String.join("\u0000",
            Objects.toString(item.getQuestion(), ""),
            Objects.toString(item.getKeywords(), ""),
            Objects.toString(item.getAlternateQuestions(), ""),
            Objects.toString(item.getAnswer(), ""));
    }

    private void applyEmbeddingMetadata(BotKnowledgeItem item, String sourceText, int dimensions) {
        EmbeddingService.EmbeddingDescriptor descriptor = embeddingService.descriptor();
        if (descriptor != null) {
            item.setEmbeddingModel(descriptor.model());
            item.setEmbeddingVersion(descriptor.version());
        }
        item.setEmbeddingDimensions(dimensions);
        item.setEmbeddingContentHash(EmbeddingMetadataUtil.contentHash(sourceText));
    }

    private void applyEmbeddingMetadata(BotKnowledgeItemChunk chunk, String sourceText,
                                        int dimensions) {
        EmbeddingService.EmbeddingDescriptor descriptor = embeddingService.descriptor();
        if (descriptor != null) {
            chunk.setEmbeddingModel(descriptor.model());
            chunk.setEmbeddingVersion(descriptor.version());
        }
        chunk.setEmbeddingDimensions(dimensions);
        chunk.setEmbeddingContentHash(EmbeddingMetadataUtil.contentHash(sourceText));
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    @PostMapping("/batch-delete")
    public R<Void> batchDelete(@RequestBody List<Long> ids) {
        if (ids == null || ids.isEmpty()) return R.ok();
        for (Long id : ids) {
            mapper.deleteById(id);
            vectorSearch.removeItem(id);
        }
        indexService.sync();
        log.info("Batch deleted " + "{}" + " knowledge items", ids.size());
        return R.ok();
    }

}
