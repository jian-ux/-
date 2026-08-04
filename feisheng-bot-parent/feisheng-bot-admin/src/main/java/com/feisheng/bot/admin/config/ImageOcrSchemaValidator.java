package com.feisheng.bot.admin.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ImageOcrSchemaValidator implements ApplicationRunner {
    private static final Set<String> REQUIRED_COLUMNS = Set.of(
        "media_type", "source_scope", "ocr_status", "ocr_text",
        "ocr_language", "ocr_error", "expires_at");

    private final JdbcTemplate jdbcTemplate;

    public ImageOcrSchemaValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> columns = jdbcTemplate.queryForList(
            "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bot_knowledge_document'",
            String.class);
        Set<String> actual = Set.copyOf(columns);
        List<String> missing = REQUIRED_COLUMNS.stream()
            .filter(column -> !actual.contains(column))
            .sorted()
            .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                "Image OCR schema is missing columns " + missing
                    + "; execute feisheng-bot-parent/sql/07_add_image_ocr.sql");
        }
    }
}
