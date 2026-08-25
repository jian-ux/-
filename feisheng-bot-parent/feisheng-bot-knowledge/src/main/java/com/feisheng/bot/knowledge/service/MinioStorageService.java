package com.feisheng.bot.knowledge.service;

import io.minio.*;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class MinioStorageService {
    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);

    private final MinioClient minioClient;

    @Value("${minio.bucket:feisheng-docs}")
    private String bucket;

    /** Presigned URL TTL: 10 minutes */
    private static final int URL_EXPIRY_MINUTES = 10;

    public MinioStorageService(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    /**
     * Upload a file to MinIO.
     * @return objectKey for persistent storage in DB.
     */
    public UploadResult upload(MultipartFile file) throws Exception {
        try (InputStream input = file.getInputStream()) {
            return upload(input, file.getSize(), file.getOriginalFilename(), file.getContentType());
        }
    }

    public UploadResult upload(Path path, String originalName, String contentType) throws Exception {
        try (InputStream input = Files.newInputStream(path)) {
            return upload(input, Files.size(path), originalName, contentType);
        }
    }

    public UploadResult upload(byte[] content, String originalName, String contentType)
            throws Exception {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("content must not be empty");
        }
        try (InputStream input = new ByteArrayInputStream(content)) {
            return upload(input, content.length, originalName, contentType);
        }
    }

    private UploadResult upload(InputStream input, long size, String originalName, String contentType) throws Exception {
        String ext = getExtension(originalName);
        String objectKey = UUID.randomUUID().toString() + (ext.isEmpty() ? "" : "." + ext);

        minioClient.putObject(
                PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .stream(input, size, -1)
                        .contentType(contentType != null ? contentType : "application/octet-stream")
                        .build()
        );

        log.info("Uploaded to MinIO: bucket={}, objectKey={}, size={}", bucket, objectKey, size);
        return new UploadResult(bucket, objectKey, ext, size);
    }

    /**
     * Generate a presigned GET URL valid for URL_EXPIRY_MINUTES.
     */
    public String getPresignedUrl(String objectKey) throws Exception {
        return minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket(bucket)
                        .object(objectKey)
                        .expiry(URL_EXPIRY_MINUTES, TimeUnit.MINUTES)
                        .build()
        );
    }

    /**
     * Download file as InputStream.
     */
    public InputStream download(String objectKey) throws Exception {
        return minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .build()
        );
    }

    /**
     * Delete file from MinIO.
     */
    public void delete(String objectKey) throws Exception {
        minioClient.removeObject(
                RemoveObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .build()
        );
        log.info("Deleted from MinIO: bucket={}, objectKey={}", bucket, objectKey);
    }

    private String getExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) return "";
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }

    /** Result wrapper for upload operation */
    public record UploadResult(String bucketName, String objectKey, String fileType, long fileSize) {}
}
