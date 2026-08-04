package com.feisheng.bot.knowledge.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class QdrantVectorStoreTest {
    private static final String COLLECTION_INFO = """
        {"result":{"points_count":0,"indexed_vectors_count":0,
        "config":{"params":{"vectors":{"size":2,"distance":"Cosine"}}}}}
        """;

    @Test
    void refusesToCreateAnIndependentClientForThePrimaryCollection() {
        QdrantVectorStore store = new QdrantVectorStore(
            new RestTemplate(), true, "http://qdrant:6333", "knowledge", 2, 64);

        assertThrows(IllegalArgumentException.class,
            () -> store.forCollection(" knowledge "));
    }

    @Test
    void createsCollectionAndFullyReconcilesPoints() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        QdrantVectorStore store = new QdrantVectorStore(
            restTemplate, true, "http://qdrant:6333", "knowledge", 2, 64);
        String expectedId = QdrantVectorStore.pointId("chunk:2");

        server.expect(once(), requestTo("http://qdrant:6333/collections/knowledge"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo("http://qdrant:6333/collections/knowledge"))
            .andExpect(method(HttpMethod.PUT))
            .andExpect(content().json("{\"vectors\":{\"size\":2,\"distance\":\"Cosine\"}}"))
            .andRespond(withSuccess("{\"result\":true,\"status\":\"ok\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://qdrant:6333/collections/knowledge"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(COLLECTION_INFO, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://qdrant:6333/collections/knowledge/points/scroll"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(
                "{\"result\":{\"points\":[{\"id\":\"stale-id\"}],\"next_page_offset\":null}}",
                MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://qdrant:6333/collections/knowledge/points/delete?wait=true"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("{\"points\":[\"stale-id\"]}"))
            .andRespond(withSuccess("{\"result\":{\"status\":\"completed\"}}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://qdrant:6333/collections/knowledge/points?wait=true"))
            .andExpect(method(HttpMethod.PUT))
            .andExpect(content().json("""
                {"points":[{"id":"%s","vector":[1.0,0.0],
                "payload":{"type":"chunk","chunkId":2}}]}
                """.formatted(expectedId)))
            .andRespond(withSuccess("{\"result\":{\"status\":\"completed\"}}", MediaType.APPLICATION_JSON));

        QdrantVectorStore.ReconcileResult result = store.reconcile(List.of(
            new QdrantVectorStore.VectorPoint(
                expectedId, List.of(1.0, 0.0), Map.of("type", "chunk", "chunkId", 2L))));

        assertEquals(1, result.upserted());
        assertEquals(1, result.deleted());
        server.verify();
    }

    @Test
    void mapsSearchPayloadAndScore() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        QdrantVectorStore store = new QdrantVectorStore(
            restTemplate, true, "http://qdrant:6333", "knowledge", 2, 64);
        server.expect(once(), requestTo("http://qdrant:6333/collections/knowledge/points/search"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""
                {"vector":[1.0,0.0],"limit":3,"with_payload":true,
                "with_vector":false,"score_threshold":0.6}
                """))
            .andRespond(withSuccess("""
                {"result":[{"id":"point-id","score":0.91,
                "payload":{"type":"item","answer":"reset it"}}]}
                """, MediaType.APPLICATION_JSON));

        List<QdrantVectorStore.SearchHit> hits = store.search(List.of(1.0, 0.0), 3, 0.6);

        assertEquals(1, hits.size());
        assertEquals(0.91, hits.get(0).score(), 0.0001);
        assertEquals("reset it", hits.get(0).payload().get("answer"));
        server.verify();
    }

    @Test
    void sendsExactAndMultiValuePayloadFilters() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        QdrantVectorStore store = new QdrantVectorStore(
            restTemplate, true, "http://qdrant:6333", "knowledge", 2, 64);
        server.expect(once(), requestTo("http://qdrant:6333/collections/knowledge/points/search"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""
                {"vector":[1.0,0.0],"limit":5,"with_payload":true,
                "with_vector":false,"filter":{"must":[
                  {"key":"categoryId","match":{"value":12}},
                  {"key":"sourceScope","match":{"any":["PUBLIC","TENANT"]}}
                ]}}
                """))
            .andRespond(withSuccess("{\"result\":[]}", MediaType.APPLICATION_JSON));

        List<QdrantVectorStore.SearchHit> hits = store.search(
            List.of(1.0, 0.0), 5, -1,
            Map.of("categoryId", 12, "sourceScope", List.of("PUBLIC", "TENANT")));

        assertEquals(0, hits.size());
        server.verify();
    }

    @Test
    void fullyReconcilesWhenRemotePointCountDrifts() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        QdrantVectorStore store = new QdrantVectorStore(
            restTemplate, true, "http://qdrant:6333", "knowledge", 2, 64);
        String expectedId = QdrantVectorStore.pointId("item:7");
        QdrantVectorStore.VectorPoint expected = new QdrantVectorStore.VectorPoint(
            expectedId, List.of(0.0, 1.0), Map.of("type", "item", "itemId", 7L));

        server.expect(once(), requestTo("http://qdrant:6333/collections/knowledge"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(COLLECTION_INFO, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://qdrant:6333/collections/knowledge"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(COLLECTION_INFO, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://qdrant:6333/collections/knowledge/points/scroll"))
            .andRespond(withSuccess(
                "{\"result\":{\"points\":[],\"next_page_offset\":null}}",
                MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://qdrant:6333/collections/knowledge/points?wait=true"))
            .andExpect(method(HttpMethod.PUT))
            .andRespond(withSuccess("{\"result\":{\"status\":\"completed\"}}", MediaType.APPLICATION_JSON));

        QdrantVectorStore.ReconcileResult result = store.applyChanges(
            List.of(), List.of(), List.of(expected));

        assertEquals(1, result.upserted());
        assertEquals(0, result.deleted());
        server.verify();
    }

    @Test
    void generatesStableIdsForDifferentSourceTypes() {
        assertEquals(QdrantVectorStore.pointId("item:1"), QdrantVectorStore.pointId("item:1"));
        assertNotEquals(QdrantVectorStore.pointId("item:1"), QdrantVectorStore.pointId("chunk:1"));
    }

    @Test
    void derivedStoreUsesAnIndependentCollection() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        QdrantVectorStore primary = new QdrantVectorStore(
            restTemplate, true, "http://qdrant:6333", "knowledge", 2, 64);
        QdrantVectorStore semanticUnits = primary.forCollection("semantic-units");
        server.expect(once(), requestTo(
                "http://qdrant:6333/collections/semantic-units/points/search"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("{\"result\":[]}", MediaType.APPLICATION_JSON));

        semanticUnits.search(List.of(1.0, 0.0), 3, -1);

        assertEquals("knowledge", primary.lastKnownStatus().collection());
        assertEquals("semantic-units", semanticUnits.lastKnownStatus().collection());
        server.verify();
    }
}
