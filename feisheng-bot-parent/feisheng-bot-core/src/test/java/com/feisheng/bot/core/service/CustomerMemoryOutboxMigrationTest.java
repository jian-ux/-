package com.feisheng.bot.core.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomerMemoryOutboxMigrationTest {
    @Test
    void migrationContainsDurableClaimAndDedupContract() throws Exception {
        Path migration = Path.of("..", "sql", "44_add_dialog_memory_outbox.sql");
        if (!Files.exists(migration)) migration = Path.of("feisheng-bot-parent", "sql",
            "44_add_dialog_memory_outbox.sql");
        String sql = Files.readString(migration).toLowerCase();
        assertTrue(sql.contains("status") && sql.contains("attempts"));
        assertTrue(sql.contains("available_at") && sql.contains("locked_until"));
        assertTrue(sql.contains("dedup_key"));
        assertTrue(sql.contains("unique key"));
        assertTrue(sql.contains("idx_memory_outbox_pending"));
        assertTrue(sql.contains("idx_memory_outbox_lease"));
    }
}
