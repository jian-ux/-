package com.feisheng.bot.knowledge.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.common.vo.R;
import com.feisheng.bot.knowledge.entity.BotKnowledgeDocument;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeDocumentMapper;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.knowledge.entity.BotKnowledgeChunk;
import com.feisheng.bot.knowledge.service.KnowledgeIndexService;
import com.feisheng.bot.knowledge.service.MinioStorageService;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/knowledge/document")
public class DocumentController {
    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final BotKnowledgeDocumentMapper mapper;
    private final BotKnowledgeChunkMapper chunkMapper;
    private final MinioStorageService storageService;
    private final KnowledgeIndexService indexService;

    public DocumentController(BotKnowledgeDocumentMapper mapper,
                              BotKnowledgeChunkMapper chunkMapper,
                              MinioStorageService storageService,
                              KnowledgeIndexService indexService) {
        this.mapper = mapper;
        this.chunkMapper = chunkMapper;
        this.storageService = storageService;
        this.indexService = indexService;
    }

    /**
     * Upload document to MinIO and save metadata to DB.
     */
    @PostMapping("/upload")
    public R<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        try {
            MinioStorageService.UploadResult result = storageService.upload(file);

            BotKnowledgeDocument doc = new BotKnowledgeDocument();
            doc.setTitle(file.getOriginalFilename());
            doc.setFileName(file.getOriginalFilename());
            doc.setBucketName(result.bucketName());
            doc.setObjectKey(result.objectKey());
            doc.setFileType(result.fileType());
            doc.setFileSize(result.fileSize());
            doc.setStatus(1);
            mapper.insert(doc);

            Map<String, Object> data = new HashMap<>();
            data.put("id", doc.getId());
            data.put("title", doc.getTitle());
            data.put("fileType", result.fileType());
            data.put("fileSize", result.fileSize());
            return R.ok(data);
        } catch (Exception e) {
            log.error("Upload failed", e);
            return R.fail(500, "上传失败");
        }
    }

    /**
     * List documents with real-time presigned download URLs.
     */
    @GetMapping("/list")
    public R<Page<Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") int p,
            @RequestParam(defaultValue = "20") int s) {
        Page<BotKnowledgeDocument> page = mapper.selectPage(
                new Page<>(p, s),
                new LambdaQueryWrapper<BotKnowledgeDocument>()
                        .eq(BotKnowledgeDocument::getSourceScope, "KNOWLEDGE")
                        .orderByDesc(BotKnowledgeDocument::getCreateTime));

        Page<Map<String, Object>> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        List<Map<String, Object>> records = new ArrayList<>();

        for (BotKnowledgeDocument doc : page.getRecords()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", doc.getId());
            item.put("title", doc.getTitle());
            item.put("fileName", doc.getFileName());
            item.put("fileType", doc.getFileType());
            item.put("mediaType", doc.getMediaType());
            item.put("ocrStatus", doc.getOcrStatus());
            item.put("ocrLanguage", doc.getOcrLanguage());
            item.put("ocrError", doc.getOcrError());
            item.put("fileSize", doc.getFileSize());
            item.put("categoryId", doc.getCategoryId());
            item.put("status", doc.getStatus());
            item.put("createTime", doc.getCreateTime());

            if (doc.getObjectKey() != null && !doc.getObjectKey().isEmpty()
                    && mapper.countActiveObjectReferences(doc.getBucketName(), doc.getObjectKey()) <= 1) {
                item.put("downloadUrl", "/api/knowledge/document/" + doc.getId() + "/download");
            } else {
                item.put("downloadUrl", null);
            }
            records.add(item);
        }
        result.setRecords(records);
        return R.ok(result);
    }

    /**
     * Download/stream a document by ID.
     */
    @GetMapping("/{id}/download")
    public void download(@PathVariable Long id, HttpServletResponse response) {
        try {
            BotKnowledgeDocument doc = mapper.selectById(id);
            if (doc == null || doc.getObjectKey() == null || doc.getObjectKey().isEmpty()) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                response.getWriter().write("文档不存在");
                return;
            }

            String fileName = doc.getFileName() != null ? doc.getFileName() : "document";
            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition",
                    "attachment; filename=\"" + URLEncoder.encode(fileName, StandardCharsets.UTF_8) + "\"");

            try (InputStream in = storageService.download(doc.getObjectKey());
                 OutputStream out = response.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }
        } catch (Exception e) {
            log.error("Download failed for doc {}", id, e);
            try {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.getWriter().write("下载失败");
            } catch (Exception ignored) {}
        }
    }

    /**
     * Delete document from MinIO and DB.
     */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        try {
            BotKnowledgeDocument doc = mapper.selectById(id);
            if (doc == null) {
                return R.fail(404, "文档不存在");
            }
            if (doc.getObjectKey() != null && !doc.getObjectKey().isEmpty()) {
                try {
                    storageService.delete(doc.getObjectKey());
                } catch (Exception e) {
                    log.error("MinIO delete failed for doc {}, objectKey {}", id, doc.getObjectKey(), e);
                    return R.fail(502, "对象存储删除失败，文档已保留以便重试");
                }
            }
            chunkMapper.delete(new LambdaQueryWrapper<BotKnowledgeChunk>()
                .eq(BotKnowledgeChunk::getDocumentId, id));
            mapper.deleteById(id);
            indexService.sync();
            return R.ok();
        } catch (Exception e) {
            log.error("Delete failed for doc {}", id, e);
            return R.fail(500, "删除失败");
        }
    }
}
