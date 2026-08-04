package com.feisheng.bot.admin.controller;

import com.feisheng.bot.admin.service.KnowledgeEmbeddingBackfillService;
import com.feisheng.bot.admin.service.DialogEvaluationService;
import com.feisheng.bot.common.vo.R;
import com.feisheng.bot.core.service.impl.RagEvaluationService;
import com.feisheng.bot.knowledge.service.KnowledgeIndexService;
import com.feisheng.bot.knowledge.service.QdrantVectorStore;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/rag")
public class RagAdminController {
    private final KnowledgeEmbeddingBackfillService backfillService;
    private final KnowledgeIndexService indexService;
    private final RagEvaluationService evaluationService;
    private final DialogEvaluationService dialogEvaluationService;

    public RagAdminController(KnowledgeEmbeddingBackfillService backfillService,
                              KnowledgeIndexService indexService,
                              RagEvaluationService evaluationService,
                              DialogEvaluationService dialogEvaluationService) {
        this.backfillService = backfillService;
        this.indexService = indexService;
        this.evaluationService = evaluationService;
        this.dialogEvaluationService = dialogEvaluationService;
    }

    @GetMapping("/status")
    public R<KnowledgeEmbeddingBackfillService.BackfillStatus> status() {
        return R.ok(backfillService.status());
    }

    @PostMapping("/backfill")
    public R<KnowledgeEmbeddingBackfillService.BackfillReport> backfill() {
        try {
            KnowledgeEmbeddingBackfillService.BackfillReport report = backfillService.backfillMissing();
            indexService.sync();
            return R.ok(report);
        } catch (IllegalStateException e) {
            return R.fail(400, e.getMessage());
        }
    }

    @PostMapping("/re-embed-all")
    public R<KnowledgeEmbeddingBackfillService.BackfillReport> reEmbedAll() {
        try {
            KnowledgeEmbeddingBackfillService.BackfillReport report = backfillService.backfillAll();
            indexService.sync();
            return R.ok(report);
        } catch (IllegalStateException e) {
            return R.fail(400, e.getMessage());
        }
    }

    @GetMapping("/sync-status")
    public R<KnowledgeIndexService.IndexStatus> syncStatus() {
        return R.ok(indexService.status());
    }

    @PostMapping("/sync")
    public R<KnowledgeIndexService.SyncReport> sync() {
        return R.ok(indexService.sync());
    }

    @GetMapping("/qdrant/status")
    public R<QdrantVectorStore.QdrantStatus> qdrantStatus() {
        return R.ok(indexService.qdrantStatus());
    }

    @PostMapping("/qdrant/reindex")
    public R<KnowledgeIndexService.QdrantReindexReport> reindexQdrant() {
        return R.ok(indexService.reindexQdrant());
    }

    @PostMapping("/evaluate")
    public R<RagEvaluationService.EvaluationReport> evaluate(
            @RequestBody RagEvaluationService.EvaluationRequest request) {
        try {
            return R.ok(evaluationService.evaluate(request));
        } catch (IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        }
    }

    @PostMapping("/evaluate-dialog")
    public R<DialogEvaluationService.DialogEvaluationReport> evaluateDialog(
            @RequestBody DialogEvaluationService.DialogEvaluationRequest request) {
        try {
            return R.ok(dialogEvaluationService.evaluate(request));
        } catch (IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        }
    }
}
