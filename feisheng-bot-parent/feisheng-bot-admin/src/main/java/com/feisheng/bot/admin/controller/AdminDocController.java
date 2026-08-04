package com.feisheng.bot.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.admin.entity.BotKnowledgeDocument;
import com.feisheng.bot.admin.entity.BotKnowledgeChunk;
import com.feisheng.bot.admin.mapper.BotKnowledgeDocumentMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.admin.service.DocumentParseService;
import com.feisheng.bot.admin.service.ChunkingService;
import com.feisheng.bot.admin.service.EmbeddingService;
import com.feisheng.bot.admin.service.ImageOcrService;
import com.feisheng.bot.admin.service.KnowledgeChunkPersistenceService;
import com.feisheng.bot.admin.service.StructuredQaReviewService;
import com.feisheng.bot.admin.service.TesseractOcrEngine;
import com.feisheng.bot.admin.service.VectorSearchService;
import com.feisheng.bot.admin.util.VectorUtil;
import com.feisheng.bot.common.vo.R;
import com.feisheng.bot.common.util.EmbeddingMetadataUtil;
import com.feisheng.bot.common.util.KnowledgeTextUtil;
import com.feisheng.bot.knowledge.service.MinioStorageService;
import com.feisheng.bot.knowledge.service.KnowledgeIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletResponse;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

@RestController
@RequestMapping("/api/admin/doc")
public class AdminDocController {
    private static final Logger log = LoggerFactory.getLogger(AdminDocController.class);
    private final BotKnowledgeDocumentMapper mapper;
    private final BotKnowledgeChunkMapper chunkMapper;
    private final DocumentParseService parseService;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final VectorSearchService vectorSearch;
    private final MinioStorageService storageService;
    private final KnowledgeIndexService indexService;
    private final ImageOcrService imageOcrService;
    private final KnowledgeChunkPersistenceService chunkPersistenceService;
    private final StructuredQaReviewService structuredQaReviewService;

    // P1-3: Thread pool for async document ingestion
    private ExecutorService ingestExecutor;

    // Document status constants
    private static final int STATUS_PROCESSING = 1;
    private static final int STATUS_COMPLETED = 2;
    private static final int STATUS_FAILED = 3;

    @PostConstruct
    public void init() {
        ingestExecutor = new ThreadPoolExecutor(
            2, 4,                          // core=2, max=4 threads
            60L, TimeUnit.SECONDS,          // keep-alive
            new LinkedBlockingQueue<>(20),  // queue up to 20 tasks
            new ThreadPoolExecutor.CallerRunsPolicy()  // backpressure
        );
    }

