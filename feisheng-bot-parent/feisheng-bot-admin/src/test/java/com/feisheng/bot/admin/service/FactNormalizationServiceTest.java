package com.feisheng.bot.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.knowledge.entity.BotKnowledgeSemanticUnit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class FactNormalizationServiceTest {
    private final FactNormalizationService service = new FactNormalizationService(new ObjectMapper());

    @Test
    void normalizesTextPolarityAndEquivalentDurations() {
        BotKnowledgeSemanticUnit unit = new BotKnowledgeSemanticUnit();
        unit.setQuestion("  申请期限？ ");
        unit.setStatement("必须在 7 天内提交，金额 1 万元，支持手机端。");
        FactNormalizationService.NormalizedFact fact = service.normalize(unit);
        assertEquals("申请期限", fact.normalizedQuestion());
        assertEquals("REQUIRED", fact.polarity());
        assertTrue(fact.numericValues().values().contains(new BigDecimal("10000")));
        assertTrue(fact.numericValues().values().stream().anyMatch(v -> v.compareTo(new BigDecimal("7")) == 0));
    }

    @Test
    void parsesScopeAndEvidence() {
        BotKnowledgeSemanticUnit unit = new BotKnowledgeSemanticUnit();
        unit.setStatement("可以办理");
        unit.setMetadataJson("{\"product\":\"合同\",\"channel\":\"手机端\"}");
        unit.setEvidenceChunkIdsJson("[3,5]");
        FactNormalizationService.NormalizedFact fact = service.normalize(unit);
        assertEquals(FactNormalizationService.ScopeRelation.KNOWN, fact.scope().relation());
        assertEquals("手机端", fact.scope().fields().get("channel"));
        assertEquals(java.util.List.of(3L, 5L), fact.evidenceChunkIds());
    }

    @Test
    void normalizesWidthNumeralsUnitsDatesAndDurations() {
        BotKnowledgeSemanticUnit left = new BotKnowledgeSemanticUnit();
        left.setQuestion("申请期限？");
        left.setStatement("7天内提交，金额1万元，比例20％，开始于2026-08-30T00:00:00+08:00");
        BotKnowledgeSemanticUnit right = new BotKnowledgeSemanticUnit();
        right.setQuestion("申请期限？");
        right.setStatement("168 小时内提交，金额 10000 元，比例 0.2，开始于2026年8月30日");
        FactNormalizationService.NormalizedFact a = service.normalize(left);
        FactNormalizationService.NormalizedFact b = service.normalize(right);
        assertEquals(a.normalizedQuestion(), b.normalizedQuestion());
        assertTrue(a.numericValues().values().stream().anyMatch(v -> v.compareTo(new BigDecimal("7")) == 0));
        assertTrue(a.numericValues().values().stream().anyMatch(v -> v.compareTo(new BigDecimal("10000")) == 0));
        assertTrue(a.numericValues().values().stream().anyMatch(v -> v.compareTo(new BigDecimal("0.2")) == 0));
        assertEquals(java.util.List.copyOf(a.temporalValues().values()), java.util.List.copyOf(b.temporalValues().values()));
    }

    @Test
    void parsesChineseCompoundNumeralsAndRanges() {
        BotKnowledgeSemanticUnit unit = new BotKnowledgeSemanticUnit();
        unit.setStatement("金额不超过十二万元，数量大于三件");
        FactNormalizationService.NormalizedFact fact = service.normalize(unit);
        assertTrue(fact.numericValues().values().stream().anyMatch(v -> v.compareTo(new BigDecimal("120000")) == 0));
        assertFalse(fact.numericRanges().isEmpty());
        assertTrue(fact.numericRanges().values().stream().anyMatch(r -> r.upper() != null && r.upper().compareTo(new BigDecimal("120000")) == 0 && r.upperInclusive()));
    }

    @Test
    void missingScopeIsUnknown() {
        BotKnowledgeSemanticUnit unit = new BotKnowledgeSemanticUnit();
        unit.setStatement("可以办理");
        assertEquals(FactNormalizationService.ScopeRelation.UNKNOWN, service.normalize(unit).scope().relation());
    }
}
