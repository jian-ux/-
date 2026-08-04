package com.feisheng.bot.common.dto;

import java.util.List;
import java.util.Objects;

/** A validated, evidence-backed semantic unit that still requires review. */
public record StructuredKnowledgeUnit(
        String unitKey,
        UnitType unitType,
        String question,
        String statement,
        String intent,
        List<String> entities,
        List<String> conditions,
        List<String> exclusions,
        List<String> queryVariants,
        List<Long> evidenceChunkIds,
        List<SourceSpan> sourceSpans,
        BusinessMetadata metadata,
        double extractionConfidence,
        String extractorModel,
        String promptVersion,
        String schemaVersion,
        String sourceHash,
        String status,
        boolean candidateOnly) {

    public StructuredKnowledgeUnit {
        unitKey = required(unitKey, "unitKey");
        unitType = Objects.requireNonNull(unitType, "unitType");
        question = required(question, "question");
        statement = required(statement, "statement");
        intent = required(intent, "intent");
        entities = immutable(entities);
        conditions = immutable(conditions);
        exclusions = immutable(exclusions);
        queryVariants = immutable(queryVariants);
        evidenceChunkIds = List.copyOf(Objects.requireNonNull(evidenceChunkIds,
            "evidenceChunkIds"));
        sourceSpans = List.copyOf(Objects.requireNonNull(sourceSpans, "sourceSpans"));
        metadata = Objects.requireNonNull(metadata, "metadata");
        extractorModel = required(extractorModel, "extractorModel");
        promptVersion = required(promptVersion, "promptVersion");
        schemaVersion = required(schemaVersion, "schemaVersion");
        sourceHash = required(sourceHash, "sourceHash");
        status = required(status, "status");
        if (evidenceChunkIds.isEmpty() || sourceSpans.isEmpty()) {
            throw new IllegalArgumentException("evidence must not be empty");
        }
        if (!Double.isFinite(extractionConfidence)
                || extractionConfidence < 0.0 || extractionConfidence > 1.0) {
            throw new IllegalArgumentException("extractionConfidence must be in [0, 1]");
        }
    }

    public enum UnitType {
        QA, FACT, PROCEDURE, POLICY
    }

    public enum RiskLevel {
        LOW, MEDIUM, HIGH, UNKNOWN
    }

    public record SourceSpan(Long chunkId, int start, int end, String quote) {
        public SourceSpan {
            Objects.requireNonNull(chunkId, "chunkId");
            quote = required(quote, "quote");
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("invalid source span");
            }
        }
    }

    public record BusinessMetadata(
            String product,
            String channel,
            String audience,
            RiskLevel riskLevel,
            String effectiveFrom,
            String effectiveTo) {
        public BusinessMetadata {
            product = optional(product);
            channel = optional(channel);
            audience = optional(audience);
            riskLevel = Objects.requireNonNull(riskLevel, "riskLevel");
            effectiveFrom = optional(effectiveFrom);
            effectiveTo = optional(effectiveTo);
        }
    }

    private static List<String> immutable(List<String> values) {
        return List.copyOf(Objects.requireNonNull(values, "list value"));
    }

    private static String required(String value, String name) {
        String normalized = optional(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }

    private static String optional(String value) {
        return value == null ? "" : value.trim();
    }
}
