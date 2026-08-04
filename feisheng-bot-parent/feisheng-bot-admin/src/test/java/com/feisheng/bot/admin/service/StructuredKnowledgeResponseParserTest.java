package com.feisheng.bot.admin.service;

import com.feisheng.bot.admin.entity.BotKnowledgeChunk;
import com.feisheng.bot.common.dto.StructuredKnowledgeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredKnowledgeResponseParserTest {
    private static final String SOURCE = "点签电子合同支持手机端签署，有效期为30天。";
    private final StructuredKnowledgeResponseParser parser =
        new StructuredKnowledgeResponseParser(new ObjectMapper());

    @Test
    void parsesStrictEvidenceBackedUnitAndDerivesSpan() throws Exception {
        List<StructuredKnowledgeUnit> units = parser.parse(validJson(),
            List.of(chunk(11L, SOURCE)), "source-hash", "extract-small", 10);

        assertEquals(1, units.size());
        StructuredKnowledgeUnit unit = units.get(0);
        assertEquals(StructuredKnowledgeUnit.UnitType.QA, unit.unitType());
        assertEquals(List.of(11L), unit.evidenceChunkIds());
        assertEquals(0, unit.sourceSpans().get(0).start());
        assertEquals(SOURCE.length(), unit.sourceSpans().get(0).end());
        assertEquals("DRAFT", unit.status());
        assertTrue(unit.candidateOnly());
        assertEquals("点签电子合同", unit.metadata().product());
    }

    @Test
    void rejectsEvidenceChunkOutsideCurrentBatch() {
        String json = validJson().replace("\"chunk_id\":11", "\"chunk_id\":99");

        assertThrows(StructuredKnowledgeResponseParser.ValidationException.class,
            () -> parser.parse(json, List.of(chunk(11L, SOURCE)),
                "source-hash", "extract-small", 10));
    }

    @Test
    void rejectsQuoteThatIsNotAnExactSourceSubstring() {
        String json = validJson().replace(SOURCE, "点签电子合同也支持线下签署。");

        assertThrows(StructuredKnowledgeResponseParser.ValidationException.class,
            () -> parser.parse(json, List.of(chunk(11L, SOURCE)),
                "source-hash", "extract-small", 10));
    }

    @Test
    void rejectsParaphrasedStatementEvenWhenEvidenceIsValid() {
        String json = validJson().replace(
            "\"statement\":\"" + SOURCE + "\"",
            "\"statement\":\"用户可在手机上完成合同签署。\"");

        assertThrows(StructuredKnowledgeResponseParser.ValidationException.class,
            () -> parser.parse(json, List.of(chunk(11L, SOURCE)),
                "source-hash", "extract-small", 10));
    }

    @Test
    void rejectsNewNumberIntroducedByQueryVariant() {
        String json = validJson().replace(
            "点签电子合同手机端怎么签？", "点签电子合同60天内怎么签？");

        assertThrows(StructuredKnowledgeResponseParser.ValidationException.class,
            () -> parser.parse(json, List.of(chunk(11L, SOURCE)),
                "source-hash", "extract-small", 10));
    }

    @Test
    void rejectsStatementAssembledAcrossMultipleEvidenceQuotes() {
        String json = strictJson(
            "alpha evidence part\\nbeta evidence part",
            "general_intent",
            "[{\"chunk_id\":21,\"quote\":\"alpha evidence part\"},"
                + "{\"chunk_id\":22,\"quote\":\"beta evidence part\"}]");

        assertThrows(StructuredKnowledgeResponseParser.ValidationException.class,
            () -> parser.parse(json,
                List.of(chunk(21L, "alpha evidence part"),
                    chunk(22L, "beta evidence part")),
                "source-hash", "extract-small", 10));
    }

    @Test
    void rejectsNewIdentifierAndNumberIntroducedByIntent() {
        String json = strictJson(
            "alpha evidence part",
            "policy_2027",
            "[{\"chunk_id\":21,\"quote\":\"alpha evidence part\"}]");

        assertThrows(StructuredKnowledgeResponseParser.ValidationException.class,
            () -> parser.parse(json, List.of(chunk(21L, "alpha evidence part")),
                "source-hash", "extract-small", 10));
    }

    @Test
    void rejectsMarkdownAndUnexpectedFields() {
        assertThrows(Exception.class,
            () -> parser.parse("```json\n" + validJson() + "\n```",
                List.of(chunk(11L, SOURCE)), "source-hash", "extract-small", 10));
        String extra = validJson().replace(
            "\"schema_version\":", "\"unexpected\":true,\"schema_version\":");
        assertThrows(StructuredKnowledgeResponseParser.ValidationException.class,
            () -> parser.parse(extra, List.of(chunk(11L, SOURCE)),
                "source-hash", "extract-small", 10));
    }

    @Test
    void rejectsDuplicateJsonFields() {
        String duplicateRoot = validJson().replace(
            "\"schema_version\":", "\"schema_version\":\"structured-knowledge-unit-v1\","
                + "\"schema_version\":");

        assertThrows(Exception.class,
            () -> parser.parse(duplicateRoot, List.of(chunk(11L, SOURCE)),
                "source-hash", "extract-small", 10));
    }

    @Test
    void rejectsModelAssignedRiskLevel() {
        String assignedRisk = validJson().replace(
            "\"risk_level\":\"UNKNOWN\"", "\"risk_level\":\"HIGH\"");

        StructuredKnowledgeResponseParser.ValidationException error = assertThrows(
            StructuredKnowledgeResponseParser.ValidationException.class,
            () -> parser.parse(assignedRisk, List.of(chunk(11L, SOURCE)),
                "source-hash", "extract-small", 10));
        assertTrue(error.getMessage().contains("risk_level must be UNKNOWN"));
    }

    @Test
    void rejectsNegationIntroducedByQuestion() {
        String flipped = validJson().replace(
            "点签电子合同如何在手机端签署？", "点签电子合同为什么不能在手机端签署？");

        StructuredKnowledgeResponseParser.ValidationException error = assertThrows(
            StructuredKnowledgeResponseParser.ValidationException.class,
            () -> parser.parse(flipped, List.of(chunk(11L, SOURCE)),
                "source-hash", "extract-small", 10));
        assertTrue(error.getMessage().contains("negation"));
    }

    @Test
    void appendsEvidenceBackedConditionWhenQuestionDropsIt() throws Exception {
        String droppedCondition = validJson().replace(
            "点签电子合同如何在手机端签署？", "点签电子合同如何签署？");

        List<StructuredKnowledgeUnit> units = parser.parse(
            droppedCondition, List.of(chunk(11L, SOURCE)),
            "source-hash", "extract-small", 10);

        assertTrue(units.get(0).question().contains("手机端"));
        assertTrue(units.get(0).queryVariants().get(0).contains("手机端"));
    }

    @Test
    void removesOptionalEntityAndMetadataThatAreNotEvidenceBacked() throws Exception {
        String inferredFields = validJson()
            .replace("\"entities\":[\"点签电子合同\"]", "\"entities\":[\"推断产品\"]")
            .replace("\"product\":\"点签电子合同\"", "\"product\":\"推断产品\"");

        List<StructuredKnowledgeUnit> units = parser.parse(
            inferredFields, List.of(chunk(11L, SOURCE)),
            "source-hash", "extract-small", 10);

        assertTrue(units.get(0).entities().isEmpty());
        assertEquals("", units.get(0).metadata().product());
    }

    @Test
    void partialParsingKeepsValidUnitsAndReportsInvalidUnits() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) objectMapper.readTree(validJson());
        ArrayNode units = (ArrayNode) root.get("units");
        ObjectNode invalid = units.get(0).deepCopy();
        ((ArrayNode) invalid.get("conditions")).set(0, objectMapper.getNodeFactory()
            .textNode("原文没有的条件"));
        units.add(invalid);

        StructuredKnowledgeResponseParser.ParseResult result = parser.parsePartial(
            objectMapper.writeValueAsString(root), List.of(chunk(11L, SOURCE)),
            "source-hash", "extract-small", 10);

        assertEquals(1, result.units().size());
        assertEquals(1, result.rejections().size());
        assertEquals(1, result.rejections().get(0).unitIndex());
        assertTrue(result.rejections().get(0).message().contains("condition"));
    }

    private String validJson() {
        return """
            {
              "schema_version":"structured-knowledge-unit-v1",
              "units":[{
                "unit_type":"QA",
                "question":"点签电子合同如何在手机端签署？",
                "statement":"点签电子合同支持手机端签署，有效期为30天。",
                "intent":"contract_signing",
                "entities":["点签电子合同"],
                "conditions":["手机端"],
                "exclusions":[],
                "query_variants":["点签电子合同手机端怎么签？"],
                "metadata":{
                  "product":"点签电子合同",
                  "channel":"手机端",
                  "audience":"",
                  "risk_level":"UNKNOWN",
                  "effective_from":"",
                  "effective_to":""
                },
                "extraction_confidence":0.94,
                "evidence":[{"chunk_id":11,"quote":"点签电子合同支持手机端签署，有效期为30天。"}]
              }]
            }
            """;
    }

    private String strictJson(String statement, String intent, String evidence) {
        return """
            {
              "schema_version":"structured-knowledge-unit-v1",
              "units":[{
                "unit_type":"FACT",
                "question":"What is the alpha evidence?",
                "statement":"%s",
                "intent":"%s",
                "entities":[],
                "conditions":[],
                "exclusions":[],
                "query_variants":[],
                "metadata":{
                  "product":"",
                  "channel":"",
                  "audience":"",
                  "risk_level":"UNKNOWN",
                  "effective_from":"",
                  "effective_to":""
                },
                "extraction_confidence":0.9,
                "evidence":%s
              }]
            }
            """.formatted(statement, intent, evidence);
    }

    private BotKnowledgeChunk chunk(Long id, String content) {
        BotKnowledgeChunk chunk = new BotKnowledgeChunk();
        chunk.setId(id);
        chunk.setDocumentId(5L);
        chunk.setChunkIndex(0);
        chunk.setContent(content);
        return chunk;
    }
}