    @PreDestroy
    public void shutdown() {
        if (ingestExecutor != null) {
            ingestExecutor.shutdown();
            try {
                if (!ingestExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    ingestExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                ingestExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public AdminDocController(BotKnowledgeDocumentMapper m, BotKnowledgeChunkMapper cm,
                               DocumentParseService ps, ChunkingService cs,
                               EmbeddingService es, VectorSearchService vs,
                               MinioStorageService storageService,
                               KnowledgeIndexService indexService,
                               ImageOcrService imageOcrService,
                               KnowledgeChunkPersistenceService chunkPersistenceService,
                               StructuredQaReviewService structuredQaReviewService) {
        mapper = m; chunkMapper = cm; parseService = ps; chunkingService = cs;
        embeddingService = es; vectorSearch = vs;
        this.storageService = storageService;
        this.indexService = indexService;
        this.imageOcrService = imageOcrService;
        this.chunkPersistenceService = chunkPersistenceService;
        this.structuredQaReviewService = structuredQaReviewService;
    }

    @PostMapping("/upload")
    public R<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) return R.fail(400, "上传文件不能为空");
        boolean image = imageOcrService.supports(file.getOriginalFilename());
        if (image) {
            try {
                imageOcrService.validateUploadSize(file.getSize());
            } catch (TesseractOcrEngine.OcrException e) {
                return R.fail(400, e.getMessage());
            }
        }
        Path filePath = Files.createTempFile("feisheng-ingest-", "-" + safeFileName(file.getOriginalFilename()));
        MinioStorageService.UploadResult stored = null;
        try {
            file.transferTo(filePath.toFile());
            stored = storageService.upload(filePath, file.getOriginalFilename(), file.getContentType());

            BotKnowledgeDocument d = new BotKnowledgeDocument();
            d.setTitle(file.getOriginalFilename());
            d.setFileName(file.getOriginalFilename());
            d.setBucketName(stored.bucketName());
            d.setObjectKey(stored.objectKey());
            d.setFileType(stored.fileType());
            d.setMediaType(image ? "IMAGE" : "DOCUMENT");
            d.setSourceScope("KNOWLEDGE");
            d.setOcrStatus(image ? "PROCESSING" : null);
            d.setFileSize(file.getSize());
            d.setStatus(STATUS_PROCESSING);
            mapper.insert(d);

            final Long docId = d.getId();
            final String fileName = file.getOriginalFilename();
            ingestExecutor.submit(() -> ingestDocument(docId, filePath, fileName));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", docId);
            result.put("fileName", fileName);
            result.put("mediaType", d.getMediaType());
            result.put("ocrStatus", d.getOcrStatus());
            result.put("status", "processing");
            return R.ok(result);
        } catch (Exception e) {
            Files.deleteIfExists(filePath);
            if (stored != null) {
                try { storageService.delete(stored.objectKey()); }
                catch (Exception cleanupError) {
                    log.warn("Failed to roll back MinIO upload {}: {}", stored.objectKey(), cleanupError.getMessage());
                }
            }
            throw e;
        }
    }

    /** Full ingestion pipeline for a document with status management */
    private void ingestDocument(Long docId, Path filePath, String fileName) {
        try {
            log.info("Ingesting document {}: {}", docId, fileName);

            BotKnowledgeDocument document = mapper.selectById(docId);
            boolean image = document != null && "IMAGE".equals(document.getMediaType());

            // 1. Parse document or run image OCR
            String text;
            if (image) {
                try {
                    ImageOcrService.OcrResult ocr = imageOcrService.extract(filePath, fileName);
                    text = imageKnowledgeText(fileName, ocr.text());
                    updateOcrSuccess(docId, ocr);
                } catch (TesseractOcrEngine.OcrException e) {
                    updateOcrFailure(docId, e.getMessage());
                    throw e;
                }
            } else {
                text = parseService.parse(filePath, fileName);
            }
            if (text == null || text.trim().isEmpty()) {
                log.warn("Empty content after parsing document {}", docId);
                updateDocStatus(docId, STATUS_FAILED);
                return;
            }

            // 2. Chunk
            List<ChunkingService.Chunk> chunks = image
                ? chunkingService.chunkImage(text)
                : chunkingService.chunk(text);
            if (chunks.isEmpty()) {
                log.warn("No chunks produced for document {}", docId);
                updateDocStatus(docId, STATUS_FAILED);
                return;
            }

            // 3. Generate embeddings
            List<String> texts = chunks.stream().map(ChunkingService.Chunk::embeddingText).toList();
            List<float[]> embeddings = embeddingService.embedBatch(texts);

            // 4. Build the complete replacement before atomically swapping old chunks.
            int embeddedCount = 0;
            List<BotKnowledgeChunk> persistedChunks = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                ChunkingService.Chunk c = chunks.get(i);
                BotKnowledgeChunk chunk = new BotKnowledgeChunk();
                chunk.setDocumentId(docId);
                chunk.setChunkIndex(c.position);
                chunk.setContent(c.content);
                chunk.setSectionPath(c.sectionPath);
                chunk.setCharCount(c.charCount);
                chunk.setChunkStrategyVersion(c.strategyVersion);
                chunk.setContentType(c.contentType);
                chunk.setQaQuestion(c.qaQuestion);
                chunk.setQaAnswer(c.qaAnswer);
                chunk.setQaKey(c.qaKey);
                chunk.setQaGroupKey(c.qaGroupKey);
                chunk.setQaVersion(c.qaVersion);
                chunk.setDirectAnswerEnabled(0);
                if (i < embeddings.size() && embeddings.get(i).length > 0) {
                    chunk.setEmbedding(VectorUtil.toJson(embeddings.get(i)));
                    applyEmbeddingMetadata(chunk, c.embeddingText(), embeddings.get(i).length);
                    embeddedCount++;
                }
                persistedChunks.add(chunk);
            }
            chunkPersistenceService.replaceDocumentChunks(docId, persistedChunks);

            // 5. A document is searchable only when every chunk has an embedding.
            int nextStatus = embeddedCount == chunks.size()
                ? STATUS_COMPLETED : STATUS_FAILED;
            updateDocStatus(docId, nextStatus);
            indexService.sync();
            if (nextStatus == STATUS_COMPLETED) {
                log.info("Document {} ingested: {} chunks, {} with embeddings",
                    docId, chunks.size(), embeddedCount);
            } else {
                log.warn("Document {} requires embedding retry: {} of {} chunks embedded",
                    docId, embeddedCount, chunks.size());
            }

        } catch (Exception e) {
            log.error("Ingestion failed for document {}: {}", docId, e.getMessage(), e);
            updateDocStatus(docId, STATUS_FAILED);
        } finally {
            try { Files.deleteIfExists(filePath); }
            catch (IOException e) { log.warn("Failed to delete ingestion temp file {}", filePath); }
        }
    }

    private void updateDocStatus(Long docId, int status) {
        try {
            BotKnowledgeDocument doc = mapper.selectById(docId);
            if (doc != null) {
                doc.setStatus(status);
                mapper.updateById(doc);
            }
        } catch (Exception e) {
            log.warn("Failed to update document {} status to {}: {}", docId, status, e.getMessage());
        }
    }

    private void updateOcrSuccess(Long docId, ImageOcrService.OcrResult result) {
        BotKnowledgeDocument doc = mapper.selectById(docId);
        if (doc == null) return;
        doc.setOcrStatus("COMPLETED");
        doc.setOcrText(result.text());
        doc.setOcrLanguage(result.language());
        doc.setOcrError(null);
        mapper.updateById(doc);
    }

    private void updateOcrFailure(Long docId, String error) {
        try {
            BotKnowledgeDocument doc = mapper.selectById(docId);
            if (doc == null) return;
            doc.setOcrStatus("FAILED");
            doc.setOcrError(truncate(error, 1000));
            mapper.updateById(doc);
        } catch (Exception e) {
            log.warn("Could not persist OCR failure for document {}: {}", docId, e.getMessage());
        }
    }

    @GetMapping("/list")
    public R<Page<BotKnowledgeDocument>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<BotKnowledgeDocument> result = mapper.selectPage(new Page<>(page, size),
            new LambdaQueryWrapper<BotKnowledgeDocument>()
                .eq(BotKnowledgeDocument::getSourceScope, "KNOWLEDGE")
                .orderByDesc(BotKnowledgeDocument::getCreateTime));
        result.getRecords().forEach(this::populateChunkStatistics);
        return R.ok(result);
    }

