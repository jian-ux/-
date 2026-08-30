package com.feisheng.bot.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.knowledge.entity.BotKnowledgeSemanticUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FactComparisonServiceTest {
    private final FactNormalizationService normalization = new FactNormalizationService(new ObjectMapper());
    private final FactComparisonService comparison = new FactComparisonService();

    @Test
    void detectsPolarityConflictAsBlocking() {
        FactNormalizationService.NormalizedFact left = normalization.normalize(unit("可以办理"));
        FactNormalizationService.NormalizedFact right = normalization.normalize(unit("不可以办理"));
        FactComparisonService.ComparisonResult result = comparison.compare(left, right);
        assertEquals(FactComparisonService.Relation.CONFLICT, result.relation());
        assertEquals(FactComparisonService.Severity.BLOCKING, result.severity());
    }

    @Test
    void separatesMutuallyExclusiveScopes() {
        BotKnowledgeSemanticUnit a = unit("可以办理"); a.setMetadataJson("{\"channel\":\"手机端\"}");
        BotKnowledgeSemanticUnit b = unit("不可以办理"); b.setMetadataJson("{\"channel\":\"柜台\"}");
        FactComparisonService.ComparisonResult result = comparison.compare(normalization.normalize(a), normalization.normalize(b));
        assertEquals(FactComparisonService.Relation.SCOPE_DIFFERENCE, result.relation());
    }

    @Test
    void treatsEquivalentNormalizedFactsAsDuplicates() {
        BotKnowledgeSemanticUnit a = unit("可以在7天内提交");
        BotKnowledgeSemanticUnit b = unit("可以在168小时内提交");
        FactComparisonService.ComparisonResult result = comparison.compare(normalization.normalize(a), normalization.normalize(b));
        assertEquals(FactComparisonService.Relation.NOT_CONFLICT, result.relation());
        assertEquals(FactComparisonService.Severity.INFO, result.severity());
    }

    @Test
    void unknownPolarityCannotBeAutomaticallyMarkedAsDuplicate() {
        FactNormalizationService.NormalizedFact normalized = normalization.normalize(unit("可以在7天内提交"));
        FactNormalizationService.NormalizedFact unknown = withPolarity(normalized, "UNKNOWN");
        FactNormalizationService.NormalizedFact known = withPolarity(normalized, "ALLOWED");

        FactComparisonService.ComparisonResult result = comparison.compare(unknown, known);

        assertEquals(FactComparisonService.Relation.UNKNOWN, result.relation());
        assertEquals(FactComparisonService.Severity.BLOCKING, result.severity());
    }

    private FactNormalizationService.NormalizedFact withPolarity(
            FactNormalizationService.NormalizedFact fact, String polarity) {
        return new FactNormalizationService.NormalizedFact(fact.normalizedQuestion(),
            fact.normalizedStatement(), polarity, fact.numericValues(), fact.numericRanges(),
            fact.temporalValues(), fact.enumValues(), fact.processSteps(), fact.scope(),
            fact.evidenceChunkIds(), fact.originalQuestion(), fact.originalStatement());
    }

    @Test
    void reportsUnknownWhenScopeCannotBeCompared() {
        BotKnowledgeSemanticUnit a = new BotKnowledgeSemanticUnit();
        a.setStatement("可以办理");
        BotKnowledgeSemanticUnit b = new BotKnowledgeSemanticUnit();
        b.setStatement("不可以办理");
        FactComparisonService.ComparisonResult result = comparison.compare(normalization.normalize(a), normalization.normalize(b));
        assertEquals(FactComparisonService.Relation.UNKNOWN, result.relation());
        assertEquals(FactComparisonService.Severity.BLOCKING, result.severity());
    }

    private BotKnowledgeSemanticUnit unit(String statement) {
        BotKnowledgeSemanticUnit unit = new BotKnowledgeSemanticUnit();
        unit.setQuestion("办理方式");
        unit.setStatement(statement);
        unit.setMetadataJson("{\"product\":\"合同\"}");
        return unit;
    }
}
