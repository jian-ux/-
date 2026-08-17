package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.admin.entity.BotKnowledgeChunk;
import com.feisheng.bot.admin.entity.BotKnowledgeDocument;
import com.feisheng.bot.admin.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeDocumentMapper;
import com.feisheng.bot.common.util.StructuredQaUtil;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Read-only checks for risky or contradictory customer-facing Q&A facts. */
@Service
public class KnowledgeQualityAuditService {
    private static final int COMPLETED_DOCUMENT_STATUS = 2;
    private static final int MAX_SEMANTIC_CONFLICTS = 100;
    private static final Pattern CLAUSE_SEPARATOR = Pattern.compile("[。！？；;，,\\r\\n]+");
    private static final Pattern NUMBER = Pattern.compile("[0-9一二三四五六七八九十百千万两]+");
    private static final List<String> NEGATIVE_MARKERS = List.of(
        "不支持", "不可以", "不能", "无法", "没有", "未提供", "不可", "禁止",
        "不允许", "不再", "不会");
    private static final List<String> POSITIVE_MARKERS = List.of(
        "支持", "可以", "能够", "可通过", "允许", "提供", "会", "能", "有");
    private static final List<String> NON_NEGATIVE_PHRASES = List.of(
        "不可抵赖", "不可篡改", "不可否认");
    private static final List<String> UNSIGNED_QUESTION_MARKERS = List.of(
        "未签署", "尚未签署", "还未签署", "没有签署");
    private static final List<String> SIGNED_QUESTION_MARKERS = List.of(
        "已签署", "签署完成", "完成签署", "签完", "签署后");
    private static final List<String> INTERNAL_NOTE_MARKERS = List.of(
        "（衡量", "(衡量", "特殊客户特殊处理", "比较强势", "技术方清除",
        "确认是否是", "客服这边反馈", "内部口径", "仅供内部");
    private static final List<String> ABSOLUTE_MARKERS = List.of(
        "百分之百", "100%", "永久", "绝对", "必然", "始终", "完全保证", "确保结果");
    private static final List<String> PRICE_MARKERS = List.of(
        "价格", "多少钱", "收费", "费用", "报价", "单价", "套餐", "退款", "折扣", "优惠");
    private static final List<String> LEGAL_MARKERS = List.of(
        "法律效力", "合法", "法院", "诉讼", "仲裁", "赔偿", "违约", "司法", "证据链", "合规");
    private static final List<String> VALIDITY_MARKERS = List.of(
        "有效期", "到期", "延期", "延长", "续费", "续套餐", "清零", "失效", "工作日");

    private static final Map<String, List<String>> CONCEPT_MARKERS = concepts();
    private static final Set<String> BROAD_CONCEPTS = Set.of("签署", "认证", "价格");

    private final BotKnowledgeChunkMapper chunkMapper;
    private final BotKnowledgeDocumentMapper documentMapper;

    public KnowledgeQualityAuditService(BotKnowledgeChunkMapper chunkMapper,
                                        BotKnowledgeDocumentMapper documentMapper) {
        this.chunkMapper = chunkMapper;
        this.documentMapper = documentMapper;
    }

    public AuditReport audit() {
        List<BotKnowledgeDocument> documents = documentMapper.selectList(
            new LambdaQueryWrapper<BotKnowledgeDocument>()
                .eq(BotKnowledgeDocument::getStatus, COMPLETED_DOCUMENT_STATUS)
                .eq(BotKnowledgeDocument::getPublishStatus,
                    KnowledgeDocumentReleaseService.PUBLISHED));
        List<BotKnowledgeChunk> chunks = chunkMapper.selectList(
            new LambdaQueryWrapper<BotKnowledgeChunk>()
                .eq(BotKnowledgeChunk::getStatus, "APPROVED")
                .eq(BotKnowledgeChunk::getContentType, "QA"));
        return audit(chunks, documents, new Date());
    }

