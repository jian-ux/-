package com.feisheng.bot.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomerLongTermStorageContractTest {

    @Test
    void customerStoresLongTermSummaryFields() throws Exception {
        assertEquals(String.class, field("longTermSummary").getType());
        assertEquals(java.util.Date.class, field("longTermSummaryUpdatedAt").getType());
    }

    @Test
    void memoryAndMediaEntitiesUseDedicatedTables() {
        assertEquals("bot_customer_memory", tableName(BotCustomerMemory.class));
        assertEquals("bot_customer_media", tableName(BotCustomerMedia.class));
        assertTrue(Arrays.stream(BotCustomerMemory.class.getDeclaredFields())
            .map(java.lang.reflect.Field::getName)
            .collect(java.util.stream.Collectors.toSet())
            .containsAll(java.util.List.of("customerId", "memoryKey", "memoryValue", "source", "confidence", "status", "createTime", "updatedAt")));
        assertTrue(Arrays.stream(BotCustomerMedia.class.getDeclaredFields())
            .map(java.lang.reflect.Field::getName)
            .collect(java.util.stream.Collectors.toSet())
            .containsAll(java.util.List.of("customerId", "sourceMessageId", "mediaType", "objectKey", "ocrText", "metadata", "createTime", "updatedAt")));
    }

    @Test
    void migrationDefinesDedicatedTablesAndCustomerSummaryColumns() throws Exception {
        Path migration = locateMigration();
        String sql = Files.readString(migration).toLowerCase(java.util.Locale.ROOT);
        assertTrue(sql.contains("long_term_summary"));
        assertTrue(sql.contains("long_term_summary_updated_at"));
        assertTrue(sql.contains("create table if not exists bot_customer_memory"));
        assertTrue(sql.contains("create table if not exists bot_customer_media"));
        assertTrue(sql.contains("uk_customer_memory"));
        assertTrue(sql.contains("idx_customer_memory_status"));
        assertTrue(sql.contains("idx_customer_media_customer"));
        assertTrue(sql.contains("customer_id"));
        assertTrue(sql.contains("source_message_id"));
        assertTrue(!sql.contains("knowledge_document_id"), "customer media must not reference knowledge documents");
    }

    private static java.lang.reflect.Field field(String name) throws NoSuchFieldException {
        return BotCustomer.class.getDeclaredField(name);
    }

    private static String tableName(Class<?> type) {
        return type.getAnnotation(TableName.class).value();
    }

    private static Path locateMigration() {
        Path moduleRelative = Path.of("..", "sql", "43_add_customer_long_term_context.sql");
        if (Files.exists(moduleRelative)) {
            return moduleRelative;
        }
        return Path.of("sql", "43_add_customer_long_term_context.sql");
    }
}
