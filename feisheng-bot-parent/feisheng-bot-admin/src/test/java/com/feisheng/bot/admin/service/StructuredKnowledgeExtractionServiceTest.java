package com.feisheng.bot.admin.service;

import com.feisheng.bot.admin.entity.BotAiModelConfig;
import com.feisheng.bot.admin.entity.BotKnowledgeChunk;
import com.feisheng.bot.admin.entity.BotKnowledgeDocument;
import com.feisheng.bot.admin.mapper.BotAiModelConfigMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeDocumentMapper;
import com.feisheng.bot.core.client.LlmHttpClient;
import com.feisheng.bot.core.dto.ChatResponse;
import com.feisheng.bot.knowledge.entity.BotKnowledgeSemanticUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StructuredKnowledgeExtractionServiceTest {
    private BotKnowledgeDocumentMapper documentMapper;
    private BotKnowledgeChunkMapper chunkMapper;
    private BotAiModelConfigMapper modelMapper;
    private LlmHttpClient llmHttpClient;
    private EmbeddingService embeddingService;
    private StructuredKnowledgeDraftPersistenceService persistenceService;
    private ObjectMapper objectMapper;
    private StructuredKnowledgeExtractionService service;

    @BeforeEach
    void setUp() {
        documentMapper = mock(BotKnowledgeDocumentMapper.class);
        chunkMapper = mock(BotKnowledgeChunkMapper.class);
        modelMapper = mock(BotAiModelConfigMapper.class);
        llmHttpClient = mock(LlmHttpClient.class);
        embeddingService = mock(EmbeddingService.class);
        persistenceService = mock(StructuredKnowledgeDraftPersistenceService.class);
        objectMapper = new ObjectMapper();
        service = new StructuredKnowledgeExtractionService(
            documentMapper, chunkMapper, modelMapper, llmHttpClient, embeddingService,
            new StructuredKnowledgeResponseParser(objectMapper), persistenceService,
            objectMapper, 8, 8000, 20, 240000, 0);
    }

    @Test
    void usesExtractionModelAndPersistsEmbeddedDraft() {
        BotAiModelConfig model = model(7L, "Extraction");
        when(modelMapper.selectList(any())).thenReturn(List.of(model));
        when(llmHttpClient.callWithPolicy(anyString(), anyString(), anyString(), anyString(),
            anyString(), anyString(), anyInt(), anyInt()))
            .thenReturn(new ChatResponse(validJson(), true));
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embedBatch(anyList()))
            .thenReturn(List.of(new float[]{0.1f, 0.2f}));
        when(embeddingService.descriptor()).thenReturn(
            new EmbeddingService.EmbeddingDescriptor("embed-small", "embed-v1"));
        when(persistenceService.replaceDrafts(anyLong(), anyString(), anyList(), anyBoolean()))
            .thenReturn(new StructuredKnowledgeDraftPersistenceService.PersistResult(
                1, 1, 0, 0, 0, List.of(101L)));

        StructuredKnowledgeExtractionService.ExtractionReport report =
            service.extractChunks(document(), List.of(chunk()), null);

        assertEquals("SUCCESS", report.status());
        assertEquals(1, report.validatedUnits());
        assertEquals(1, report.embeddedUnits());
        assertEquals("extract-small", report.extractorModel());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BotKnowledgeSemanticUnit>> entities =
            ArgumentCaptor.forClass(List.class);
        verify(persistenceService).replaceDrafts(eq(5L), eq(report.sourceHash()),
            entities.capture(), eq(true));
        BotKnowledgeSemanticUnit draft = entities.getValue().get(0);
        assertEquals("DRAFT", draft.getStatus());
        assertEquals("[11]", draft.getEvidenceChunkIdsJson());
        assertEquals(2, draft.getEmbeddingDimensions());
        assertEquals("embed-v1", draft.getEmbeddingVersion());
        verify(llmHttpClient).callWithPolicy(eq("https://extract.test/chat/completions"),
            eq("secret"), eq("extract-small"), anyString(), anyString(), eq("extraction"),
            eq(240000), eq(0));
    }

    @Test
    void returnsUnavailableInsteadOfFallingBackToProductionLlm() {
        when(modelMapper.selectList(any())).thenReturn(List.of());

        StructuredKnowledgeExtractionService.ExtractionReport report =
            service.extractChunks(document(), List.of(chunk()), null);

        assertEquals("UNAVAILABLE", report.status());
        assertTrue(report.message().contains("不会回退"));
        verify(llmHttpClient, never()).callWithPolicy(anyString(), anyString(), anyString(),
            anyString(), anyString(), anyString(), anyInt(), anyInt());
        verify(persistenceService, never()).replaceDrafts(
            anyLong(), anyString(), anyList(), anyBoolean());
    }

    @Test
    void rejectsExplicitProductionLlmModel() {
        BotAiModelConfig model = model(9L, "LLM");
        when(modelMapper.selectById(9L)).thenReturn(model);

        StructuredKnowledgeExtractionService.ExtractionReport report =
            service.extractChunks(document(), List.of(chunk()), 9L);

        assertEquals("UNAVAILABLE", report.status());
        verify(llmHttpClient, never()).callWithPolicy(anyString(), anyString(), anyString(),
            anyString(), anyString(), anyString(), anyInt(), anyInt());
        verify(persistenceService, never()).replaceDrafts(
            anyLong(), anyString(), anyList(), anyBoolean());
    }

    @Test
    void invalidModelJsonFailsClosedWithoutReplacingDrafts() {
        when(modelMapper.selectList(any())).thenReturn(List.of(model(7L, "Extraction")));
        when(llmHttpClient.callWithPolicy(anyString(), anyString(), anyString(), anyString(),
            anyString(), anyString(), anyInt(), anyInt()))
            .thenReturn(new ChatResponse("{}", true));

        StructuredKnowledgeExtractionService.ExtractionReport report =
            service.extractChunks(document(), List.of(chunk()), null);

        assertEquals("FAILED", report.status());
        assertEquals("INVALID_MODEL_JSON", report.errors().get(0).code());
        verify(persistenceService, never()).replaceDrafts(
            anyLong(), anyString(), anyList(), anyBoolean());
    }

    @Test
    void persistsValidUnitsWhenAnotherUnitInTheBatchFailsValidation() throws Exception {
        ObjectNode root = (ObjectNode) objectMapper.readTree(validJson());
        ArrayNode units = (ArrayNode) root.get("units");
        ObjectNode invalid = units.get(0).deepCopy();
        ((ArrayNode) invalid.get("conditions")).set(0, objectMapper.getNodeFactory()
            .textNode("原文没有的条件"));
        units.add(invalid);

        when(modelMapper.selectList(any())).thenReturn(List.of(model(7L, "Extraction")));
        when(llmHttpClient.callWithPolicy(anyString(), anyString(), anyString(), anyString(),
            anyString(), anyString(), anyInt(), anyInt()))
            .thenReturn(new ChatResponse(objectMapper.writeValueAsString(root), true));
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embedBatch(anyList()))
            .thenReturn(List.of(new float[]{0.1f, 0.2f}));
        when(embeddingService.descriptor()).thenReturn(
            new EmbeddingService.EmbeddingDescriptor("embed-small", "embed-v1"));
        when(persistenceService.replaceDrafts(anyLong(), anyString(), anyList(), anyBoolean()))
            .thenReturn(new StructuredKnowledgeDraftPersistenceService.PersistResult(
                1, 1, 0, 0, 0, List.of(101L)));

        StructuredKnowledgeExtractionService.ExtractionReport report =
            service.extractChunks(document(), List.of(chunk()), null);

        assertEquals("PARTIAL", report.status());
        assertEquals(1, report.successfulBatches());
        assertEquals(1, report.validatedUnits());
        assertTrue(report.errors().stream()
            .anyMatch(error -> "INVALID_MODEL_UNIT".equals(error.code())));
        verify(persistenceService).replaceDrafts(
            eq(5L), eq(report.sourceHash()), anyList(), eq(true));
    }

    @Test
    void directOfflineEntryRejectsIncompleteDocument() {
        BotKnowledgeDocument document = document();
        document.setStatus(1);

        StructuredKnowledgeExtractionService.ExtractionException error = assertThrows(
            StructuredKnowledgeExtractionService.ExtractionException.class,
            () -> service.extractChunks(document, List.of(chunk()), null));

        assertEquals(409, error.status());
        verify(modelMapper, never()).selectList(any());
        verify(persistenceService, never()).replaceDrafts(
            anyLong(), anyString(), anyList(), anyBoolean());
    }

    @Test
    void directOfflineEntryRejectsChatDocument() {
        BotKnowledgeDocument document = document();
        document.setSourceScope("CHAT");

        StructuredKnowledgeExtractionService.ExtractionException error = assertThrows(
            StructuredKnowledgeExtractionService.ExtractionException.class,
            () -> service.extractChunks(document, List.of(chunk()), null));

        assertEquals(400, error.status());
        verify(modelMapper, never()).selectList(any());
    }

    private BotAiModelConfig model(Long id, String type) {
        BotAiModelConfig model = new BotAiModelConfig();
        model.setId(id);
        model.setStatus(1);
        model.setIsDefault(1);
        model.setModelType(type);
        model.setModelName("extract-small");
        model.setApiUrl("https://extract.test/chat/completions");
        model.setApiKey("secret");
        return model;
    }

    private BotKnowledgeDocument document() {
        BotKnowledgeDocument document = new BotKnowledgeDocument();
        document.setId(5L);
        document.setCategoryId(3L);
        document.setSourceScope("KNOWLEDGE");
        document.setStatus(2);
        return document;
    }

    private BotKnowledgeChunk chunk() {
        BotKnowledgeChunk chunk = new BotKnowledgeChunk();
        chunk.setId(11L);
        chunk.setDocumentId(5L);
        chunk.setChunkIndex(0);
        chunk.setContent("点签电子合同支持手机端签署，有效期为30天。");
        return chunk;
    }

    private String validJson() {
        return """
            {"schema_version":"structured-knowledge-unit-v1","units":[{
              "unit_type":"QA",
              "question":"点签电子合同如何在手机端签署？",
              "statement":"点签电子合同支持手机端签署，有效期为30天。",
              "intent":"contract_signing",
              "entities":["点签电子合同"],
              "conditions":["手机端"],
              "exclusions":[],
              "query_variants":["点签电子合同手机端怎么签？"],
              "metadata":{"product":"点签电子合同","channel":"手机端",
                "audience":"","risk_level":"UNKNOWN","effective_from":"","effective_to":""},
              "extraction_confidence":0.94,
              "evidence":[{"chunk_id":11,"quote":"点签电子合同支持手机端签署，有效期为30天。"}]
            }]}
            """;
    }
}