    @GetMapping("/ocr/status")
    public R<TesseractOcrEngine.EngineStatus> ocrStatus() {
        return R.ok(imageOcrService.status());
    }

    @PostMapping("/{id}/ocr/retry")
    public R<Map<String, Object>> retryOcr(@PathVariable Long id) throws Exception {
        BotKnowledgeDocument doc = mapper.selectById(id);
        if (doc == null) return R.fail(404, "图片不存在");
        if (!"IMAGE".equals(doc.getMediaType())) return R.fail(400, "该文件不是图片");
        if (doc.getObjectKey() == null || doc.getObjectKey().isBlank()) {
            return R.fail(400, "图片对象不存在");
        }

        Path filePath = Files.createTempFile("feisheng-ocr-retry-", "-" + safeFileName(doc.getFileName()));
        try (InputStream input = storageService.download(doc.getObjectKey())) {
            Files.copy(input, filePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        doc.setStatus(STATUS_PROCESSING);
        doc.setOcrStatus("PROCESSING");
        doc.setOcrError(null);
        mapper.updateById(doc);
        ingestExecutor.submit(() -> ingestDocument(id, filePath, doc.getFileName()));
        return R.ok(Map.of("id", id, "status", "processing"));
    }

    @GetMapping("/{id}/preview")
    public void preview(@PathVariable Long id, HttpServletResponse response) throws IOException {
        BotKnowledgeDocument doc = mapper.selectById(id);
        if (doc == null || doc.getObjectKey() == null || doc.getObjectKey().isBlank()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        response.setContentType(contentType(doc.getFileType()));
        response.setHeader("Cache-Control", "private, max-age=300");
        try (InputStream input = storageService.download(doc.getObjectKey());
             OutputStream output = response.getOutputStream()) {
            input.transferTo(output);
        } catch (Exception e) {
            log.warn("Could not preview document {}: {}", id, e.getMessage());
            if (!response.isCommitted()) response.sendError(HttpServletResponse.SC_BAD_GATEWAY);
        }
    }

    @GetMapping("/{id}/chunks")
    public R<List<BotKnowledgeChunk>> chunks(@PathVariable Long id) {
        return R.ok(chunkMapper.selectList(
            new LambdaQueryWrapper<BotKnowledgeChunk>()
                .eq(BotKnowledgeChunk::getDocumentId, id)
                .orderByAsc(BotKnowledgeChunk::getChunkIndex)));
    }

    @PostMapping("/{id}/embedding/retry")
    public R<Map<String, Object>> retryEmbeddings(@PathVariable Long id) {
        BotKnowledgeDocument document = mapper.selectById(id);
        if (document == null) return R.fail(404, "文档不存在");

        List<BotKnowledgeChunk> chunks = chunkMapper.selectList(
            new LambdaQueryWrapper<BotKnowledgeChunk>()
                .eq(BotKnowledgeChunk::getDocumentId, id)
                .orderByAsc(BotKnowledgeChunk::getChunkIndex));
        List<BotKnowledgeChunk> missing = chunks.stream()
            .filter(chunk -> !hasEmbedding(chunk))
            .filter(chunk -> chunk.getContent() != null && !chunk.getContent().isBlank())
            .toList();

        int generated = 0;
        if (!missing.isEmpty()) {
            List<float[]> embeddings = embeddingService.embedBatch(
                missing.stream().map(AdminDocController::chunkEmbeddingText).toList());
            for (int i = 0; i < missing.size(); i++) {
                float[] embedding = i < embeddings.size()
                    ? embeddings.get(i) : new float[0];
                if (embedding == null || embedding.length == 0) continue;
                BotKnowledgeChunk chunk = missing.get(i);
                chunk.setEmbedding(VectorUtil.toJson(embedding));
                applyEmbeddingMetadata(chunk, chunkEmbeddingText(chunk), embedding.length);
                chunkMapper.updateById(chunk);
                if ("APPROVED".equals(chunk.getStatus())) {
                    vectorSearch.reloadChunk(chunk.getId());
                }
                generated++;
            }
        }

        List<BotKnowledgeChunk> refreshed = chunkMapper.selectList(
            new LambdaQueryWrapper<BotKnowledgeChunk>()
                .eq(BotKnowledgeChunk::getDocumentId, id));
        int remaining = (int) refreshed.stream().filter(chunk -> !hasEmbedding(chunk)).count();
        int nextStatus = !refreshed.isEmpty() && remaining == 0
            ? STATUS_COMPLETED : STATUS_FAILED;
        updateDocStatus(id, nextStatus);
        indexService.sync();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documentId", id);
        result.put("totalChunks", refreshed.size());
        result.put("missingBefore", missing.size());
        result.put("generated", generated);
        result.put("remaining", remaining);
        result.put("status", nextStatus == STATUS_COMPLETED ? "completed" : "failed");
        return R.ok(result);
    }

    @PostMapping("/chunks/{chunkId}/approve")
    public R<Void> approveChunk(@PathVariable Long chunkId) {
        BotKnowledgeChunk c = chunkMapper.selectById(chunkId);
        if (c != null) {
            c.setStatus("APPROVED");
            chunkMapper.updateById(c);
            vectorSearch.reloadChunk(chunkId);
            indexService.sync();
        }
        return R.ok();
    }

    @PostMapping("/chunks/{chunkId}/reject")
    public R<Void> rejectChunk(@PathVariable Long chunkId) {
        BotKnowledgeChunk c = chunkMapper.selectById(chunkId);
        if (c != null) {
            structuredQaReviewService.disableGroup(chunkId);
            c.setStatus("REJECTED");
            chunkMapper.updateById(c);
            vectorSearch.removeChunk(chunkId);
            indexService.sync();
        }
        return R.ok();
    }

    @PutMapping("/chunks/{chunkId}/direct-answer")
    public R<StructuredQaReviewService.UpdateResult> updateDirectAnswer(
            @PathVariable Long chunkId,
            @RequestBody DirectAnswerRequest request) {
        try {
            StructuredQaReviewService.UpdateResult result =
                structuredQaReviewService.updateDirectAnswer(
                    chunkId, request.enabled(), request.version());
            indexService.sync();
            return R.ok(result);
        } catch (StructuredQaReviewService.ReviewException e) {
            return R.fail(e.status(), e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        BotKnowledgeDocument doc = mapper.selectById(id);
        if (doc == null) return R.fail(404, "文档不存在");

        if (doc.getObjectKey() != null && !doc.getObjectKey().isBlank()) {
            try {
                storageService.delete(doc.getObjectKey());
            } catch (Exception e) {
                log.error("Failed to delete MinIO object {}", doc.getObjectKey(), e);
                return R.fail(502, "对象存储删除失败，文档已保留以便重试");
            }
        }
        chunkMapper.selectList(new LambdaQueryWrapper<BotKnowledgeChunk>()
                .eq(BotKnowledgeChunk::getDocumentId, id))
            .forEach(chunk -> vectorSearch.removeChunk(chunk.getId()));
        chunkMapper.delete(new LambdaQueryWrapper<BotKnowledgeChunk>()
            .eq(BotKnowledgeChunk::getDocumentId, id));
        mapper.deleteById(id);
        indexService.sync();
        return R.ok();
    }

    private static String safeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return "document";
        return Path.of(fileName).getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public record DirectAnswerRequest(boolean enabled, Integer version) {}

    private void populateChunkStatistics(BotKnowledgeDocument document) {
        List<BotKnowledgeChunk> chunks = chunkMapper.selectList(
            new LambdaQueryWrapper<BotKnowledgeChunk>()
                .eq(BotKnowledgeChunk::getDocumentId, document.getId()));
        document.setChunkCount(chunks.size());
        document.setEmbeddingCount((int) chunks.stream().filter(AdminDocController::hasEmbedding).count());
        document.setApprovedCount((int) chunks.stream()
            .filter(chunk -> "APPROVED".equals(chunk.getStatus())).count());
    }

    private static boolean hasEmbedding(BotKnowledgeChunk chunk) {
        return chunk != null && chunk.getEmbedding() != null && !chunk.getEmbedding().isBlank();
    }

    private static String chunkEmbeddingText(BotKnowledgeChunk chunk) {
        return KnowledgeTextUtil.chunkEmbeddingText(chunk.getSectionPath(), chunk.getContent());
    }

    private void applyEmbeddingMetadata(BotKnowledgeChunk chunk, String sourceText, int dimensions) {
        EmbeddingService.EmbeddingDescriptor descriptor = embeddingService.descriptor();
        if (descriptor != null) {
            chunk.setEmbeddingModel(descriptor.model());
            chunk.setEmbeddingVersion(descriptor.version());
        }
        chunk.setEmbeddingDimensions(dimensions);
        chunk.setEmbeddingContentHash(EmbeddingMetadataUtil.contentHash(sourceText));
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) return "OCR failed";
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static String contentType(String fileType) {
        if (fileType == null) return "application/octet-stream";
        return switch (fileType.toLowerCase(Locale.ROOT)) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "bmp" -> "image/bmp";
            case "tif", "tiff" -> "image/tiff";
            case "pdf" -> "application/pdf";
            default -> "application/octet-stream";
        };
    }

    private static String imageKnowledgeText(String fileName, String ocrText) {
        String title = safeFileName(fileName);
        int dot = title.lastIndexOf('.');
        if (dot > 0) title = title.substring(0, dot);
        return "图片名称：" + title + "\n图片文字：" + ocrText;
    }
}