    AuditReport audit(List<BotKnowledgeChunk> chunks,
                      List<BotKnowledgeDocument> documents,
                      Date now) {
        Map<Long, BotKnowledgeDocument> activeDocuments = new LinkedHashMap<>();
        for (BotKnowledgeDocument document : safeList(documents)) {
            if (document == null || document.getId() == null || !active(document, now)) continue;
            activeDocuments.put(document.getId(), document);
        }

        List<Entry> entries = safeList(chunks).stream()
            .filter(chunk -> activeChunk(chunk, activeDocuments))
            .map(chunk -> new Entry(chunk, activeDocuments.get(chunk.getDocumentId())))
            .sorted(Comparator.comparingLong(entry -> entry.chunk().getId()))
            .toList();

        List<Finding> findings = new ArrayList<>();
        addExactQuestionConflicts(entries, findings);
        addEntryRisks(entries, findings);
        addSemanticConflictCandidates(entries, findings);
        findings.sort(Comparator
            .comparingInt((Finding finding) -> finding.severity().order())
            .thenComparing(Finding::code)
            .thenComparing(finding -> finding.evidence().isEmpty()
                ? Long.MAX_VALUE : finding.evidence().get(0).chunkId()));

        Map<String, Long> byCode = new LinkedHashMap<>();
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        for (Finding finding : findings) {
            byCode.merge(finding.code(), 1L, Long::sum);
            bySeverity.merge(finding.severity().name(), 1L, Long::sum);
        }
        return new AuditReport(Instant.now().toString(), activeDocuments.size(), entries.size(),
            findings.size(), Collections.unmodifiableMap(bySeverity),
            Collections.unmodifiableMap(byCode), List.copyOf(findings));
    }

    private void addExactQuestionConflicts(List<Entry> entries, List<Finding> findings) {
        Map<String, List<Entry>> byQuestion = new LinkedHashMap<>();
        for (Entry entry : entries) {
            String key = StructuredQaUtil.canonicalKey(entry.question());
            if (!key.isBlank()) byQuestion.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
        }
        for (List<Entry> group : byQuestion.values()) {
            Set<String> answers = new HashSet<>();
            group.forEach(entry -> answers.add(StructuredQaUtil.answerFingerprint(entry.answer())));
            if (group.size() < 2 || answers.size() < 2) continue;
            findings.add(finding("EXACT_QUESTION_CONFLICT", Severity.BLOCKER, "CONSISTENCY",
                "同一标准问题存在不同答案，必须先统一口径，再允许进入直答或生成链路。", group));
        }
    }

    private void addEntryRisks(List<Entry> entries, List<Finding> findings) {
        for (Entry entry : entries) {
            String text = compact(entry.question() + " " + entry.answer());
            boolean price = containsAny(text, PRICE_MARKERS);
            boolean validity = containsAny(text, VALIDITY_MARKERS) && NUMBER.matcher(text).find();
            boolean legal = containsAny(text, LEGAL_MARKERS);
            boolean risky = price || validity || legal;

            if (containsAny(text, INTERNAL_NOTE_MARKERS)) {
                findings.add(finding("INTERNAL_NOTE_EXPOSURE", Severity.BLOCKER, "CONTENT_HYGIENE",
                    "标准答案含内部判断、特殊处理或技术备注，不应直接面向客户。", List.of(entry)));
            }
            if (looksComposite(entry.answer())) {
                findings.add(finding("COMPOSITE_QA_ANSWER", Severity.HIGH, "CONTENT_HYGIENE",
                    "一个标准答案中疑似混入了后续问题，可能导致检索后拼接无关事实。", List.of(entry)));
            }
            if (price) {
                String expiry = entry.document().getEffectiveTo() == null
                    ? "，且来源未设置失效时间" : "";
                findings.add(finding("PRICE_REVIEW_REQUIRED", Severity.HIGH, "PRICE",
                    "价格、套餐或优惠内容具有时效性，需要业务复核" + expiry + "。", List.of(entry)));
            }
            if (validity) {
                findings.add(finding("VALIDITY_REVIEW_REQUIRED", Severity.HIGH, "VALIDITY",
                    "答案包含期限、延期或失效条件，需要核对当前政策及适用范围。", List.of(entry)));
            }
            if (legal) {
                findings.add(finding("LEGAL_REVIEW_REQUIRED", Severity.HIGH, "LEGAL",
                    "答案包含法律、争议或合规判断，需要业务或法务复核。", List.of(entry)));
            }
            if (containsAny(text, ABSOLUTE_MARKERS)) {
                findings.add(finding("ABSOLUTE_CLAIM_REVIEW", Severity.REVIEW, "MARKETING",
                    "答案包含绝对化或保证性表述，需要确认是否有充分依据。", List.of(entry)));
            }
            if (risky && Integer.valueOf(1).equals(entry.chunk().getDirectAnswerEnabled())) {
                findings.add(finding("RISKY_DIRECT_ANSWER", Severity.BLOCKER, "DIRECT_ANSWER",
                    "高风险或时效性答案已开启直答，应先完成专项复核。", List.of(entry)));
            }
        }
    }

