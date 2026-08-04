package com.feisheng.bot.knowledge.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bm25SearchIndexTest {
    @Test
    void ranksRareChineseTermsAboveGenericContent() {
        Bm25SearchIndex index = Bm25SearchIndex.build(List.of(
            new Bm25SearchIndex.SourceDocument(
                Map.of("type", "chunk", "chunkId", 1L),
                "电子合同支持企业在线签署和归档"),
            new Bm25SearchIndex.SourceDocument(
                Map.of("type", "chunk", "chunkId", 2L),
                "ERR42签署错误表示企业证书已经过期，请重新申请证书")));

        List<Map<String, Object>> result = index.search("ERR42证书过期", 2, 0);

        assertEquals(2L, result.get(0).get("chunkId"));
        assertEquals("bm25", result.get(0).get("matchMode"));
        assertTrue(((Number) result.get(0).get("bm25Score")).doubleValue() > 0);
    }

    @Test
    void tokenizesChineseBigramsAndAsciiIdentifiers() {
        List<String> terms = Bm25SearchIndex.tokenize("合同 ERR-42");

        assertTrue(terms.contains("合同"));
        assertTrue(terms.contains("err"));
        assertTrue(terms.contains("42"));
    }
}
