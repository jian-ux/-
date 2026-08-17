package com.feisheng.bot.core.service.impl;

import com.feisheng.bot.common.util.RedisUtil;
import com.feisheng.bot.common.util.StructuredQaUtil;
import com.feisheng.bot.core.client.KnowledgeClient;
import com.feisheng.bot.core.client.StructuredUnitRetrievalClient;
import com.feisheng.bot.core.client.StructuredUnitRetrievalClient.StructuredUnitHit;
import com.feisheng.bot.core.dto.QueryVariant;
import com.feisheng.bot.core.service.EmbeddingService;
import com.feisheng.bot.core.service.BusinessSafetyBoundaryService;
import com.feisheng.bot.core.service.QueryExpansionService;
import com.feisheng.bot.core.service.RerankService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

@Service
public class RagRetrievalService {
    private static final Logger log = LoggerFactory.getLogger(RagRetrievalService.class);
    private static final String CACHE_PREFIX_EMBEDDING = "rag:emb:";
    private static final long CACHE_TTL_SECONDS = 600;
    private static final List<String> IMAGE_INTENT_TERMS = List.of(
        "图片", "产品图", "流程图", "截图", "照片", "海报", "二维码",
        "发张图", "发一张图", "发图", "看图"
    );
    private static final List<String> CONTRACT_TOPIC_TERMS = List.of(
        "电子合同", "合同", "签合同", "签署", "签名", "签章", "盖章", "发起合同");
    private static final List<String> MEMBERSHIP_TOPIC_TERMS = List.of(
        "会员", "会员类型", "会员通道", "会员权益", "套餐", "续费");
    private static final List<String> CONTRACT_LAUNCH_METHOD_TERMS = List.of(
        "发起合同", "合同发起", "发起方式", "上传文件发起", "模板发起");
    private static final List<String> SERVICE_MODE_TERMS = List.of(
        "服务模式", "SaaS", "Saas", "SAAS", "OpenAPI", "OpenApi", "Openapi", "定制化开发");
    private static final double CROSS_TOPIC_SCORE_FACTOR = 0.45;
    private static final double VECTOR_RRF_WEIGHT = 1.0;
    private static final double BM25_RRF_WEIGHT = 1.0;
    private static final double LEXICAL_RRF_WEIGHT = 0.8;
    private static final double PHONETIC_RRF_WEIGHT = 0.6;
    private static final double KEYWORD_RRF_WEIGHT = 1.2;
    private static final double EXPANSION_RRF_WEIGHT = 0.65;
    private static final int MAX_STRUCTURED_EVIDENCE_CHUNKS = 50;

    private final FaqMatchServiceImpl faqMatchService;
    private final EmbeddingService embeddingService;
    private final KnowledgeClient knowledgeClient;
    private final RerankService rerankService;
    private final QueryExpansionService queryExpansionService;
    private final StructuredUnitRetrievalClient structuredUnitRetrievalClient;
    private final RedisUtil redisUtil;
    private final ObjectMapper objectMapper;
    private final BusinessSafetyBoundaryService businessSafetyBoundaryService;

    @Value("${rag.retrieval.top-k:3}")
    private int topK;

    @Value("${rag.retrieval.candidate-k:10}")
    private int candidateK;

    @Value("${rag.retrieval.direct-threshold:0.82}")
    private double directThreshold;

    @Value("${rag.retrieval.context-threshold:0.50}")
    private double contextThreshold;

    @Value("${rag.retrieval.multimodal-context-max-chars:4000}")
    private int multimodalContextMaxChars = 4000;

    @Value("${rag.retrieval.phonetic-threshold:0.80}")
    private double phoneticThreshold = 0.80;

    @Value("${rag.retrieval.lexical-threshold:0.72}")
    private double lexicalThreshold = 0.72;

    @Value("${rag.retrieval.bm25-enabled:true}")
    private boolean bm25Enabled = true;

    @Value("${rag.retrieval.bm25-min-score:0.0}")
    private double bm25MinScore;

    @Value("${rag.retrieval.bm25-fallback-min-score:8.0}")
    private double bm25FallbackMinScore = 8.0;

    @Value("${rag.retrieval.bm25-fallback-min-gap-ratio:1.10}")
    private double bm25FallbackMinGapRatio = 1.10;

    @Value("${rag.retrieval.bm25-fallback-min-question-similarity:0.60}")
    private double bm25FallbackMinQuestionSimilarity = 0.60;

    @Value("${rag.retrieval.bm25-fallback-confidence:0.65}")
    private double bm25FallbackConfidence = 0.65;

    @Value("${rag.retrieval.rank-fusion-k:60}")
    private int rankFusionK = 60;

    @Value("${rag.retrieval.neighbor-radius:1}")
    private int neighborRadius = 1;

    @Value("${rag.retrieval.max-neighbor-chunks:4}")
    private int maxNeighborChunks = 4;

    @Value("${rag.structured-qa.enabled:true}")
    private boolean structuredQaEnabled = true;

    @Value("${rag.structured-qa.exact-min-score:0.82}")
    private double structuredQaExactMinScore = 0.82;

    @Value("${rag.structured-qa.rerank-min-score:0.90}")
    private double structuredQaRerankMinScore = 0.90;

    @Value("${rag.structured-qa.rerank-min-gap:0.08}")
    private double structuredQaRerankMinGap = 0.08;

    @Value("${rag.rerank.confidence.high-min-score:0.90}")
    private double rerankHighMinScore = 0.90;

    @Value("${rag.rerank.confidence.high-min-gap:0.08}")
    private double rerankHighMinGap = 0.08;

    @Value("${rag.rerank.confidence.medium-min-score:0.65}")
    private double rerankMediumMinScore = 0.65;

    @Value("${rag.rerank.confidence.medium-min-gap:0.03}")
    private double rerankMediumMinGap = 0.03;

    @Value("${rag.structured-unit-index.enabled:false}")
    private boolean structuredUnitIndexEnabled;

    @Value("${rag.structured-unit-index.shadow-only:true}")
    private boolean structuredUnitShadowOnly = true;

    @Value("${rag.structured-unit-index.top-k:5}")
    private int structuredUnitTopK = 5;

    @Value("${rag.structured-unit-index.weight:0.65}")
    private double structuredUnitWeight = 0.65;

    public RagRetrievalService(FaqMatchServiceImpl faqMatchService,
                               EmbeddingService embeddingService,
                               KnowledgeClient knowledgeClient,
                               RerankService rerankService,
                               QueryExpansionService queryExpansionService,
                               StructuredUnitRetrievalClient structuredUnitRetrievalClient,
                               RedisUtil redisUtil,
                               ObjectMapper objectMapper,
                               BusinessSafetyBoundaryService businessSafetyBoundaryService) {
        this.faqMatchService = faqMatchService;
        this.embeddingService = embeddingService;
        this.knowledgeClient = knowledgeClient;
        this.rerankService = rerankService;
        this.queryExpansionService = queryExpansionService;
        this.structuredUnitRetrievalClient = structuredUnitRetrievalClient;
        this.redisUtil = redisUtil;
        this.objectMapper = objectMapper;
        this.businessSafetyBoundaryService = businessSafetyBoundaryService;
    }

    public RetrievalResult retrieve(String query) {
        return retrieve(query, true);
    }

    public RetrievalResult retrieve(String query, boolean trackHit) {
        return retrieve(query, (String) null, trackHit);
    }

    /** Retrieves while applying trusted payload filters in every recall channel. */
    public RetrievalResult retrieve(String query, Map<String, Object> filters, boolean trackHit) {
        return retrieve(query, null, null, filters, trackHit);
    }

    /**
     * Retrieves with optional OCR/ASR-derived context folded into the semantic query.
     * Keyword matching remains scoped to the user's explicit text to avoid false FAQ hits.
     */
    public RetrievalResult retrieve(String query, String modalityContext, boolean trackHit) {
        return retrieve(query, null, modalityContext, trackHit);
    }

    /**
     * Retrieves with conversation history and optional OCR/ASR context used only by
     * semantic search. Keyword matching remains scoped to the current user message.
     */
    public RetrievalResult retrieve(String query, String conversationContext,
                                    String modalityContext, boolean trackHit) {
        return retrieve(query, conversationContext, modalityContext,
            Collections.emptyMap(), trackHit);
    }

    public RetrievalResult retrieve(String query, String conversationContext,
                                    String modalityContext, Map<String, Object> filters,
                                    boolean trackHit) {
        return retrieve(query, conversationContext, modalityContext, filters,
            Collections.emptyList(), trackHit);
    }