    private void addSemanticConflictCandidates(List<Entry> entries, List<Finding> findings) {
        List<Claim> claims = new ArrayList<>();
        for (Entry entry : entries) claims.addAll(claims(entry));
        Set<String> reportedPairs = new HashSet<>();
        int reported = 0;
        for (int i = 0; i < claims.size() && reported < MAX_SEMANTIC_CONFLICTS; i++) {
            Claim left = claims.get(i);
            for (int j = i + 1; j < claims.size() && reported < MAX_SEMANTIC_CONFLICTS; j++) {
                Claim right = claims.get(j);
                if (left.entry().chunk().getId().equals(right.entry().chunk().getId())
                        || left.polarity() == right.polarity()) continue;
                Set<String> shared = new LinkedHashSet<>(left.concepts());
                shared.retainAll(right.concepts());
                if (shared.isEmpty()) continue;
                if (mutuallyExclusiveSigningStates(
                        left.entry().question(), right.entry().question())) continue;
                double questionSimilarity = bigramSimilarity(
                    left.entry().question(), right.entry().question());
                if (shared.size() < 2) {
                    String sharedConcept = shared.iterator().next();
                    double minimumSimilarity = BROAD_CONCEPTS.contains(sharedConcept) ? 0.55 : 0.30;
                    if (questionSimilarity < minimumSimilarity) continue;
                }
                long first = Math.min(left.entry().chunk().getId(), right.entry().chunk().getId());
                long second = Math.max(left.entry().chunk().getId(), right.entry().chunk().getId());
                if (!reportedPairs.add(first + ":" + second)) continue;
                findings.add(finding("SEMANTIC_CONFLICT_CANDIDATE", Severity.HIGH, "CONSISTENCY",
                    "不同问题的答案在“" + String.join("、", shared)
                        + "”上出现相反口径，请人工确认。冲突片段：‘"
                        + truncate(left.clause(), 100) + "’ / ‘"
                        + truncate(right.clause(), 100) + "’。",
                    List.of(left.entry(), right.entry())));
                reported++;
            }
        }
    }

    private List<Claim> claims(Entry entry) {
        List<Claim> result = new ArrayList<>();
        for (String raw : CLAUSE_SEPARATOR.split(entry.answer())) {
            String clause = compact(raw);
            if (clause.length() < 3) continue;
            Polarity polarity = polarity(clause);
            if (polarity == Polarity.NEUTRAL) continue;
            Set<String> concepts = conceptsIn(clause);
            if (!concepts.isEmpty()) result.add(new Claim(entry, raw.trim(), polarity, concepts));
        }
        return result;
    }

    private Polarity polarity(String clause) {
        String negativeCandidate = clause;
        for (String phrase : NON_NEGATIVE_PHRASES) {
            negativeCandidate = negativeCandidate.replace(phrase, "");
        }
        if (containsAny(negativeCandidate, NEGATIVE_MARKERS)) return Polarity.NEGATIVE;
        if (containsAny(clause, POSITIVE_MARKERS)) return Polarity.POSITIVE;
        return Polarity.NEUTRAL;
    }

    private boolean mutuallyExclusiveSigningStates(String leftQuestion, String rightQuestion) {
        boolean leftUnsigned = containsAny(compact(leftQuestion), UNSIGNED_QUESTION_MARKERS);
        boolean rightUnsigned = containsAny(compact(rightQuestion), UNSIGNED_QUESTION_MARKERS);
        boolean leftSigned = containsAny(compact(leftQuestion), SIGNED_QUESTION_MARKERS);
        boolean rightSigned = containsAny(compact(rightQuestion), SIGNED_QUESTION_MARKERS);
        return (leftUnsigned && rightSigned) || (rightUnsigned && leftSigned);
    }

