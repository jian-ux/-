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
        BotKnowledgeSemanticUnit a = unit("7天内提交");
        BotKnowledgeSemanticUnit b = unit("168小时内提交");
        FactComparisonService.ComparisonResult result = comparison.compare(normalization.normalize(a), normalization.normalize(b));
        assertEquals(FactComparisonService.Relation.NOT_CONFLICT, result.relation());
        assertEquals(FactComparisonService.Severity.INFO, result.severity());
    }

    @Test
    void reportsUnknownWhenScopeCannotBeCompared() {
        BotKnowledgeSemanticUnit a = new BotKnowledgeSemanticUnit();
        a.setStatement("可以办理");
        BotKnowledgeSemanticUnit b = new BotKnowledgeSemanticUnit();
        b.setStatement("不可以办理");
        FactComparisonService.ComparisonResult result = comparison.compare(normalization.normalize(a), normalization.normalize(b));
        assertEquals(FactComparisonService.Relation.UNKNOWN, result.relation());
    }

    private BotKnowledgeSemanticUnit unit(String statement) {
        BotKnowledgeSemanticUnit unit = new BotKnowledgeSemanticUnit();
        unit.setQuestion("办理方式");
        unit.setStatement(statement);
        unit.setMetadataJson("{\"product\":\"合同\"}");
        return unit;
    }
}