    /**
     * Retrieves with deterministic supplemental variants while keeping {@code query}
     * as the only source for exact matching, topic alignment, and final reranking.
     */
    public RetrievalResult retrieve(String query, String conversationContext,
                                    String modalityContext, Map<String, Object> filters,
                                    List<QueryVariant> supplementalVariants,
                                    boolean trackHit) {
        RetrievalTimingCollector timings = new RetrievalTimingCollector();
        if (businessSafetyBoundaryService.checkRetrievalAuthorization(query).isBlocked()) {
            return new RetrievalResult(false, false, null, null, 0,
                "authorization_blocked", false,
                Collections.emptyList(), Collections.emptyList(),
                defaultRerankDiagnostics(), timings.snapshot(0, 0));
        }
        Map<String, Object> searchFilters = trustedFilters(filters);
        long keywordStarted = System.nanoTime();
        Map<String, Object> keywordMatch = keywordMatch(query, trackHit, searchFilters);
        timings.keywordNanos += elapsedNanos(keywordStarted);
        boolean hasKeywordMatch = keywordMatch != null && keywordMatch.containsKey("answer");
        double keywordScore = hasKeywordMatch ? number(keywordMatch.get("score")) : 0;
        boolean exactKeywordMatch = hasKeywordMatch
            && Boolean.TRUE.equals(keywordMatch.get("exactMatch"));
        boolean directAnswerEnabled = hasKeywordMatch
            && Boolean.TRUE.equals(keywordMatch.get("directAnswerEnabled"));

        if (exactKeywordMatch && directAnswerEnabled && keywordScore >= directThreshold
                && isBlank(conversationContext) && isBlank(modalityContext)) {
            RetrievalResult direct = directKeywordResult(keywordMatch, keywordScore);
            return direct.withStageLatencies(
                timings.snapshot(0, direct.candidates().size()));
        }

        List<QueryVariant> queryVariants = queryVariants(
            query, exactKeywordMatch, supplementalVariants);
        List<Map<String, Object>> candidates = new ArrayList<>();
        int recallLimit = Math.max(topK, candidateK);
        boolean semanticAvailable = false;
        List<Double> originalEmbedding = Collections.emptyList();
        if (embeddingService.isAvailable()) {
            for (QueryVariant variant : queryVariants) {
                String semanticQuery = semanticQuery(
                    variant.query(), conversationContext, modalityContext);
                long embeddingStarted = System.nanoTime();
                EmbeddingLookup embeddingLookup = getEmbedding(semanticQuery);
                timings.embeddingNanos += elapsedNanos(embeddingStarted);
                if (embeddingLookup.cacheHit()) timings.embeddingCacheHits++;
                else timings.embeddingCacheMisses++;
                List<Double> embedding = embeddingLookup.vector();
                if (embedding.isEmpty()) continue;
                if (variant.original()) originalEmbedding = embedding;
                semanticAvailable = true;
                long vectorStarted = System.nanoTime();
                List<Map<String, Object>> matches = semanticMatch(
                    semanticQuery, embedding, recallLimit, searchFilters);
                timings.vectorSearchNanos += elapsedNanos(vectorStarted);
                if (variant.original()) {
                    mergeRankedMatches(candidates, semanticMatches(matches),
                        "semanticScore", null, "vectorRank");
                } else {
                    mergeExpansionMatches(candidates, matches, variant,
                        "similarity", "expandedVectorScore", "expanded_vector");
                }
            }
        }

        long structuredStarted = System.nanoTime();
        StructuredUnitRecall structuredUnitRecall = recallStructuredUnits(
            originalEmbedding, searchFilters);
        timings.vectorSearchNanos += elapsedNanos(structuredStarted);
        if (!structuredUnitRecall.evidenceCandidates().isEmpty()) {
            mergeRankedMatches(candidates, structuredUnitRecall.evidenceCandidates(),
                "structuredUnitScore", "structured_unit_evidence", "structuredUnitRank");
        }

        long sparseStarted = System.nanoTime();
        if (bm25Enabled) {
            mergeRankedMatches(candidates,
                bm25Match(query, recallLimit, searchFilters),
                "bm25Score", "bm25", "bm25Rank");
            for (QueryVariant variant : expandedVariants(queryVariants)) {
                mergeExpansionMatches(candidates, bm25Match(
                    variant.query(), recallLimit, searchFilters), variant,
                    null, null, "expanded_bm25");
            }
        }

        // Exact and near-exact text must always participate in reranking. A merely
        // related vector hit must not suppress the literal question from the document.
        for (String lexicalQuery : lexicalQueries(query)) {
            mergeRankedMatches(candidates, lexicalMatch(
                lexicalQuery, recallLimit, searchFilters),
                "lexicalScore", "lexical", "lexicalRank");
        }
        for (QueryVariant variant : expandedVariants(queryVariants)) {
            mergeExpansionMatches(candidates, lexicalMatch(
                variant.query(), recallLimit, searchFilters), variant,
                "lexicalScore", "lexicalScore", "expanded_lexical");
        }
        boolean hasConfidentTextMatch = candidates.stream()
            .anyMatch(candidate -> number(candidate.get("similarity")) >= contextThreshold);
        if (!hasConfidentTextMatch) {
            mergeRankedMatches(candidates, phoneticMatch(
                query, recallLimit, searchFilters),
                "phoneticScore", "phonetic", "phoneticRank");
        }
        timings.sparseSearchNanos += elapsedNanos(sparseStarted);

        if (hasKeywordMatch) {
            Map<String, Object> keywordCandidate = findFaq(candidates, keywordMatch.get("itemId"));
            if (keywordCandidate == null) {
                keywordCandidate = new HashMap<>(keywordMatch);
                keywordCandidate.put("type", "item");
                keywordCandidate.put("sourceType", "faq");
                keywordCandidate.put("sourceId", keywordMatch.get("itemId"));
                keywordCandidate.put("title", keywordMatch.get("question"));
                keywordCandidate.put("content", keywordMatch.get("answer"));
                keywordCandidate.put("similarity", 0.0);
                keywordCandidate.put("keywordOnly", true);
                candidates.add(keywordCandidate);
            }
            keywordCandidate.put("keywordRank", 1);
            keywordCandidate.put("directAnswerEnabled", directAnswerEnabled);
            keywordCandidate.put("fullAnswer", keywordMatch.get("answer"));
        }

        for (Map<String, Object> candidate : candidates) {
            double vectorScore = number(candidate.get("semanticScore"));
            double expandedVectorScore = number(candidate.get("expandedVectorScore"));
            double structuredUnitScore = number(candidate.get("structuredUnitScore"));
            double lexicalScore = number(candidate.get("lexicalScore"));
            double phoneticScore = number(candidate.get("phoneticScore"));
            double retrievalScore = Math.max(
                Math.max(Math.max(vectorScore, expandedVectorScore), structuredUnitScore),
                Math.max(lexicalScore, phoneticScore));
            double candidateKeywordScore = sameFaq(candidate, keywordMatch) ? keywordScore : 0;
            boolean candidateExactMatch = candidateKeywordScore > 0 && exactKeywordMatch;
            String type = string(candidate.getOrDefault("type", "item"));
            double score;
            if (Boolean.TRUE.equals(candidate.get("keywordOnly"))) {
                score = candidateKeywordScore;
            } else if ("chunk".equals(type)) {
                score = retrievalScore;
            } else if (candidateKeywordScore > 0) {
                score = Math.max(candidateKeywordScore,
                    retrievalScore * 0.7 + candidateKeywordScore * 0.3);
            } else {
                score = retrievalScore;
            }
            candidate.put("vectorScore", round(vectorScore));
            candidate.put("expandedVectorScore", round(expandedVectorScore));
            candidate.put("structuredUnitScore", round(structuredUnitScore));
            candidate.put("lexicalScore", round(lexicalScore));
            candidate.put("phoneticScore", round(phoneticScore));
            candidate.put("keywordScore", round(candidateKeywordScore));
            candidate.put("exactMatch", candidateExactMatch);
            candidate.put("structuredQaExactMatch",
                Boolean.TRUE.equals(candidate.get("structuredQa"))
                    && StructuredQaUtil.normalizeQuestion(query).equals(
                        StructuredQaUtil.normalizeQuestion(structuredQuestion(candidate))));
            TopicAlignment alignment = topicAlignment(query, candidate);
            candidate.put("topicMismatch", alignment.mismatch());
            candidate.put("topicAlignment", alignment.label());
            candidate.put("combinedScore", round(score * alignment.factor()));
            double fusedScore = reciprocalRankScore(candidate) * alignment.factor();
            candidate.put("rrfScore", round6(fusedScore));
            candidate.put("rankScore", round6(fusedScore));
        }

        candidates.sort(this::compareCandidates);
        RerankService.RerankDiagnostics rerankDiagnostics =
            applyReranking(query, candidates);
        candidates.sort(this::compareCandidates);
        RerankConfidenceDecision rerankConfidence = assessRerankConfidence(candidates);
        List<Map<String, Object>> selected = rerankConfidence.tier() == RerankConfidenceTier.LOW
            ? Collections.emptyList()
            : selectAcceptedCandidates(candidates, query, rerankConfidence.applied());
        selected = isolateFocusedStructuredQa(query, selected);
        double topCombinedScore = selected.isEmpty()
            ? candidates.stream().mapToDouble(candidate -> number(candidate.get("combinedScore")))
                .max().orElse(0)
            : acceptedConfidence(selected.get(0));
        double confidence = rerankConfidence.applied()
            ? rerankConfidence.topScore() : topCombinedScore;

        boolean answerable = !selected.isEmpty();
        boolean contextFree = isBlank(conversationContext) && isBlank(modalityContext);
        boolean directConfidenceAllowed = !rerankConfidence.applied()
            || rerankConfidence.tier() == RerankConfidenceTier.HIGH;
        boolean directFaq = directConfidenceAllowed && answerable
            && (conversationContext == null || conversationContext.isBlank())
            && "item".equals(selected.get(0).get("type"))
            && Boolean.TRUE.equals(selected.get(0).get("exactMatch"))
            && Boolean.TRUE.equals(selected.get(0).get("directAnswerEnabled"))
            && topCombinedScore >= directThreshold;
        StructuredQaDirectDecision qaDirect = directConfidenceAllowed && contextFree
            ? structuredQaDirectDecision(query, candidates, selected)
            : StructuredQaDirectDecision.noMatch();
        StructuredTableAnswerResolver.Decision tableDirect = directConfidenceAllowed && contextFree
            ? structuredTableDirectDecision(query, selected)
            : null;
        boolean direct = directFaq || qaDirect.direct() || tableDirect != null;
        if (qaDirect.direct()) {
            selected.get(0).put("directMatchMode", qaDirect.matchMode());
        } else if (tableDirect != null) {
            selected.get(0).put("directMatchMode", tableDirect.mode());
        }
        List<Map<String, Object>> accepted = direct
            ? List.of(selected.get(0)) : expandAdjacentChunks(selected);
        List<Map<String, Object>> citations = buildCitations(accepted);
        String context = answerable ? buildContext(accepted, citations) : null;
        String directAnswer = directFaq ? firstNonBlank(
            string(selected.get(0).get("fullAnswer")), string(selected.get(0).get("answer")))
            : qaDirect.direct() ? qaDirect.answer()
            : tableDirect != null ? tableDirect.answer() : null;
        String decision = directFaq ? "direct"
            : qaDirect.direct() ? "structured_qa_direct"
            : tableDirect != null ? "structured_table_direct"
            : answerable ? "rag" : "no_answer";

        return new RetrievalResult(answerable, direct, directAnswer, context,
            round(confidence), decision, semanticAvailable,
            List.copyOf(citations), candidatesWithDiagnostics(
                candidates, structuredUnitRecall.diagnostics()),
            rerankDiagnostics.asMap(), timings.snapshot(
                rerankDiagnostics.latencyMs(), candidates.size()));
    }

