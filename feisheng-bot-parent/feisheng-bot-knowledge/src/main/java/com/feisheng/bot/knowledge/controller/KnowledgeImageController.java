package com.feisheng.bot.knowledge.controller;

import com.feisheng.bot.knowledge.service.KnowledgeImageService;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/public/knowledge-images")
public class KnowledgeImageController {
    private final KnowledgeImageService imageService;

    public KnowledgeImageController(KnowledgeImageService imageService) {
        this.imageService = imageService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> image(
            @PathVariable Long id,
            @RequestParam long expires,
            @RequestParam String signature) {
        if (!imageService.verify(id, expires, signature)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            KnowledgeImageService.ImageContent image = imageService.load(id);
            ContentDisposition disposition = ContentDisposition.inline()
                .filename(image.fileName(), StandardCharsets.UTF_8)
                .build();
            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .contentLength(image.bytes().length)
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(image.bytes());
        } catch (KnowledgeImageService.ImageUnavailableException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
