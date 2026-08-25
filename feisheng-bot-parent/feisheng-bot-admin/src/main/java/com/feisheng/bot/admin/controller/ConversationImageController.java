package com.feisheng.bot.admin.controller;

import com.feisheng.bot.admin.service.ConversationImageService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
public class ConversationImageController {
    private final ConversationImageService imageService;

    public ConversationImageController(ConversationImageService imageService) {
        this.imageService = imageService;
    }

    @GetMapping("/api/public/conversation-images/{conversationId}/{messageId}")
    public ResponseEntity<byte[]> image(
            @PathVariable Long conversationId,
            @PathVariable Long messageId,
            @RequestParam long expires,
            @RequestParam String signature) {
        try {
            ConversationImageService.ImageContent image = imageService.load(
                conversationId, messageId, expires, signature);
            MediaType mediaType = MediaType.parseMediaType(image.contentType());
            String disposition = ContentDisposition.inline()
                .filename(image.fileName(), StandardCharsets.UTF_8)
                .build().toString();
            return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .body(image.bytes());
        } catch (ConversationImageService.ImageUnavailableException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