    /**
     * Merges attached evidence (for example, a chat screenshot) with global RAG results.
     * Attached citations stay first and knowledge citations are renumbered consistently.
     */
    public RetrievalResult mergeWithProvidedContext(RetrievalResult retrieved,
                                                      String providedContext,
                                                      List<Map<String, Object>> providedCitations) {
        RetrievalResult base = retrieved == null
            ? new RetrievalResult(false, false, null, null, 0, "no_answer",
                false, Collections.emptyList(), Collections.emptyList())
            : retrieved;
        if (providedContext == null || providedContext.isBlank()) return base;

        List<Map<String, Object>> attached = providedCitations == null || providedCitations.isEmpty()
            ? citationsForProvidedContext(providedContext)
            : copyAndRenumberCitations(providedCitations, 0);
        List<Map<String, Object>> citations = new ArrayList<>(attached);
        citations.addAll(copyAndRenumberCitations(base.citations(), attached.size()));

        StringBuilder context = new StringBuilder(providedContext.trim());
        if (base.answerable() && base.context() != null && !base.context().isBlank()) {
            context.append("\n\n")
                .append(shiftCitationHeaders(base.context(), base.citations(), attached.size()));
        }
        String decision = base.answerable() ? "multimodal_rag" : "provided_context";
        double confidence = base.answerable() ? base.confidence() : 1.0;
        return new RetrievalResult(true, false, null, context.toString(), confidence,
            decision, base.semanticAvailable(), List.copyOf(citations), base.candidates(),
            base.rerankDiagnostics(), base.stageLatencies());
    }

    public List<Map<String, Object>> citationsForProvidedContext(String context) {
        if (context == null || context.isBlank()) return Collections.emptyList();
        Map<String, Object> citation = new LinkedHashMap<>();
        citation.put("ref", 1);
        citation.put("id", "provided:1");
        citation.put("sourceType", "provided_context");
        citation.put("sourceId", null);
        citation.put("title", "调用方提供的知识上下文");
        citation.put("score", 1.0);
        citation.put("snippet", truncate(context, 180));
        return List.of(citation);
    }