    private Set<String> conceptsIn(String clause) {
        Set<String> result = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> concept : CONCEPT_MARKERS.entrySet()) {
            if (containsAny(clause, concept.getValue())) result.add(concept.getKey());
        }
        return result;
    }

    private Finding finding(String code, Severity severity, String category,
                            String message, List<Entry> entries) {
        List<Evidence> evidence = entries.stream().map(entry -> new Evidence(
            entry.chunk().getId(), entry.chunk().getDocumentId(),
            entry.document().getTitle(), entry.question(), truncate(entry.answer(), 800),
            entry.chunk().getDirectAnswerEnabled())).toList();
        return new Finding(code, severity, category, message, evidence);
    }

    private boolean active(BotKnowledgeDocument document, Date now) {
        if (!Integer.valueOf(COMPLETED_DOCUMENT_STATUS).equals(document.getStatus())
                || !KnowledgeDocumentReleaseService.PUBLISHED.equals(document.getPublishStatus())) {
            return false;
        }
        return (document.getEffectiveFrom() == null || !document.getEffectiveFrom().after(now))
            && (document.getEffectiveTo() == null || !document.getEffectiveTo().before(now));
    }

    private boolean activeChunk(BotKnowledgeChunk chunk,
                                Map<Long, BotKnowledgeDocument> documents) {
        return chunk != null && chunk.getId() != null && chunk.getDocumentId() != null
            && documents.containsKey(chunk.getDocumentId())
            && "APPROVED".equals(chunk.getStatus())
            && "QA".equals(chunk.getContentType())
            && hasText(chunk.getQaQuestion()) && hasText(chunk.getQaAnswer())
            && !Integer.valueOf(1).equals(chunk.getDeleted());
    }

    private boolean looksComposite(String answer) {
        if (!hasText(answer)) return false;
        int questionMark = Math.max(answer.indexOf('？'), answer.indexOf('?'));
        return questionMark >= 0 && answer.length() - questionMark > 5;
    }

    private double bigramSimilarity(String left, String right) {
        Set<String> first = bigrams(StructuredQaUtil.normalizeQuestion(left));
        Set<String> second = bigrams(StructuredQaUtil.normalizeQuestion(right));
        if (first.isEmpty() || second.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(first);
        intersection.retainAll(second);
        Set<String> union = new HashSet<>(first);
        union.addAll(second);
        return (double) intersection.size() / union.size();
    }

    private Set<String> bigrams(String value) {
        Set<String> result = new HashSet<>();
        if (value == null) return result;
        for (int i = 0; i + 1 < value.length(); i++) result.add(value.substring(i, i + 2));
        return result;
    }

    private static Map<String, List<String>> concepts() {
        Map<String, List<String>> values = new LinkedHashMap<>();
        values.put("手机APP", List.of("手机app", "独立app", "app"));
        values.put("手机端", List.of("手机端", "移动端", "手机", "移动设备"));
        values.put("套餐延期", List.of("延期", "延长有效期", "延长", "续期"));
        values.put("套餐续费", List.of("续费", "续套餐", "续订"));
        values.put("有效期", List.of("有效期", "有效期限", "期限", "到期"));
        values.put("退款", List.of("退款", "退费"));
        values.put("合同存储", List.of("合同存储", "保存合同", "存档", "清除合同", "删除合同"));
        values.put("账号注销", List.of("账号注销", "注销账号", "注销"));
        values.put("套餐共享", List.of("套餐共享", "共用套餐", "套餐共用"));
        values.put("价格", List.of("价格", "收费", "费用", "报价", "元"));
        values.put("发票", List.of("发票", "开票"));
        values.put("认证", List.of("认证", "人脸", "银行卡", "对公账户"));
        values.put("签署", List.of("签署", "签合同", "电子合同"));
        values.put("审批", List.of("审批", "审批流", "自动盖章"));
        values.put("短信验证码", List.of("验证码", "短信", "短信码"));
        return Collections.unmodifiableMap(values);
    }

    private boolean containsAny(String value, List<String> markers) {
        if (value == null || value.isBlank()) return false;
        String normalized = value.toLowerCase(Locale.ROOT);
        return markers.stream().map(marker -> marker.toLowerCase(Locale.ROOT))
            .anyMatch(normalized::contains);
    }

    private String compact(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private String truncate(String value, int maxChars) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= maxChars ? trimmed : trimmed.substring(0, maxChars) + "...";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record Entry(BotKnowledgeChunk chunk, BotKnowledgeDocument document) {
        String question() { return chunk.getQaQuestion().trim(); }
        String answer() { return chunk.getQaAnswer().trim(); }
    }

    private record Claim(Entry entry, String clause, Polarity polarity, Set<String> concepts) {}
    private enum Polarity { POSITIVE, NEGATIVE, NEUTRAL }

    public enum Severity {
        BLOCKER(0), HIGH(1), REVIEW(2);
        private final int order;
        Severity(int order) { this.order = order; }
        int order() { return order; }
    }

    public record Evidence(Long chunkId, Long documentId, String documentTitle,
                           String question, String answer, Integer directAnswerEnabled) {}
    public record Finding(String code, Severity severity, String category,
                          String message, List<Evidence> evidence) {}
    public record AuditReport(String generatedAt, int documentCount, int qaCount,
                              int findingCount, Map<String, Long> bySeverity,
                              Map<String, Long> byCode, List<Finding> findings) {}
}