    private EmbeddingLookup getEmbedding(String query) {
        EmbeddingService.EmbeddingDescriptor descriptor = embeddingService.descriptor();
        String modelVersion = descriptor == null || descriptor.version() == null
            || descriptor.version().isBlank() ? "legacy" : descriptor.version();
        String key = CACHE_PREFIX_EMBEDDING + modelVersion + ":" + hash(query);
        try {
            Object cached = redisUtil.get(key);
            if (cached != null) {
                return new EmbeddingLookup(objectMapper.readValue(cached.toString(),
                    new TypeReference<List<Double>>() {}), true);
            }
        } catch (Exception ignored) {}

        List<Double> embedding = embeddingService.embed(query);
        if (!embedding.isEmpty()) {
            try {
                redisUtil.setex(key, objectMapper.writeValueAsString(embedding),
                    CACHE_TTL_SECONDS, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }
        return new EmbeddingLookup(embedding, false);
    }

    private String semanticQuery(String query, String conversationContext,
                                 String modalityContext) {
        String normalizedQuery = query == null ? "" : query.trim();
        int maxChars = Math.max(1, multimodalContextMaxChars);
        StringBuilder result = new StringBuilder(normalizedQuery);
        int remaining = maxChars;
        if (conversationContext != null && !conversationContext.isBlank()) {
            String context = truncate(conversationContext.trim(), remaining);
            result.append("\n【相关对话上下文】\n").append(context);
            remaining -= context.length();
        }
        if (remaining > 0 && modalityContext != null && !modalityContext.isBlank()) {
            result.append("\n【附加模态文本】\n")
                .append(truncate(modalityContext.trim(), remaining));
        }
        return result.toString();
    }

    private List<QueryVariant> queryVariants(String query, boolean exactMatch,
                                             List<QueryVariant> supplementalVariants) {
        String original = query == null ? "" : query.trim();
        if (original.isEmpty()) return Collections.emptyList();
        List<QueryVariant> expanded = queryExpansionService == null
            ? List.of() : queryExpansionService.expand(original, exactMatch);
        List<QueryVariant> normalized = new ArrayList<>();
        normalized.add(QueryVariant.original(original));
        Set<String> seen = new LinkedHashSet<>();
        seen.add(StructuredQaUtil.normalizeQuestion(original));
        appendSupplementalVariants(normalized, seen, supplementalVariants);
        appendSupplementalVariants(normalized, seen, expanded);
        return List.copyOf(normalized);
    }

    private void appendSupplementalVariants(List<QueryVariant> target, Set<String> seen,
                                            List<QueryVariant> variants) {
        if (variants == null || variants.isEmpty()) return;
        for (QueryVariant variant : variants) {
            if (variant == null || variant.original()) continue;
            String key = StructuredQaUtil.normalizeQuestion(variant.query());
            if (key.isBlank() || !seen.add(key)) continue;
            target.add(variant);
        }
    }

    private List<QueryVariant> expandedVariants(List<QueryVariant> variants) {
        if (variants == null || variants.size() <= 1) return Collections.emptyList();
        return variants.stream().filter(variant -> !variant.original()).toList();
    }

    private StructuredUnitRecall recallStructuredUnits(List<Double> embedding,
                                                        Map<String, Object> filters) {
        if (!structuredUnitIndexEnabled || structuredUnitRetrievalClient == null
                || embedding == null || embedding.isEmpty()) {
            return StructuredUnitRecall.empty();
        }
        List<StructuredUnitHit> hits = structuredUnitRetrievalClient.search(
            embedding, Math.max(1, structuredUnitTopK), filters);
        if (hits == null || hits.isEmpty()) return StructuredUnitRecall.empty();

        List<Map<String, Object>> diagnostics = structuredUnitDiagnostics(hits);
        if (structuredUnitShadowOnly) {
            return new StructuredUnitRecall(Collections.emptyList(), diagnostics);
        }

        double weight = Math.max(0, Math.min(1, structuredUnitWeight));
        if (weight == 0) return StructuredUnitRecall.empty();
        List<StructuredUnitAttribution> unitAttributions = new ArrayList<>();
        Set<String> seenUnits = new LinkedHashSet<>();
        Set<Long> requestedChunkIds = new LinkedHashSet<>();
        for (int index = 0; index < hits.size(); index++) {
            StructuredUnitHit hit = hits.get(index);
            double weightedScore = hit.score() * weight;
            if (!Double.isFinite(weightedScore) || weightedScore <= 0) continue;
            if (!seenUnits.add(hit.semanticUnitId())) continue;
            List<Long> evidenceChunkIds = hit.evidenceChunkIds().stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
            if (evidenceChunkIds.isEmpty()) continue;

            long additionalChunks = evidenceChunkIds.stream()
                .filter(chunkId -> !requestedChunkIds.contains(chunkId))
                .count();
            if (requestedChunkIds.size() + additionalChunks
                    > MAX_STRUCTURED_EVIDENCE_CHUNKS) {
                continue;
            }
            unitAttributions.add(new StructuredUnitAttribution(
                hit.semanticUnitId(), hit.score(), weightedScore, index + 1,
                evidenceChunkIds));
            requestedChunkIds.addAll(evidenceChunkIds);
        }
        if (unitAttributions.isEmpty()) {
            return StructuredUnitRecall.empty();
        }

        List<Map<String, Object>> chunks = structuredUnitRetrievalClient.evidenceChunks(
            List.copyOf(requestedChunkIds), filters);
        if (chunks == null || chunks.isEmpty()) {
            return StructuredUnitRecall.empty();
        }
        Map<Long, Map<String, Object>> resolvedByChunkId = new LinkedHashMap<>();
        for (Map<String, Object> chunk : chunks) {
            if (chunk == null) continue;
            Long chunkId = longValue(chunk.get("chunkId"));
            if (chunkId == null) chunkId = longValue(chunk.get("sourceId"));
            Object content = chunk.get("content");
            if (chunkId == null || !requestedChunkIds.contains(chunkId)
                    || !(content instanceof String text) || text.isBlank()) {
                continue;
            }
            resolvedByChunkId.putIfAbsent(chunkId, chunk);
        }

        Map<Long, StructuredEvidenceAttribution> attributionByChunk = new LinkedHashMap<>();
        for (StructuredUnitAttribution unit : unitAttributions) {
            boolean complete = unit.evidenceChunkIds().stream()
                .allMatch(resolvedByChunkId::containsKey);
            if (!complete) continue;
            StructuredEvidenceAttribution attribution = new StructuredEvidenceAttribution(
                unit.semanticUnitId(), unit.rawScore(), unit.weightedScore(), unit.rank());
            for (Long chunkId : unit.evidenceChunkIds()) {
                StructuredEvidenceAttribution existing = attributionByChunk.get(chunkId);
                if (existing == null || attribution.weightedScore() > existing.weightedScore()) {
                    attributionByChunk.put(chunkId, attribution);
                }
            }
        }
        if (attributionByChunk.isEmpty()) return StructuredUnitRecall.empty();

        List<Map<String, Object>> evidenceCandidates = new ArrayList<>();
        int k = Math.max(1, rankFusionK);
        for (Long chunkId : requestedChunkIds) {
            StructuredEvidenceAttribution attribution = attributionByChunk.get(chunkId);
            Map<String, Object> chunk = resolvedByChunkId.get(chunkId);
            if (chunk == null || attribution == null) continue;
            Map<String, Object> evidence = new HashMap<>(chunk);
            evidence.put("type", "chunk");
            evidence.put("chunkId", chunkId);
            evidence.put("sourceId", chunkId);
            evidence.putIfAbsent("sourceType", "document");
            evidence.remove("answer");
            evidence.remove("fullAnswer");
            evidence.remove("directAnswerEnabled");
            evidence.remove("directAnswerEligible");
            evidence.remove("structuredQaExactMatch");
            evidence.put("structuredQa", false);
            evidence.put("similarity", round6(attribution.weightedScore()));
            evidence.put("structuredUnitId", attribution.semanticUnitId());
            evidence.put("structuredUnitRawScore", round6(attribution.rawScore()));
            evidence.put("structuredUnitScore", round6(attribution.weightedScore()));
            evidence.put("structuredUnitRank", attribution.rank());
            evidence.put("structuredUnitWeight", round6(weight));
            evidence.put("structuredUnitEvidence", true);
            evidence.put("structuredUnitRrfContribution",
                weight / (k + attribution.rank()));
            evidenceCandidates.add(evidence);
        }
        return new StructuredUnitRecall(
            List.copyOf(evidenceCandidates), Collections.emptyList());
    }

    private List<Map<String, Object>> structuredUnitDiagnostics(List<StructuredUnitHit> hits) {
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        for (int index = 0; index < hits.size(); index++) {
            StructuredUnitHit hit = hits.get(index);
            Map<String, Object> diagnostic = new LinkedHashMap<>();
            diagnostic.put("type", "structured_unit_diagnostic");
            diagnostic.put("sourceType", "structured_unit_shadow");
            diagnostic.put("sourceId", hit.semanticUnitId());
            diagnostic.put("diagnosticOnly", true);
            diagnostic.put("structuredUnitId", hit.semanticUnitId());
            diagnostic.put("structuredUnitRawScore", round6(hit.score()));
            diagnostic.put("structuredUnitRank", index + 1);
            diagnostic.put("structuredUnitWeight", round6(
                Math.max(0, Math.min(1, structuredUnitWeight))));
            diagnostic.put("evidenceChunkIds", List.copyOf(hit.evidenceChunkIds()));
            diagnostic.put("structuredUnitMode", structuredUnitShadowOnly ? "shadow" : "active");
            diagnostics.add(Collections.unmodifiableMap(diagnostic));
        }
        return List.copyOf(diagnostics);
    }

    private Map<String, Object> trustedFilters(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) return Collections.emptyMap();
        Map<String, Object> result = new LinkedHashMap<>();
        filters.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null) result.put(key.trim(), value);
        });
        return result.isEmpty() ? Collections.emptyMap() : Map.copyOf(result);
    }

    private Map<String, Object> keywordMatch(String query, boolean trackHit,
                                             Map<String, Object> filters) {
        return filters.isEmpty()
            ? faqMatchService.match(query, trackHit)
            : faqMatchService.match(query, trackHit, filters);
    }

    private List<Map<String, Object>> semanticMatch(String query, List<Double> embedding,
                                                    int limit, Map<String, Object> filters) {
        return filters.isEmpty()
            ? knowledgeClient.semanticMatch(query, embedding, limit)
            : knowledgeClient.semanticMatch(query, embedding, limit, filters);
    }

    private List<Map<String, Object>> bm25Match(String query, int limit,
                                                Map<String, Object> filters) {
        return filters.isEmpty()
            ? knowledgeClient.bm25Match(query, limit, bm25MinScore)
            : knowledgeClient.bm25Match(query, limit, bm25MinScore, filters);
    }

    private List<Map<String, Object>> lexicalMatch(String query, int limit,
                                                   Map<String, Object> filters) {
        return filters.isEmpty()
            ? knowledgeClient.lexicalMatch(query, limit, lexicalThreshold)
            : knowledgeClient.lexicalMatch(query, limit, lexicalThreshold, filters);
    }

    private List<Map<String, Object>> phoneticMatch(String query, int limit,
                                                    Map<String, Object> filters) {
        return filters.isEmpty()
            ? knowledgeClient.phoneticMatch(query, limit, phoneticThreshold)
            : knowledgeClient.phoneticMatch(query, limit, phoneticThreshold, filters);
    }

    private List<Map<String, Object>> copyAndRenumberCitations(
            List<Map<String, Object>> values, int offset) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        List<Map<String, Object>> result = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            Map<String, Object> citation = new LinkedHashMap<>(values.get(i));
            citation.put("ref", offset + i + 1);
            result.add(citation);
        }
        return result;
    }

    private List<String> lexicalQueries(String query) {
        String normalized = query == null ? "" : query.trim();
        Set<String> queries = new LinkedHashSet<>();
        if (!normalized.isBlank()) queries.add(normalized);

        String compact = normalized.replaceAll("\\s+", "");
        boolean serviceTiming = compact.contains("多久") || compact.contains("小时")
            || compact.contains("分钟") || compact.contains("天内")
            || compact.contains("保证") || compact.contains("承诺");
        boolean serviceAction = compact.contains("响应")
            || compact.contains("解决") || compact.contains("处理");
        if (serviceTiming && serviceAction) {
            if (compact.contains("一小时") || compact.contains("1小时")) {
                queries.add("1小时内响应");
            } else if (compact.contains("远程")) {
                queries.add("远程问题");
            } else {
                queries.add("响应");
            }
        }

        boolean introduction = normalized.contains("介绍")
            || normalized.contains("做什么") || normalized.contains("业务");
        if (normalized.contains("产品") && introduction) queries.add("产品介绍");
        if (normalized.contains("公司") && introduction) queries.add("公司介绍");
        if (normalized.contains("优势") || normalized.contains("特点")) queries.add("产品优势");
        boolean loginIntent = normalized.contains("怎么登录")
            || normalized.contains("如何登录") || normalized.contains("登录入口")
            || normalized.contains("哪里登录") || normalized.contains("在哪登录");
        if (normalized.contains("点签") && loginIntent) {
            queries.add("点签可以在哪里使用");
        }
        boolean officialWebsiteIntent = compact.contains("官网")
            || compact.contains("官方网站") || compact.contains("网址")
            || compact.contains("网站地址") || compact.contains("网页地址")
            || compact.contains("网页版地址");
        if (compact.contains("点签") && officialWebsiteIntent) {
            queries.add("点签可以在哪里使用");
        }
        return List.copyOf(queries);
    }

    private List<Map<String, Object>> selectAcceptedCandidates(
            List<Map<String, Object>> candidates, String query, boolean rerankApplied) {
        List<Map<String, Object>> eligible = new ArrayList<>();
        Set<String> seenStructuredGroups = new LinkedHashSet<>();
        Map<String, Object> sparseFallback = rerankApplied
            ? null : strongestSparseFallback(candidates, query);
        if (sparseFallback != null) eligible.add(sparseFallback);
        for (Map<String, Object> candidate : candidates) {
            if (candidate == sparseFallback) continue;
            boolean accepted = isReviewedExactStructuredQa(candidate)
                || (rerankApplied
                    ? Boolean.TRUE.equals(candidate.get("reranked"))
                        && number(candidate.get("rerankScore")) >= rerankMediumMinScore
                    : number(candidate.get("combinedScore")) >= contextThreshold);
            if (!accepted) continue;
            if (isStructuredQaCandidate(candidate)) {
                String groupKey = firstNonBlank(string(candidate.get("qaGroupKey")),
                    string(candidate.get("qaKey")));
                if (!groupKey.isBlank() && !seenStructuredGroups.add(groupKey)) continue;
            }
            eligible.add(candidate);
        }
        int limit = Math.min(Math.max(topK, 0), eligible.size());
        if (limit == 0) return Collections.emptyList();

        List<Map<String, Object>> accepted = new ArrayList<>(eligible.subList(0, limit));
        if (!hasExplicitImageIntent(query)) return List.copyOf(accepted);

        Map<String, Object> bestImage = eligible.stream()
            .filter(this::isImageCandidate)
            .findFirst()
            .orElse(null);
        if (bestImage == null || accepted.contains(bestImage)) return List.copyOf(accepted);

        accepted.remove(accepted.size() - 1);
        accepted.add(bestImage);
        return List.copyOf(accepted);
    }

    /**
     * BM25 is normally a ranking signal, not an absolute confidence score. During
     * reranker outages, however, a clearly leading standard question with strong
     * textual alignment is safer than accepting an unrelated vector hit at the
     * context threshold. Keep this path deliberately narrow.
     */
    private Map<String, Object> strongestSparseFallback(
            List<Map<String, Object>> candidates, String query) {
        if (!bm25Enabled || candidates == null || candidates.isEmpty()) return null;
        List<Map<String, Object>> sparse = candidates.stream()
            .filter(candidate -> candidate.get("bm25Rank") instanceof Number)
            .sorted(Comparator.comparingInt(candidate ->
                ((Number) candidate.get("bm25Rank")).intValue()))
            .toList();
        if (sparse.isEmpty()) return null;

        Map<String, Object> top = sparse.get(0);
        double topScore = number(top.get("bm25Score"));
        double secondScore = sparse.size() > 1 ? number(sparse.get(1).get("bm25Score")) : 0;
        double similarity = questionSimilarity(query, firstNonBlank(
            structuredQuestion(top), string(top.get("sectionPath")), string(top.get("title"))));
        boolean leading = secondScore <= 0
            || topScore / Math.max(secondScore, 0.000001) >= bm25FallbackMinGapRatio;
        if (topScore < bm25FallbackMinScore || !leading
                || similarity < bm25FallbackMinQuestionSimilarity
                || Boolean.TRUE.equals(top.get("topicMismatch"))
                || Boolean.TRUE.equals(top.get("qaConflict"))) {
            return null;
        }
        top.put("sparseFallbackAccepted", true);
        top.put("sparseFallbackQuestionSimilarity", round6(similarity));
        top.put("sparseFallbackConfidence", round(bm25FallbackConfidence));
        return top;
    }

    private double acceptedConfidence(Map<String, Object> candidate) {
        if (Boolean.TRUE.equals(candidate.get("sparseFallbackAccepted"))) {
            return number(candidate.get("sparseFallbackConfidence"));
        }
        return number(candidate.get("combinedScore"));
    }

    private double questionSimilarity(String left, String right) {
        String first = StructuredQaUtil.normalizeQuestion(left);
        String second = StructuredQaUtil.normalizeQuestion(right);
        if (first.isBlank() || second.isBlank()) return 0;
        int[][] lengths = new int[first.length() + 1][second.length() + 1];
        for (int i = 1; i <= first.length(); i++) {
            for (int j = 1; j <= second.length(); j++) {
                lengths[i][j] = first.charAt(i - 1) == second.charAt(j - 1)
                    ? lengths[i - 1][j - 1] + 1
                    : Math.max(lengths[i - 1][j], lengths[i][j - 1]);
            }
        }
        return 2.0 * lengths[first.length()][second.length()]
            / (first.length() + second.length());
    }

    /**
     * An exact standard-question hit is a complete evidence boundary. Similar Q&A
     * candidates may be useful for paraphrases, but including them for an exact
     * hit lets the model combine mutually exclusive rules from different answers.
     */
    private List<Map<String, Object>> isolateFocusedStructuredQa(
            String query, List<Map<String, Object>> selected) {
        if (selected == null || selected.isEmpty()) return selected;
        Map<String, Object> top = selected.get(0);
        if (!isStructuredQaCandidate(top)) return selected;
        String normalizedQuery = StructuredQaUtil.normalizeQuestion(query);
        String normalizedQuestion = StructuredQaUtil.normalizeQuestion(
            structuredQuestion(top));
        boolean exact = !normalizedQuery.isBlank()
            && normalizedQuery.equals(normalizedQuestion);
        boolean embeddedStandardQuestion = containsStandardQuestion(
            normalizedQuery, top);
        if (!exact && !embeddedStandardQuestion) {
            if (isCompositeQuestion(query)) return selected;
            double topScore = focusScore(top);
            double competingScore = selected.stream()
                .skip(1)
                .filter(candidate -> !sameStructuredQaGroup(top, candidate))
                .mapToDouble(this::focusScore)
                .max().orElse(0);
            if (topScore < contextThreshold
                    || topScore - competingScore < structuredQaRerankMinGap) {
                return selected;
            }
        }

        String groupKey = firstNonBlank(string(top.get("qaGroupKey")),
            string(top.get("qaKey")));
        if (groupKey.isBlank()) return List.of(top);
        List<Map<String, Object>> sameGroup = selected.stream()
            .filter(candidate -> isStructuredQaCandidate(candidate)
                && groupKey.equals(firstNonBlank(string(candidate.get("qaGroupKey")),
                    string(candidate.get("qaKey")))))
            .toList();
        return sameGroup.isEmpty() ? List.of(top) : List.copyOf(sameGroup);
    }

    private boolean containsStandardQuestion(String normalizedQuery,
                                             Map<String, Object> candidate) {
        if (normalizedQuery == null || normalizedQuery.length() < 8
                || isCompositeQuestion(normalizedQuery)
                || !hasCompleteStructuredAnswer(candidate)) {
            return false;
        }
        String normalizedAnswer = StructuredQaUtil.normalizeQuestion(firstNonBlank(
            string(candidate.get("fullAnswer")), string(candidate.get("answer"))));
        return normalizedAnswer.contains(normalizedQuery);
    }

    private double focusScore(Map<String, Object> candidate) {
        return Boolean.TRUE.equals(candidate.get("reranked"))
            ? number(candidate.get("rerankScore"))
            : number(candidate.get("combinedScore"));
    }

    private List<Map<String, Object>> expandAdjacentChunks(
            List<Map<String, Object>> selected) {
        if (selected == null || selected.isEmpty() || neighborRadius <= 0
                || maxNeighborChunks <= 0) return selected;
        Map<String, Map<String, Object>> selectedByIdentity = new HashMap<>();
        Set<String> selectedContent = new HashSet<>();
        for (Map<String, Object> candidate : selected) {
            selectedByIdentity.put(candidateIdentity(candidate), candidate);
            String fingerprint = contentFingerprint(candidate);
            if (!fingerprint.isBlank()) selectedContent.add(fingerprint);
        }

        Map<String, Map<String, Object>> expanded = new LinkedHashMap<>();
        Set<String> neighborContent = new HashSet<>();
        int addedNeighbors = 0;
        for (Map<String, Object> anchor : selected) {
            if (!"chunk".equals(string(anchor.get("type")))) {
                expanded.putIfAbsent(candidateIdentity(anchor), anchor);
                continue;
            }
            boolean structuredAnchor = isStructuredQaCandidate(anchor);
            if (structuredAnchor && hasCompleteStructuredAnswer(anchor)) {
                expanded.putIfAbsent(candidateIdentity(anchor), anchor);
                continue;
            }
            Long documentId = anchor.get("documentId") instanceof Number value
                ? value.longValue() : null;
            Integer chunkIndex = anchor.get("chunkIndex") instanceof Number value
                ? value.intValue() : null;
            List<Map<String, Object>> group = new ArrayList<>();
            group.add(anchor);
            if (addedNeighbors < maxNeighborChunks) {
                for (Map<String, Object> neighbor : knowledgeClient.neighborChunks(
                        documentId, chunkIndex, neighborRadius,
                        string(anchor.get("sectionPath")))) {
                    Map<String, Object> evidence = new HashMap<>(neighbor);
                    // A neighboring FAQ is not evidence for the current FAQ. Keep
                    // adjacent expansion for plain prose, but only allow chunks
                    // from the same structured answer group to continue a long QA.
                    if (structuredAnchor && !sameStructuredQaGroup(anchor, evidence)) {
                        continue;
                    }
                    evidence.putIfAbsent("type", "chunk");
                    evidence.putIfAbsent("sourceType", anchor.get("sourceType"));
                    evidence.putIfAbsent("title", anchor.get("title"));
                    evidence.putIfAbsent("combinedScore", anchor.get("combinedScore"));
                    evidence.put("adjacentContext", true);
                    String identity = candidateIdentity(evidence);
                    group.add(selectedByIdentity.getOrDefault(identity, evidence));
                }
            }
            group.sort(Comparator.comparingInt(this::chunkOrder));
            for (Map<String, Object> evidence : group) {
                String identity = candidateIdentity(evidence);
                boolean selectedCandidate = selectedByIdentity.containsKey(identity);
                if (!selectedCandidate) {
                    if (addedNeighbors >= maxNeighborChunks) continue;
                    String fingerprint = contentFingerprint(evidence);
                    if ((!fingerprint.isBlank() && selectedContent.contains(fingerprint))
                            || (!fingerprint.isBlank() && !neighborContent.add(fingerprint))) {
                        continue;
                    }
                    addedNeighbors++;
                }
                expanded.putIfAbsent(identity, evidence);
            }
        }
        return List.copyOf(expanded.values());
    }

    private boolean hasCompleteStructuredAnswer(Map<String, Object> candidate) {
        return !firstNonBlank(string(candidate.get("fullAnswer")),
            string(candidate.get("answer"))).isBlank();
    }

    private boolean isStructuredQaCandidate(Map<String, Object> candidate) {
        return candidate != null && (Boolean.TRUE.equals(candidate.get("structuredQa"))
            || "structured_qa".equalsIgnoreCase(string(candidate.get("knowledgeType"))));
    }

    private boolean sameStructuredQaGroup(Map<String, Object> anchor,
                                          Map<String, Object> neighbor) {
        if (!isStructuredQaCandidate(neighbor)) return false;
        String anchorGroup = firstNonBlank(string(anchor.get("qaGroupKey")),
            string(anchor.get("qaKey")));
        String neighborGroup = firstNonBlank(string(neighbor.get("qaGroupKey")),
            string(neighbor.get("qaKey")));
        return !anchorGroup.isBlank() && anchorGroup.equals(neighborGroup);
    }

    private int chunkOrder(Map<String, Object> candidate) {
        return candidate.get("chunkIndex") instanceof Number value
            ? value.intValue() : Integer.MAX_VALUE;
    }

    private String contentFingerprint(Map<String, Object> candidate) {
        return string(candidate.get("content")).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private boolean hasExplicitImageIntent(String query) {
        if (query == null || query.isBlank()) return false;
        String normalized = query.replaceAll("\\s+", "");
        return IMAGE_INTENT_TERMS.stream().anyMatch(normalized::contains);
    }

    private boolean isImageCandidate(Map<String, Object> candidate) {
        return "image".equalsIgnoreCase(string(candidate.get("sourceType")));
    }

    private String shiftCitationHeaders(String value,
                                        List<Map<String, Object>> citations,
                                        int offset) {
        if (value == null || value.isBlank() || offset == 0
                || citations == null || citations.isEmpty()) return value;
        String shifted = value;
        for (Map<String, Object> citation : citations) {
            int ref = citation.get("ref") instanceof Number number ? number.intValue() : 0;
            String title = string(citation.get("title"));
            if (ref <= 0 || title.isBlank()) continue;
            Pattern header = Pattern.compile("(?m)^" + Pattern.quote("[" + ref + "] " + title) + "$");
            shifted = header.matcher(shifted)
                .replaceFirst(java.util.regex.Matcher.quoteReplacement(
                    "[" + (ref + offset) + "] " + title));
        }
        return shifted;
    }

    private List<Map<String, Object>> buildCitations(List<Map<String, Object>> accepted) {
        List<Map<String, Object>> citations = new ArrayList<>();
        for (int i = 0; i < accepted.size(); i++) {
            Map<String, Object> candidate = accepted.get(i);
            String type = string(candidate.getOrDefault("type", "item"));
            boolean image = "image".equals(string(candidate.get("sourceType")));
            Map<String, Object> citation = new LinkedHashMap<>();
            citation.put("ref", i + 1);
            citation.put("id", image
                ? "image:" + candidate.get("documentId") + ":chunk:" + candidate.get("chunkId")
                : "chunk".equals(type) ? "chunk:" + candidate.get("chunkId")
                : "faq:" + candidate.get("itemId"));
            citation.put("sourceType", image ? "image" : "chunk".equals(type) ? "document" : "faq");
            citation.put("sourceId", "chunk".equals(type) ? candidate.get("chunkId") : candidate.get("itemId"));
            if (candidate.get("documentId") != null) citation.put("documentId", candidate.get("documentId"));
            if (candidate.get("chunkIndex") != null) citation.put("chunkIndex", candidate.get("chunkIndex"));
            if (candidate.get("sectionPath") != null) citation.put("sectionPath", candidate.get("sectionPath"));
            if (candidate.get("previewUrl") != null) citation.put("previewUrl", candidate.get("previewUrl"));
            citation.put("title", firstNonBlank(string(candidate.get("title")),
                string(candidate.get("question")), "知识库来源"));
            citation.put("score", round(number(candidate.get("combinedScore"))));
            citation.put("snippet", truncate(Boolean.TRUE.equals(candidate.get("structuredQa"))
                ? firstNonBlank(string(candidate.get("fullAnswer")), string(candidate.get("answer")))
                : firstNonBlank(string(candidate.get("content")), string(candidate.get("answer"))), 180));
            citations.add(citation);
        }
        return citations;
    }

    private String buildContext(List<Map<String, Object>> accepted,
                                List<Map<String, Object>> citations) {
        StringBuilder context = new StringBuilder("【企业内部事实】\n");
        for (int i = 0; i < accepted.size(); i++) {
            Map<String, Object> candidate = accepted.get(i);
            context.append("事实：").append(citations.get(i).get("title")).append("\n");
            if ("item".equals(candidate.get("type"))
                    || Boolean.TRUE.equals(candidate.get("structuredQa"))) {
                context.append("问题：").append(candidate.get("question")).append("\n")
                    .append("答案：").append(firstNonBlank(
                        string(candidate.get("fullAnswer")), string(candidate.get("answer"))))
                    .append("\n");
            } else {
                String sectionPath = string(candidate.get("sectionPath"));
                if (!sectionPath.isBlank()) context.append("章节：").append(sectionPath).append("\n");
                context.append(candidate.get("content")).append("\n");
            }
        }
        context.append("回答时先锁定客户明确询问的产品、业务或对象。")
            .append("如果事实范围比客户问题更宽，只提取与所问对象和意图直接相关的内容，")
            .append("不要照搬整段答案，不要罗列客户未询问的其他产品或业务。")
            .append("事实包含表格时，必须按同一行的列关系回答；√表示支持，×表示不支持，")
            .append("不得把其他行的条件或结论移到当前对象。")
            .append("客户只给出一个大类，而表格把该类拆成多行且规则不同时，")
            .append("不得自行假设客户属于其中某一行；应先说明这些行共同适用的结论，")
            .append("再自然询问具体类型。不得补充事实未明确说明的手机号归属、审核材料或办理时效。")
            .append("请直接向客户陈述相关事实和适用条件，不要提及事实来自何处，")
            .append("不要输出引用或参考来源。");
        return context.toString();
    }

    private List<Map<String, Object>> copyCandidates(List<Map<String, Object>> candidates) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> candidate : candidates.stream()
                .limit(Math.max(topK, candidateK)).toList()) {
            Map<String, Object> copy = new LinkedHashMap<>();
            copy.put("type", candidate.get("type"));
            copy.put("sourceId", candidate.get("sourceId"));
            copy.put("itemId", candidate.get("itemId"));
            copy.put("chunkId", candidate.get("chunkId"));
            copy.put("documentId", candidate.get("documentId"));
            copy.put("chunkIndex", candidate.get("chunkIndex"));
            copy.put("sourceType", candidate.get("sourceType"));
            copy.put("categoryId", candidate.get("categoryId"));
            copy.put("sourceScope", candidate.get("sourceScope"));
            copy.put("mediaType", candidate.get("mediaType"));
            copy.put("expiresAt", candidate.get("expiresAt"));
            copy.put("knowledgeType", candidate.get("knowledgeType"));
            copy.put("sectionPath", candidate.get("sectionPath"));
            copy.put("previewUrl", candidate.get("previewUrl"));
            copy.put("title", candidate.get("title"));
            copy.put("vectorScore", candidate.get("vectorScore"));
            copy.put("expandedVectorScore", candidate.get("expandedVectorScore"));
            copy.put("structuredUnitId", candidate.get("structuredUnitId"));
            copy.put("structuredUnitRawScore", candidate.get("structuredUnitRawScore"));
            copy.put("structuredUnitScore", candidate.get("structuredUnitScore"));
            copy.put("structuredUnitRank", candidate.get("structuredUnitRank"));
            copy.put("structuredUnitWeight", candidate.get("structuredUnitWeight"));
            copy.put("structuredUnitEvidence", candidate.get("structuredUnitEvidence"));
            copy.put("keywordScore", candidate.get("keywordScore"));
            copy.put("lexicalScore", candidate.get("lexicalScore"));
            copy.put("phoneticScore", candidate.get("phoneticScore"));
            copy.put("bm25Score", candidate.get("bm25Score"));
            copy.put("sparseFallbackAccepted", candidate.get("sparseFallbackAccepted"));
            copy.put("sparseFallbackQuestionSimilarity",
                candidate.get("sparseFallbackQuestionSimilarity"));
            copy.put("sparseFallbackConfidence", candidate.get("sparseFallbackConfidence"));
            copy.put("matchMode", candidate.get("matchMode"));
            copy.put("exactMatch", candidate.get("exactMatch"));
            copy.put("topicMismatch", candidate.get("topicMismatch"));
            copy.put("topicAlignment", candidate.get("topicAlignment"));
            copy.put("vectorRank", candidate.get("vectorRank"));
            copy.put("bm25Rank", candidate.get("bm25Rank"));
            copy.put("lexicalRank", candidate.get("lexicalRank"));
            copy.put("phoneticRank", candidate.get("phoneticRank"));
            copy.put("keywordRank", candidate.get("keywordRank"));
            copy.put("expansionRank", candidate.get("expansionRank"));
            copy.put("expandedQuery", candidate.get("expandedQuery"));
            copy.put("expansionPurpose", candidate.get("expansionPurpose"));
            copy.put("expansionWeight", candidate.get("expansionWeight"));
            copy.put("rrfScore", candidate.get("rrfScore"));
            copy.put("rerankScore", candidate.get("rerankScore"));
            copy.put("rerankConfidenceTier", candidate.get("rerankConfidenceTier"));
            copy.put("rerankSecondScore", candidate.get("rerankSecondScore"));
            copy.put("rerankScoreGap", candidate.get("rerankScoreGap"));
            copy.put("rankScore", candidate.get("rankScore"));
            copy.put("structuredQa", candidate.get("structuredQa"));
            copy.put("qaKey", candidate.get("qaKey"));
            copy.put("qaGroupKey", candidate.get("qaGroupKey"));
            copy.put("qaVersion", candidate.get("qaVersion"));
            copy.put("directAnswerEnabled", candidate.get("directAnswerEnabled"));
            copy.put("directAnswerEligible", candidate.get("directAnswerEligible"));
            copy.put("qaConflict", candidate.get("qaConflict"));
            copy.put("qaDirectStatus", candidate.get("qaDirectStatus"));
            copy.put("directMatchMode", candidate.get("directMatchMode"));
            copy.put("structuredQaExactMatch", candidate.get("structuredQaExactMatch"));
            copy.put("score", candidate.get("combinedScore"));
            result.add(copy);
        }
        return List.copyOf(result);
    }

    private List<Map<String, Object>> candidatesWithDiagnostics(
            List<Map<String, Object>> candidates, List<Map<String, Object>> diagnostics) {
        List<Map<String, Object>> result = new ArrayList<>(copyCandidates(candidates));
        if (diagnostics != null && !diagnostics.isEmpty()) result.addAll(diagnostics);
        return List.copyOf(result);
    }

    private RetrievalResult directKeywordResult(Map<String, Object> keywordMatch,
                                                 double keywordScore) {
        Map<String, Object> candidate = new HashMap<>(keywordMatch);
        candidate.put("type", "item");
        candidate.put("sourceType", "faq");
        candidate.put("sourceId", keywordMatch.get("itemId"));
        candidate.put("title", keywordMatch.get("question"));
        candidate.put("content", keywordMatch.get("answer"));
        candidate.put("similarity", 0.0);
        candidate.put("vectorScore", 0.0);
        candidate.put("lexicalScore", 0.0);
        candidate.put("phoneticScore", 0.0);
        candidate.put("keywordScore", round(keywordScore));
        candidate.put("combinedScore", round(keywordScore));
        candidate.put("matchMode", "keyword");

        List<Map<String, Object>> accepted = List.of(candidate);
        List<Map<String, Object>> citations = buildCitations(accepted);
        return new RetrievalResult(true, true, string(keywordMatch.get("answer")),
            buildContext(accepted, citations), round(keywordScore), "direct", false,
            List.copyOf(citations), copyCandidates(accepted));
    }

    private void mergeRankedMatches(List<Map<String, Object>> candidates,
                                    List<Map<String, Object>> matches,
                                    String scoreField,
                                    String matchMode,
                                    String rankField) {
        if (matches == null || matches.isEmpty()) return;
        for (int index = 0; index < matches.size(); index++) {
            Map<String, Object> match = matches.get(index);
            Map<String, Object> existing = candidates.stream()
                .filter(candidate -> candidateIdentity(candidate).equals(candidateIdentity(match)))
                .findFirst()
                .orElse(null);
            if (existing == null) {
                existing = new HashMap<>(match);
                candidates.add(existing);
            } else {
                for (Map.Entry<String, Object> entry : match.entrySet()) {
                    if ("matchMode".equals(entry.getKey()) || "similarity".equals(entry.getKey())) {
                        continue;
                    }
                    existing.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
            int rank = index + 1;
            if (existing.get(rankField) instanceof Number previous) {
                rank = Math.min(rank, previous.intValue());
            }
            existing.put(rankField, rank);
            if (scoreField == null) {
                continue;
            }

            double fallbackScore = number(match.get(scoreField));
            double bestScore = Math.max(fallbackScore, number(existing.get(scoreField)));
            existing.put(scoreField, bestScore);
            if (!"bm25Score".equals(scoreField)
                    && bestScore > number(existing.get("similarity"))) {
                existing.put("similarity", bestScore);
                existing.put("matchMode", matchMode);
            } else if (!"bm25Score".equals(scoreField)
                    && existing.get("matchMode") == null && matchMode != null) {
                existing.put("matchMode", matchMode);
            }
        }
    }

    private void mergeExpansionMatches(List<Map<String, Object>> candidates,
                                       List<Map<String, Object>> matches,
                                       QueryVariant variant,
                                       String sourceScoreField,
                                       String targetScoreField,
                                       String matchMode) {
        if (matches == null || matches.isEmpty()) return;
        List<Map<String, Object>> weighted = new ArrayList<>(matches.size());
        for (Map<String, Object> match : matches) {
            Map<String, Object> copy = new HashMap<>(match);
            if (sourceScoreField != null && targetScoreField != null) {
                double weightedScore = number(match.get(sourceScoreField)) * variant.weight();
                copy.put(targetScoreField, round6(weightedScore));
                copy.put("similarity", round6(weightedScore));
            }
            copy.put("matchMode", matchMode);
            weighted.add(copy);
        }
        mergeRankedMatches(candidates, weighted, targetScoreField, matchMode, "expansionRank");

        int k = Math.max(1, rankFusionK);
        for (int index = 0; index < matches.size(); index++) {
            Map<String, Object> match = matches.get(index);
            Map<String, Object> candidate = candidates.stream()
                .filter(value -> candidateIdentity(value).equals(candidateIdentity(match)))
                .findFirst().orElse(null);
            if (candidate == null) continue;
            int rank = index + 1;
            double contribution = variant.weight() / (k + rank);
            if (contribution <= number(candidate.get("expansionRrfContribution"))) continue;
            candidate.put("expansionRrfContribution", contribution);
            candidate.put("expansionRank", rank);
            candidate.put("expandedQuery", variant.query());
            candidate.put("expansionPurpose", variant.purpose());
            candidate.put("expansionWeight", round6(variant.weight()));
        }
    }

    private List<Map<String, Object>> semanticMatches(List<Map<String, Object>> matches) {
        if (matches == null || matches.isEmpty()) return Collections.emptyList();
        List<Map<String, Object>> values = new ArrayList<>(matches.size());
        for (Map<String, Object> match : matches) {
            Map<String, Object> copy = new HashMap<>(match);
            copy.put("semanticScore", number(match.get("similarity")));
            values.add(copy);
        }
        return values;
    }

    private RerankService.RerankDiagnostics applyReranking(
            String query, List<Map<String, Object>> candidates) {
        if (rerankService == null) {
            return new RerankService.RerankDiagnostics(false, false, false,
                "service_unavailable", 0, "none", null);
        }
        if (!rerankService.isAvailable()) {
            return firstNonNullRerankDiagnostics(rerankService.diagnostics(),
                "not_configured");
        }
        if (candidates.isEmpty()) {
            return firstNonNullRerankDiagnostics(rerankService.diagnostics(),
                "no_candidates");
        }
        int limit = Math.min(Math.max(candidateK, topK), candidates.size());
        List<Map<String, Object>> selected = new ArrayList<>(candidates.subList(0, limit));
        List<String> documents = selected.stream().map(this::rerankDocument).toList();
        Map<Integer, Double> scores = rerankService.rerank(query, documents);
        RerankService.RerankDiagnostics diagnostics = rerankService.diagnostics();
        if (diagnostics == null) {
            diagnostics = new RerankService.RerankDiagnostics(true, true,
                scores.size() == selected.size(),
                scores.size() == selected.size() ? null : "partial_response",
                0, scores.size() == selected.size() ? "rerank" : "fused", "unknown");
        }
        if (scores.size() != selected.size()) {
            if (!scores.isEmpty()) {
                log.warn("Ignoring partial rerank response: expected {} scores but received {}",
                    selected.size(), scores.size());
            }
            return diagnostics.applied() ? diagnostics.withFailure(
                "partial_response", "fused") : diagnostics;
        }
        for (Map.Entry<Integer, Double> score : scores.entrySet()) {
            if (score.getKey() < 0 || score.getKey() >= selected.size()) continue;
            Map<String, Object> candidate = selected.get(score.getKey());
            candidate.put("rerankScore", round6(score.getValue()));
            candidate.put("rankScore", round6(score.getValue()));
            candidate.put("reranked", true);
        }
        return diagnostics;
    }

    private RerankService.RerankDiagnostics firstNonNullRerankDiagnostics(
            RerankService.RerankDiagnostics diagnostics, String failureReason) {
        if (diagnostics != null) return diagnostics;
        return new RerankService.RerankDiagnostics(true, false, false,
            failureReason, 0, "fused", "unknown");
    }

    private RerankConfidenceDecision assessRerankConfidence(
            List<Map<String, Object>> candidates) {
        List<Map<String, Object>> reranked = candidates.stream()
            .filter(candidate -> Boolean.TRUE.equals(candidate.get("reranked")))
            .toList();
        if (reranked.isEmpty()) return RerankConfidenceDecision.unavailable();

        Map<String, Object> top = reranked.get(0);
        double topScore = number(top.get("rerankScore"));
        double secondScore = reranked.size() > 1
            ? number(reranked.get(1).get("rerankScore")) : 0;
        double gap = topScore - secondScore;
        RerankConfidenceTier tier;
        if (topScore >= rerankHighMinScore && gap >= rerankHighMinGap) {
            tier = RerankConfidenceTier.HIGH;
        } else if (topScore >= rerankMediumMinScore && gap >= rerankMediumMinGap) {
            tier = RerankConfidenceTier.MEDIUM;
        } else {
            tier = RerankConfidenceTier.LOW;
        }
        top.put("rerankConfidenceTier", tier.name());
        top.put("rerankSecondScore", round6(secondScore));
        top.put("rerankScoreGap", round6(gap));
        return new RerankConfidenceDecision(true, tier, topScore);
    }

    private String rerankDocument(Map<String, Object> candidate) {
        boolean structuredQa = Boolean.TRUE.equals(candidate.get("structuredQa"));
        String heading = structuredQa
            ? firstNonBlank(structuredQuestion(candidate), string(candidate.get("title")))
            : firstNonBlank(string(candidate.get("title")), string(candidate.get("question")));
        String body = structuredQa
            ? firstNonBlank(string(candidate.get("fullAnswer")), string(candidate.get("answer")))
            : firstNonBlank(string(candidate.get("content")), string(candidate.get("answer")));
        return String.join("\n", heading, body);
    }

    private int compareCandidates(Map<String, Object> left, Map<String, Object> right) {
        boolean leftExactQa = Boolean.TRUE.equals(left.get("structuredQaExactMatch"));
        boolean rightExactQa = Boolean.TRUE.equals(right.get("structuredQaExactMatch"));
        if (leftExactQa != rightExactQa) return leftExactQa ? -1 : 1;
        boolean leftEligibleExactQa = leftExactQa
            && Boolean.TRUE.equals(left.get("directAnswerEligible"))
            && !Boolean.TRUE.equals(left.get("qaConflict"));
        boolean rightEligibleExactQa = rightExactQa
            && Boolean.TRUE.equals(right.get("directAnswerEligible"))
            && !Boolean.TRUE.equals(right.get("qaConflict"));
        if (leftEligibleExactQa != rightEligibleExactQa) {
            return leftEligibleExactQa ? -1 : 1;
        }
        boolean leftReranked = Boolean.TRUE.equals(left.get("reranked"));
        boolean rightReranked = Boolean.TRUE.equals(right.get("reranked"));
        if (leftReranked != rightReranked) return leftReranked ? -1 : 1;
        boolean leftExactLexical = number(left.get("lexicalScore")) >= 1.0;
        boolean rightExactLexical = number(right.get("lexicalScore")) >= 1.0;
        if (leftExactLexical != rightExactLexical) return leftExactLexical ? -1 : 1;
        double leftRank = number(left.get("rankScore"));
        double rightRank = number(right.get("rankScore"));
        int ranking = Double.compare(rightRank, leftRank);
        if (Math.abs(rightRank - leftRank) > 0.002) return ranking;
        int priority = Double.compare(
            number(right.get("documentPriority")), number(left.get("documentPriority")));
        if (priority != 0) return priority;
        if (ranking != 0) return ranking;
        int relevance = Double.compare(
            number(right.get("combinedScore")), number(left.get("combinedScore")));
        if (relevance != 0) return relevance;
        return 0;
    }

    private double reciprocalRankScore(Map<String, Object> candidate) {
        int k = Math.max(1, rankFusionK);
        double raw = reciprocalRank(candidate, "vectorRank", VECTOR_RRF_WEIGHT, k)
            + reciprocalRank(candidate, "bm25Rank", BM25_RRF_WEIGHT, k)
            + reciprocalRank(candidate, "lexicalRank", LEXICAL_RRF_WEIGHT, k)
            + reciprocalRank(candidate, "phoneticRank", PHONETIC_RRF_WEIGHT, k)
            + reciprocalRank(candidate, "keywordRank", KEYWORD_RRF_WEIGHT, k)
            + EXPANSION_RRF_WEIGHT * number(candidate.get("expansionRrfContribution"))
            + number(candidate.get("structuredUnitRrfContribution"));
        double totalWeight = VECTOR_RRF_WEIGHT + BM25_RRF_WEIGHT + LEXICAL_RRF_WEIGHT
            + PHONETIC_RRF_WEIGHT + KEYWORD_RRF_WEIGHT + EXPANSION_RRF_WEIGHT;
        return raw * (k + 1) / totalWeight;
    }

    private double reciprocalRank(Map<String, Object> candidate, String field,
                                  double weight, int k) {
        if (!(candidate.get(field) instanceof Number rank) || rank.intValue() <= 0) return 0;
        return weight / (k + rank.intValue());
    }

    private String candidateIdentity(Map<String, Object> candidate) {
        if (Boolean.TRUE.equals(candidate.get("structuredQa"))
                && Boolean.TRUE.equals(candidate.get("directAnswerEligible"))
                && !string(candidate.get("qaKey")).isBlank()) {
            return "qa:" + candidate.get("qaKey") + ":" + string(candidate.get("qaVersion"));
        }
        String type = string(candidate.getOrDefault("type", "item"));
        Object id = "chunk".equals(type) ? candidate.get("chunkId") : candidate.get("itemId");
        return type + ":" + string(id);
    }

    private StructuredQaDirectDecision structuredQaDirectDecision(
            String query, List<Map<String, Object>> candidates,
            List<Map<String, Object>> selected) {
        if (!structuredQaEnabled || selected == null || selected.isEmpty()) {
            return StructuredQaDirectDecision.noMatch();
        }
        Map<String, Object> top = selected.get(0);
        if (!Boolean.TRUE.equals(top.get("structuredQa"))
                || !Boolean.TRUE.equals(top.get("directAnswerEligible"))
                || Boolean.TRUE.equals(top.get("qaConflict"))
                || Boolean.TRUE.equals(top.get("topicMismatch"))) {
            return StructuredQaDirectDecision.noMatch();
        }
        String question = structuredQuestion(top);
        String answer = firstNonBlank(string(top.get("fullAnswer")), string(top.get("answer")));
        if (question.isBlank() || answer.isBlank()) return StructuredQaDirectDecision.noMatch();

        boolean exact = StructuredQaUtil.normalizeQuestion(query)
            .equals(StructuredQaUtil.normalizeQuestion(question));
        // Reviewed exact Q&A is deterministic evidence; its vector score is only
        // a recall signal and must not block an approved answer.
        if (exact && (number(top.get("combinedScore")) >= structuredQaExactMinScore
                || isReviewedExactStructuredQa(top))) {
            return new StructuredQaDirectDecision(true, answer, "normalized_exact");
        }
        if (isCompositeQuestion(query)) return StructuredQaDirectDecision.noMatch();

        double topRerankScore = number(top.get("rerankScore"));
        if (!Boolean.TRUE.equals(top.get("reranked"))
                || topRerankScore < structuredQaRerankMinScore) {
            return StructuredQaDirectDecision.noMatch();
        }
        double secondRerankScore = candidates.stream()
            .filter(candidate -> candidate != top)
            .filter(candidate -> Boolean.TRUE.equals(candidate.get("reranked")))
            .mapToDouble(candidate -> number(candidate.get("rerankScore")))
            .max().orElse(0);
        if (topRerankScore - secondRerankScore < structuredQaRerankMinGap) {
            return StructuredQaDirectDecision.noMatch();
        }
        return new StructuredQaDirectDecision(true, answer, "rerank_high_confidence");
    }

    private boolean isReviewedExactStructuredQa(Map<String, Object> candidate) {
        return Boolean.TRUE.equals(candidate.get("structuredQaExactMatch"))
            && Boolean.TRUE.equals(candidate.get("directAnswerEligible"))
            && !Boolean.TRUE.equals(candidate.get("qaConflict"));
    }

    private String structuredQuestion(Map<String, Object> candidate) {
        if (candidate == null) return "";
        return firstNonBlank(string(candidate.get("question")),
            string(candidate.get("qaQuestion")), string(candidate.get("sectionPath")));
    }

    private StructuredTableAnswerResolver.Decision structuredTableDirectDecision(
            String query, List<Map<String, Object>> selected) {
        if (!structuredQaEnabled || selected == null || selected.isEmpty()) return null;
        Map<String, Object> top = selected.get(0);
        if (!Boolean.TRUE.equals(top.get("structuredQa"))
                || Boolean.TRUE.equals(top.get("qaConflict"))
                || Boolean.TRUE.equals(top.get("topicMismatch"))) return null;
        String question = string(top.get("question"));
        String answer = firstNonBlank(string(top.get("fullAnswer")), string(top.get("answer")));
        return StructuredTableAnswerResolver.resolve(query, question, answer).orElse(null);
    }

    private boolean isCompositeQuestion(String query) {
        String normalized = query == null ? "" : query.replaceAll("\\s+", "");
        long questionMarks = normalized.codePoints()
            .filter(value -> value == '?' || value == '？')
            .count();
        return questionMarks >= 2 || containsAny(normalized, List.of(
            "分别", "对比", "区别", "以及", "同时", "还有", "并且", "另外"));
    }

    private Map<String, Object> findFaq(List<Map<String, Object>> candidates, Object itemId) {
        if (itemId == null) return null;
        return candidates.stream().filter(candidate -> "item".equals(candidate.get("type"))
            && itemId.toString().equals(string(candidate.get("itemId"))))
            .findFirst().orElse(null);
    }

    private boolean sameFaq(Map<String, Object> candidate, Map<String, Object> keywordMatch) {
        return keywordMatch != null && keywordMatch.get("itemId") != null
            && keywordMatch.get("itemId").toString().equals(string(candidate.get("itemId")));
    }

    private TopicAlignment topicAlignment(String query, Map<String, Object> candidate) {
        String candidateText = String.join(" ",
            string(candidate.get("title")), string(candidate.get("question")),
            string(candidate.get("answer")), string(candidate.get("content")));
        if (isContractLaunchMethodQuery(query)
                && containsAny(candidateText, SERVICE_MODE_TERMS)
                && !containsAny(candidateText, CONTRACT_LAUNCH_METHOD_TERMS)) {
            return TopicAlignment.mismatch("contract_launch_method_query_vs_service_mode_candidate");
        }

        boolean queryContract = containsAny(query, CONTRACT_TOPIC_TERMS);
        boolean queryMembership = containsAny(query, MEMBERSHIP_TOPIC_TERMS);
        if (queryContract == queryMembership) return TopicAlignment.aligned();

        boolean candidateContract = containsAny(candidateText, CONTRACT_TOPIC_TERMS);
        boolean candidateMembership = containsAny(candidateText, MEMBERSHIP_TOPIC_TERMS);
        if (queryContract && candidateMembership && !candidateContract) {
            return TopicAlignment.mismatch("contract_query_vs_membership_candidate");
        }
        if (queryMembership && candidateContract && !candidateMembership) {
            return TopicAlignment.mismatch("membership_query_vs_contract_candidate");
        }
        return TopicAlignment.aligned();
    }

    private boolean isContractLaunchMethodQuery(String query) {
        if (query == null || query.isBlank()) return false;
        String normalized = query.replaceAll("\\s+", "");
        boolean contractLaunch = normalized.contains("发起合同")
            || normalized.contains("合同发起");
        boolean asksMethod = normalized.contains("方式") || normalized.contains("几种")
            || normalized.contains("哪些") || normalized.contains("哪种");
        return contractLaunch && asksMethod;
    }

    private boolean containsAny(String value, List<String> terms) {
        if (value == null || value.isBlank()) return false;
        return terms.stream().anyMatch(value::contains);
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) result.append(String.format("%02x", b));
            return result.toString();
        } catch (Exception e) {
            log.debug("SHA-256 unavailable, falling back to hashCode");
            return Integer.toHexString(value.hashCode());
        }
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    private static double round6(double value) {
        return Math.round(value * 1_000_000) / 1_000_000.0;
    }

    private static long elapsedNanos(long started) {
        return Math.max(0L, System.nanoTime() - started);
    }

    private static long nanosToMillis(long nanos) {
        return Math.max(0L, nanos / 1_000_000L);
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private record EmbeddingLookup(List<Double> vector, boolean cacheHit) {}

    private static final class RetrievalTimingCollector {
        private final long startedNanos = System.nanoTime();
        private long keywordNanos;
        private long embeddingNanos;
        private long vectorSearchNanos;
        private long sparseSearchNanos;
        private int embeddingCacheHits;
        private int embeddingCacheMisses;

        private Map<String, Object> snapshot(long rerankMs, int candidateCount) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("retrievalTotalMs", nanosToMillis(elapsedNanos(startedNanos)));
            result.put("keywordMatchMs", nanosToMillis(keywordNanos));
            result.put("embeddingMs", nanosToMillis(embeddingNanos));
            result.put("vectorSearchMs", nanosToMillis(vectorSearchNanos));
            result.put("sparseSearchMs", nanosToMillis(sparseSearchNanos));
            result.put("rerankMs", Math.max(0L, rerankMs));
            result.put("embeddingCacheHits", embeddingCacheHits);
            result.put("embeddingCacheMisses", embeddingCacheMisses);
            result.put("candidateCount", Math.max(0, candidateCount));
            return Collections.unmodifiableMap(result);
        }
    }

    public record RetrievalResult(boolean answerable, boolean directAnswer,
                                  String directAnswerText, String context,
                                  double confidence, String decision,
                                  boolean semanticAvailable,
                                  List<Map<String, Object>> citations,
                                  List<Map<String, Object>> candidates,
                                  Map<String, Object> rerankDiagnostics,
                                  Map<String, Object> stageLatencies) {
        public RetrievalResult {
            rerankDiagnostics = rerankDiagnostics == null
                ? defaultRerankDiagnostics()
                : Collections.unmodifiableMap(new LinkedHashMap<>(rerankDiagnostics));
            stageLatencies = stageLatencies == null
                ? defaultStageLatencies()
                : Collections.unmodifiableMap(new LinkedHashMap<>(stageLatencies));
        }

        public RetrievalResult(boolean answerable, boolean directAnswer,
                               String directAnswerText, String context,
                               double confidence, String decision,
                               boolean semanticAvailable,
                               List<Map<String, Object>> citations,
                               List<Map<String, Object>> candidates) {
            this(answerable, directAnswer, directAnswerText, context, confidence, decision,
                semanticAvailable, citations, candidates, defaultRerankDiagnostics(),
                defaultStageLatencies());
        }

        public RetrievalResult(boolean answerable, boolean directAnswer,
                               String directAnswerText, String context,
                               double confidence, String decision,
                               boolean semanticAvailable,
                               List<Map<String, Object>> citations,
                               List<Map<String, Object>> candidates,
                               Map<String, Object> rerankDiagnostics) {
            this(answerable, directAnswer, directAnswerText, context, confidence, decision,
                semanticAvailable, citations, candidates, rerankDiagnostics,
                defaultStageLatencies());
        }

        public RetrievalResult withStageLatencies(Map<String, Object> timings) {
            return new RetrievalResult(answerable, directAnswer, directAnswerText, context,
                confidence, decision, semanticAvailable, citations, candidates,
                rerankDiagnostics, timings);
        }

        public String rerankConfidenceTier() {
            if (candidates == null) return null;
            return candidates.stream()
                .map(candidate -> candidate.get("rerankConfidenceTier"))
                .filter(value -> value != null && !value.toString().isBlank())
                .map(Object::toString)
                .findFirst()
                .orElse(null);
        }

        public List<Map<String, Object>> structuredUnitDiagnostics() {
            if (candidates == null || candidates.isEmpty()) return Collections.emptyList();
            return candidates.stream()
                .filter(candidate -> Boolean.TRUE.equals(candidate.get("diagnosticOnly")))
                .toList();
        }
    }

    private static Map<String, Object> defaultRerankDiagnostics() {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("configured", false);
        diagnostics.put("attempted", false);
        diagnostics.put("applied", false);
        diagnostics.put("failureReason", "not_attempted");
        diagnostics.put("latencyMs", 0L);
        diagnostics.put("scoreSource", "none");
        diagnostics.put("configSource", null);
        return Collections.unmodifiableMap(diagnostics);
    }

    private static Map<String, Object> defaultStageLatencies() {
        Map<String, Object> timings = new LinkedHashMap<>();
        timings.put("retrievalTotalMs", 0L);
        timings.put("keywordMatchMs", 0L);
        timings.put("embeddingMs", 0L);
        timings.put("vectorSearchMs", 0L);
        timings.put("sparseSearchMs", 0L);
        timings.put("rerankMs", 0L);
        timings.put("embeddingCacheHits", 0);
        timings.put("embeddingCacheMisses", 0);
        timings.put("candidateCount", 0);
        return Collections.unmodifiableMap(timings);
    }

    private record StructuredUnitRecall(List<Map<String, Object>> evidenceCandidates,
                                        List<Map<String, Object>> diagnostics) {
        private static StructuredUnitRecall empty() {
            return new StructuredUnitRecall(Collections.emptyList(), Collections.emptyList());
        }
    }

    private record StructuredEvidenceAttribution(String semanticUnitId, double rawScore,
                                                 double weightedScore, int rank) {}

    private record StructuredUnitAttribution(String semanticUnitId, double rawScore,
                                             double weightedScore, int rank,
                                             List<Long> evidenceChunkIds) {}

    private enum RerankConfidenceTier {
        HIGH,
        MEDIUM,
        LOW
    }

    private record RerankConfidenceDecision(boolean applied, RerankConfidenceTier tier,
                                            double topScore) {
        private static RerankConfidenceDecision unavailable() {
            return new RerankConfidenceDecision(false, null, 0);
        }
    }

    private record TopicAlignment(boolean mismatch, double factor, String label) {
        private static TopicAlignment aligned() {
            return new TopicAlignment(false, 1.0, "aligned_or_neutral");
        }

        private static TopicAlignment mismatch(String label) {
            return new TopicAlignment(true, CROSS_TOPIC_SCORE_FACTOR, label);
        }
    }

    private record StructuredQaDirectDecision(boolean direct, String answer, String matchMode) {
        private static StructuredQaDirectDecision noMatch() {
            return new StructuredQaDirectDecision(false, null, null);
        }
    }
}
