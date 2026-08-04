package com.feisheng.bot.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.knowledge.entity.BotKnowledgeChunk;
import com.feisheng.bot.knowledge.entity.BotKnowledgeDocument;
import com.feisheng.bot.knowledge.entity.BotKnowledgeItem;
import com.feisheng.bot.knowledge.entity.BotKnowledgeItemChunk;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeDocumentMapper;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeItemMapper;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeItemChunkMapper;
import com.feisheng.bot.common.util.StructuredQaUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Knowledge vector index backed by Qdrant, with an immutable in-memory fallback.
 */
@Service
public class KnowledgeIndexService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexService.class);
    private static final List<String> LOW_INFORMATION_FAQ_QUESTIONS = List.of(
        "你好", "您好", "hello", "hi");

    private final BotKnowledgeItemMapper itemMapper;
    private final BotKnowledgeItemChunkMapper itemChunkMapper;
    private final BotKnowledgeChunkMapper chunkMapper;
    private final BotKnowledgeDocumentMapper documentMapper;
    private final ObjectMapper objectMapper;
    private final QdrantVectorStore qdrantStore;

    private volatile Snapshot snapshot = Snapshot.empty();
    private volatile SyncReport lastReport = SyncReport.empty();
    private volatile boolean qdrantReady;

    @Value("${rag.index.require-consistent-embedding-version:false}")
    private boolean requireConsistentEmbeddingVersion;

    @Autowired
    public KnowledgeIndexService(BotKnowledgeItemMapper itemMapper,
                                 BotKnowledgeItemChunkMapper itemChunkMapper,
                                 BotKnowledgeChunkMapper chunkMapper,
                                 BotKnowledgeDocumentMapper documentMapper,
                                 ObjectMapper objectMapper,
                                 QdrantVectorStore qdrantStore) {
        this.itemMapper = itemMapper;
        this.itemChunkMapper = itemChunkMapper;
        this.chunkMapper = chunkMapper;
        this.documentMapper = documentMapper;
        this.objectMapper = objectMapper;
        this.qdrantStore = qdrantStore;
    }

    public KnowledgeIndexService(BotKnowledgeItemMapper itemMapper,
                                 BotKnowledgeChunkMapper chunkMapper,
                                 BotKnowledgeDocumentMapper documentMapper,
                                 ObjectMapper objectMapper,
                                 QdrantVectorStore qdrantStore) {
        this(itemMapper, null, chunkMapper, documentMapper, objectMapper, qdrantStore);
    }

    @PostConstruct
    public void initialize() {
        sync();
    }

    @Scheduled(fixedDelayString = "${rag.index.sync-interval-ms:30000}")
    public void scheduledSync() {
        sync();
    }

    public synchronized SyncReport sync() {
        long started = System.currentTimeMillis();
        Snapshot previous = snapshot;
        try {
            Snapshot next = loadSnapshot(previous.version());
            requireConsistentEmbeddingVersion(next);
            Diff diff = diff(previous, next);
            long version = diff.changed() ? previous.version() + 1 : previous.version();
            next = next.withVersion(version);
            snapshot = next;

            QdrantSyncResult qdrant = syncQdrant(next, diff);
            lastReport = new SyncReport(
                true, version, next.faqVectorCount(), next.chunks().size(),
                diff.added(), diff.updated(), diff.removed(),
                qdrant.synced(), qdrant.upserted(), qdrant.deleted(),
                System.currentTimeMillis() - started, Instant.now().toString(),
                null, qdrant.error());
            log.info("Knowledge index sync completed: version={}, items={}, chunks={}, added={}, "
                    + "updated={}, removed={}, qdrantSynced={}, qdrantUpserted={}, qdrantDeleted={}",
                version, next.faqVectorCount(), next.chunks().size(),
                diff.added(), diff.updated(), diff.removed(), qdrant.synced(),
                qdrant.upserted(), qdrant.deleted());
        } catch (Exception e) {
            lastReport = new SyncReport(
                false, previous.version(), previous.faqVectorCount(), previous.chunks().size(),
                0, 0, 0, false, 0, 0,
                System.currentTimeMillis() - started,
                Instant.now().toString(), rootMessage(e), null);
            log.warn("Knowledge index sync failed; keeping version {}: {}",
                previous.version(), rootMessage(e));
        }
        return lastReport;
    }

    public synchronized QdrantReindexReport reindexQdrant() {
        long started = System.currentTimeMillis();
        if (!qdrantStore.isEnabled()) {
            return new QdrantReindexReport(false, snapshot.size(), 0, 0,
                System.currentTimeMillis() - started, Instant.now().toString(),
                "Qdrant is disabled");
        }

        try {
            Snapshot previous = snapshot;
            Snapshot next = loadSnapshot(previous.version());
            requireConsistentEmbeddingVersion(next);
            Diff diff = diff(previous, next);
            long version = diff.changed() ? previous.version() + 1 : previous.version();
            next = next.withVersion(version);
            snapshot = next;

            QdrantVectorStore.ReconcileResult result = qdrantStore.reconcile(toPoints(next));
            qdrantReady = true;
            lastReport = new SyncReport(
                true, version, next.faqVectorCount(), next.chunks().size(),
                diff.added(), diff.updated(), diff.removed(),
                true, result.upserted(), result.deleted(),
                System.currentTimeMillis() - started, Instant.now().toString(), null, null);
            return new QdrantReindexReport(true, next.size(), result.upserted(), result.deleted(),
                System.currentTimeMillis() - started, Instant.now().toString(), null);
        } catch (Exception e) {
            qdrantReady = false;
            log.warn("Qdrant full reindex failed; memory fallback remains active: {}", rootMessage(e));
            return new QdrantReindexReport(false, snapshot.size(), 0, 0,
                System.currentTimeMillis() - started, Instant.now().toString(), rootMessage(e));
        }
    }

    public IndexStatus status() {
        Snapshot current = snapshot;
        return new IndexStatus(
            current.version(), current.faqVectorCount(), current.chunks().size(),
            qdrantReady ? "qdrant" : "memory", qdrantReady,
            current.embeddingConsistency(), qdrantStore.lastKnownStatus(), lastReport);
    }

    public QdrantVectorStore.QdrantStatus qdrantStatus() {
        return qdrantStore.status();
    }

    public List<Map<String, Object>> search(List<Double> queryEmbedding, int topK, double minScore) {
        return search(queryEmbedding, topK, minScore, Collections.emptyMap());
    }

    public List<Map<String, Object>> search(List<Double> queryEmbedding, int topK, double minScore,
                                            Map<String, Object> filters) {
        if (queryEmbedding == null || queryEmbedding.isEmpty() || topK <= 0) {
            return Collections.emptyList();
        }
        Map<String, Object> normalizedFilters = PayloadFilters.normalize(filters);
        if (PayloadFilters.isUnsatisfiable(normalizedFilters)) return Collections.emptyList();

        if (qdrantReady && qdrantStore.isEnabled()) {
            try {
                List<QdrantVectorStore.SearchHit> hits = normalizedFilters.isEmpty()
                    ? qdrantStore.search(queryEmbedding, topK, minScore)
                    : qdrantStore.search(queryEmbedding, topK, minScore, normalizedFilters);
                List<Map<String, Object>> results = new ArrayList<>(hits.size());
                for (QdrantVectorStore.SearchHit hit : hits) {
                    Map<String, Object> match = new LinkedHashMap<>(hit.payload());
                    match.put("similarity", hit.score());
                    results.add(match);
                }
                return results;
            } catch (Exception e) {
                qdrantReady = false;
                log.warn("Qdrant search failed; using memory fallback until the next sync: {}",
                    rootMessage(e));
            }
        }

        return searchMemory(queryEmbedding, topK, minScore, normalizedFilters);
    }

    /**
     * Matches a Chinese query against FAQ questions and document chunks by pinyin.
     * This is a conservative fallback for ASR and input-method homophone errors.
     */
    public List<Map<String, Object>> searchPhonetic(String query, int topK, double minScore) {
        return searchPhonetic(query, topK, minScore, Collections.emptyMap());
    }

    public List<Map<String, Object>> searchPhonetic(String query, int topK, double minScore,
                                                    Map<String, Object> filters) {
        if (query == null || query.isBlank() || topK <= 0) return Collections.emptyList();
        Map<String, Object> normalizedFilters = PayloadFilters.normalize(filters);
        if (PayloadFilters.isUnsatisfiable(normalizedFilters)) return Collections.emptyList();

        Snapshot current = snapshot;
        List<Map<String, Object>> scored = new ArrayList<>();
        for (ItemEntry entry : current.items().values()) {
            Map<String, Object> payload = itemPayload(entry);
            if (PayloadFilters.matches(payload, normalizedFilters)) {
                addPhoneticMatch(scored, payload, query, entry.question(), minScore);
            }
        }
        for (FaqChunkEntry entry : current.faqChunks().values()) {
            Map<String, Object> payload = faqChunkPayload(entry);
            if (PayloadFilters.matches(payload, normalizedFilters)) {
                addPhoneticMatch(scored, payload, query, entry.searchableText(), minScore);
            }
        }
        for (ChunkEntry entry : current.chunks().values()) {
            Map<String, Object> payload = chunkPayload(entry);
            if (PayloadFilters.matches(payload, normalizedFilters)) {
                addPhoneticMatch(scored, payload, query, entry.searchableText(), minScore);
            }
        }

        scored.sort((a, b) -> Double.compare(number(b.get("similarity")),
            number(a.get("similarity"))));
        return scored.size() > topK
            ? new ArrayList<>(scored.subList(0, topK))
            : scored;
    }

    /**
     * Matches normalized query text against FAQ questions and document chunks.
     * This keeps document retrieval available when the query embedding provider is down.
     */
    public List<Map<String, Object>> searchLexical(String query, int topK, double minScore) {
        return searchLexical(query, topK, minScore, Collections.emptyMap());
    }

    public List<Map<String, Object>> searchLexical(String query, int topK, double minScore,
                                                   Map<String, Object> filters) {
        if (query == null || query.isBlank() || topK <= 0) return Collections.emptyList();
        Map<String, Object> normalizedFilters = PayloadFilters.normalize(filters);
        if (PayloadFilters.isUnsatisfiable(normalizedFilters)) return Collections.emptyList();

        Snapshot current = snapshot;
        List<Map<String, Object>> scored = new ArrayList<>();
        for (ItemEntry entry : current.items().values()) {
            Map<String, Object> payload = itemPayload(entry);
            if (PayloadFilters.matches(payload, normalizedFilters)) {
                addLexicalMatch(scored, payload, query, entry.question(), minScore);
            }
        }
        for (FaqChunkEntry entry : current.faqChunks().values()) {
            Map<String, Object> payload = faqChunkPayload(entry);
            if (PayloadFilters.matches(payload, normalizedFilters)) {
                addLexicalMatch(scored, payload, query, entry.searchableText(), minScore);
            }
        }
        for (ChunkEntry entry : current.chunks().values()) {
            Map<String, Object> payload = chunkPayload(entry);
            if (PayloadFilters.matches(payload, normalizedFilters)) {
                addLexicalMatch(scored, payload, query, entry.searchableText(), minScore);
            }
        }

        scored.sort((a, b) -> Double.compare(number(b.get("similarity")),
            number(a.get("similarity"))));
        return scored.size() > topK
            ? new ArrayList<>(scored.subList(0, topK))
            : scored;
    }

    /** Sparse lexical recall for exact terminology, identifiers and long-form documents. */
    public List<Map<String, Object>> searchBm25(String query, int topK, double minScore) {
        return searchBm25(query, topK, minScore, Collections.emptyMap());
    }

    public List<Map<String, Object>> searchBm25(String query, int topK, double minScore,
                                                Map<String, Object> filters) {
        return snapshot.bm25Index().search(query, topK, minScore, filters);
    }

    private void addLexicalMatch(List<Map<String, Object>> matches,
                                 Map<String, Object> payload,
                                 String query,
                                 String candidate,
                                 double minScore) {
        double score = LexicalSimilarity.bestSubstringScore(query, candidate);
        if (score < minScore) return;
        payload.put("similarity", score);
        payload.put("lexicalScore", score);
        payload.put("matchMode", "lexical");
        matches.add(payload);
    }

    private void addPhoneticMatch(List<Map<String, Object>> matches,
                                  Map<String, Object> payload,
                                  String query,
                                  String candidate,
                                  double minScore) {
        double score = PhoneticSimilarity.bestSubstringScore(query, candidate);
        if (score < minScore) return;
        payload.put("similarity", score);
        payload.put("phoneticScore", score);
        payload.put("matchMode", "phonetic");
        matches.add(payload);
    }

    private QdrantSyncResult syncQdrant(Snapshot next, Diff diff) {
        if (!qdrantStore.isEnabled()) return QdrantSyncResult.disabled();
        try {
            QdrantVectorStore.ReconcileResult result;
            if (!qdrantReady) {
                result = qdrantStore.reconcile(toPoints(next));
            } else {
                List<String> deletedPointIds = diff.removedKeys().stream()
                    .map(QdrantVectorStore::pointId)
                    .toList();
                result = qdrantStore.applyChanges(
                    toPoints(next, diff.changedKeys()), deletedPointIds, toPoints(next));
            }
            qdrantReady = true;
            return new QdrantSyncResult(true, result.upserted(), result.deleted(), null);
        } catch (Exception e) {
            qdrantReady = false;
            String error = rootMessage(e);
            log.warn("Qdrant sync failed; memory fallback remains active: {}", error);
            return new QdrantSyncResult(false, 0, 0, error);
        }
    }

    private List<Map<String, Object>> searchMemory(List<Double> queryEmbedding, int topK,
                                                   double minScore,
                                                   Map<String, Object> filters) {
        Snapshot current = snapshot;
        List<Map<String, Object>> scored = new ArrayList<>();
        for (ItemEntry entry : current.items().values()) {
            Map<String, Object> match = itemPayload(entry);
            if (!PayloadFilters.matches(match, filters)) continue;
            double similarity = cosineSimilarity(queryEmbedding, entry.vector());
            if (similarity < minScore) continue;
            match.put("similarity", similarity);
            scored.add(match);
        }

        for (FaqChunkEntry entry : current.faqChunks().values()) {
            Map<String, Object> match = faqChunkPayload(entry);
            if (!PayloadFilters.matches(match, filters)) continue;
            double similarity = cosineSimilarity(queryEmbedding, entry.vector());
            if (similarity < minScore) continue;
            match.put("similarity", similarity);
            scored.add(match);
        }

        for (ChunkEntry entry : current.chunks().values()) {
            Map<String, Object> match = chunkPayload(entry);
            if (!PayloadFilters.matches(match, filters)) continue;
            double similarity = cosineSimilarity(queryEmbedding, entry.vector());
            if (similarity < minScore) continue;
            match.put("similarity", similarity);
            scored.add(match);
        }

        scored.sort((a, b) -> Double.compare(number(b.get("similarity")), number(a.get("similarity"))));
        return scored.size() > topK
            ? new ArrayList<>(scored.subList(0, topK))
            : scored;
    }

    private Snapshot loadSnapshot(long version) {
        List<BotKnowledgeItem> items = itemMapper.selectList(
            new LambdaQueryWrapper<BotKnowledgeItem>()
                .eq(BotKnowledgeItem::getStatus, 1)
                .isNotNull(BotKnowledgeItem::getEmbedding));
        List<BotKnowledgeChunk> chunks = chunkMapper.selectList(
            new LambdaQueryWrapper<BotKnowledgeChunk>()
                .eq(BotKnowledgeChunk::getStatus, "APPROVED")
                .isNotNull(BotKnowledgeChunk::getEmbedding));
        List<BotKnowledgeChunk> qaChunks = chunkMapper.selectList(
            new LambdaQueryWrapper<BotKnowledgeChunk>()
                .eq(BotKnowledgeChunk::getContentType, "QA"));
        Map<Long, QaDirectState> qaDirectStates = qaDirectStates(qaChunks);
        List<BotKnowledgeItemChunk> faqChunks = itemChunkMapper == null
            ? Collections.emptyList()
            : itemChunkMapper.selectList(new LambdaQueryWrapper<BotKnowledgeItemChunk>()
                .isNotNull(BotKnowledgeItemChunk::getEmbedding));
        List<BotKnowledgeDocument> documents = documentMapper.selectList(null);

        Map<Long, DocumentMeta> documentMetadata = new HashMap<>();
        for (BotKnowledgeDocument document : documents) {
            documentMetadata.put(document.getId(), new DocumentMeta(
                firstNonBlank(document.getTitle(), document.getFileName(),
                    "\u6587\u6863 " + document.getId()),
                firstNonBlank(document.getMediaType(), "DOCUMENT"),
                document.getCategoryId(), nullToEmpty(document.getSourceScope()),
                document.getExpiresAt() == null
                    ? "" : document.getExpiresAt().toInstant().toString()));
        }

        Map<Long, ItemEntry> itemEntries = new HashMap<>();
        for (BotKnowledgeItem item : items) {
            if (!shouldIndexFaq(item)) continue;
            List<Double> vector = parseVector(item.getEmbedding());
            if (!vector.isEmpty()) {
                itemEntries.put(item.getId(), new ItemEntry(
                    item.getId(), item.getCategoryId(), nullToEmpty(item.getQuestion()),
                    nullToEmpty(item.getAnswer()), nullToEmpty(item.getKeywords()), vector,
                    nullToEmpty(item.getEmbeddingModel()), nullToEmpty(item.getEmbeddingVersion()),
                    item.getEmbeddingDimensions(), nullToEmpty(item.getEmbeddingContentHash())));
            }
        }

        Map<Long, ChunkEntry> chunkEntries = new HashMap<>();
        for (BotKnowledgeChunk chunk : chunks) {
            List<Double> vector = parseVector(chunk.getEmbedding());
            if (!vector.isEmpty()) {
                DocumentMeta metadata = documentMetadata.getOrDefault(chunk.getDocumentId(),
                    new DocumentMeta("\u6587\u6863 " + chunk.getDocumentId(), "DOCUMENT",
                        null, "", ""));
                QaDirectState qa = qaDirectStates.getOrDefault(chunk.getId(),
                    QaDirectState.from(chunk));
                chunkEntries.put(chunk.getId(), new ChunkEntry(
                    chunk.getId(), chunk.getDocumentId(), chunk.getChunkIndex(),
                    metadata.title(), metadata.mediaType(), metadata.categoryId(),
                    metadata.sourceScope(), metadata.expiresAt(),
                    nullToEmpty(chunk.getContent()), nullToEmpty(chunk.getSectionPath()),
                    chunk.getCharCount(), nullToEmpty(chunk.getChunkStrategyVersion()),
                    qa.structuredQa(), qa.question(), qa.answer(), qa.qaKey(), qa.groupKey(),
                    qa.version(),
                    qa.directAnswerEnabled(), qa.directAnswerEligible(), qa.conflict(),
                    qa.status(), vector,
                    nullToEmpty(chunk.getEmbeddingModel()), nullToEmpty(chunk.getEmbeddingVersion()),
                    chunk.getEmbeddingDimensions(), nullToEmpty(chunk.getEmbeddingContentHash())));
            }
        }

        Map<Long, FaqChunkEntry> faqChunkEntries = new HashMap<>();
        for (BotKnowledgeItemChunk chunk : faqChunks) {
            ItemEntry parent = itemEntries.get(chunk.getItemId());
            if (parent == null) continue;
            List<Double> vector = parseVector(chunk.getEmbedding());
            if (!vector.isEmpty()) {
                faqChunkEntries.put(chunk.getId(), new FaqChunkEntry(
                    chunk.getId(), chunk.getItemId(), chunk.getChunkIndex(),
                    parent.categoryId(), parent.question(), parent.keywords(),
                    nullToEmpty(chunk.getContent()), vector,
                    nullToEmpty(chunk.getEmbeddingModel()), nullToEmpty(chunk.getEmbeddingVersion()),
                    chunk.getEmbeddingDimensions(), nullToEmpty(chunk.getEmbeddingContentHash())));
            }
        }

        Map<Long, ItemEntry> immutableItems = Map.copyOf(itemEntries);
        Map<Long, FaqChunkEntry> immutableFaqChunks = Map.copyOf(faqChunkEntries);
        Map<Long, ChunkEntry> immutableChunks = Map.copyOf(chunkEntries);
        return new Snapshot(version, immutableItems, immutableFaqChunks, immutableChunks,
            buildBm25Index(immutableItems, immutableFaqChunks, immutableChunks),
            embeddingConsistency(immutableItems, immutableFaqChunks, immutableChunks));
    }

    private void requireConsistentEmbeddingVersion(Snapshot value) {
        if (requireConsistentEmbeddingVersion && !value.embeddingConsistency().consistent()) {
            throw new IllegalStateException("Knowledge embeddings contain mixed model versions: "
                + value.embeddingConsistency().versions());
        }
    }

    private EmbeddingConsistency embeddingConsistency(Map<Long, ItemEntry> items,
                                                       Map<Long, FaqChunkEntry> faqChunks,
                                                       Map<Long, ChunkEntry> chunks) {
        Set<String> versions = new TreeSet<>();
        int legacy = 0;
        for (ItemEntry item : items.values()) {
            if (item.embeddingVersion().isBlank()) legacy++;
            else versions.add(item.embeddingVersion());
        }
        for (FaqChunkEntry chunk : faqChunks.values()) {
            if (chunk.embeddingVersion().isBlank()) legacy++;
            else versions.add(chunk.embeddingVersion());
        }
        for (ChunkEntry chunk : chunks.values()) {
            if (chunk.embeddingVersion().isBlank()) legacy++;
            else versions.add(chunk.embeddingVersion());
        }
        boolean consistent = versions.size() <= 1 && (legacy == 0 || versions.isEmpty());
        return new EmbeddingConsistency(consistent, List.copyOf(versions), legacy,
            items.size() + faqChunks.size() + chunks.size() - legacy);
    }

    private Bm25SearchIndex buildBm25Index(Map<Long, ItemEntry> items,
                                             Map<Long, FaqChunkEntry> faqChunks,
                                             Map<Long, ChunkEntry> chunks) {
        List<Bm25SearchIndex.SourceDocument> documents = new ArrayList<>(
            items.size() + faqChunks.size() + chunks.size());
        items.values().forEach(entry -> documents.add(new Bm25SearchIndex.SourceDocument(
            itemPayload(entry), String.join("\n", entry.question(), entry.question(),
                entry.keywords(), entry.answer()))));
        faqChunks.values().forEach(entry -> documents.add(new Bm25SearchIndex.SourceDocument(
            faqChunkPayload(entry), entry.searchableText())));
        chunks.values().forEach(entry -> documents.add(new Bm25SearchIndex.SourceDocument(
            chunkPayload(entry), entry.documentTitle() + "\n" + entry.searchableText())));
        return Bm25SearchIndex.build(documents);
    }

    private boolean shouldIndexFaq(BotKnowledgeItem item) {
        if (item == null || item.getQuestion() == null || item.getQuestion().isBlank()) return false;
        String normalized = item.getQuestion().replaceAll("[\\p{P}\\p{S}\\s]+", "")
            .toLowerCase();
        return !LOW_INFORMATION_FAQ_QUESTIONS.contains(normalized);
    }

    private List<QdrantVectorStore.VectorPoint> toPoints(Snapshot value) {
        List<QdrantVectorStore.VectorPoint> points = new ArrayList<>(value.size());
        value.items().values().forEach(entry -> points.add(new QdrantVectorStore.VectorPoint(
            QdrantVectorStore.pointId("item:" + entry.id()), entry.vector(), itemPayload(entry))));
        value.faqChunks().values().forEach(entry -> points.add(new QdrantVectorStore.VectorPoint(
            QdrantVectorStore.pointId("faq-chunk:" + entry.id()), entry.vector(),
            faqChunkPayload(entry))));
        value.chunks().values().forEach(entry -> points.add(new QdrantVectorStore.VectorPoint(
            QdrantVectorStore.pointId("chunk:" + entry.id()), entry.vector(), chunkPayload(entry))));
        return points;
    }

    private List<QdrantVectorStore.VectorPoint> toPoints(Snapshot value, List<String> keys) {
        List<QdrantVectorStore.VectorPoint> points = new ArrayList<>(keys.size());
        for (String key : keys) {
            if (key.startsWith("item:")) {
                Long id = Long.valueOf(key.substring("item:".length()));
                ItemEntry entry = value.items().get(id);
                if (entry != null) {
                    points.add(new QdrantVectorStore.VectorPoint(
                        QdrantVectorStore.pointId(key), entry.vector(), itemPayload(entry)));
                }
            } else if (key.startsWith("faq-chunk:")) {
                Long id = Long.valueOf(key.substring("faq-chunk:".length()));
                FaqChunkEntry entry = value.faqChunks().get(id);
                if (entry != null) {
                    points.add(new QdrantVectorStore.VectorPoint(
                        QdrantVectorStore.pointId(key), entry.vector(), faqChunkPayload(entry)));
                }
            } else if (key.startsWith("chunk:")) {
                Long id = Long.valueOf(key.substring("chunk:".length()));
                ChunkEntry entry = value.chunks().get(id);
                if (entry != null) {
                    points.add(new QdrantVectorStore.VectorPoint(
                        QdrantVectorStore.pointId(key), entry.vector(), chunkPayload(entry)));
                }
            }
        }
        return points;
    }

    private Map<String, Object> itemPayload(ItemEntry entry) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "item");
        payload.put("sourceType", "faq");
        payload.put("sourceId", entry.id());
        payload.put("itemId", entry.id());
        if (entry.categoryId() != null) payload.put("categoryId", entry.categoryId());
        payload.put("title", entry.question());
        payload.put("question", entry.question());
        payload.put("answer", entry.answer());
        payload.put("keywords", entry.keywords());
        addEmbeddingMetadata(payload, entry.embeddingModel(), entry.embeddingVersion(),
            entry.embeddingDimensions(), entry.embeddingContentHash());
        payload.put("content", entry.answer());
        return payload;
    }

    private Map<String, Object> faqChunkPayload(FaqChunkEntry entry) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "item");
        payload.put("sourceType", "faq");
        payload.put("sourceId", entry.itemId());
        payload.put("itemId", entry.itemId());
        if (entry.categoryId() != null) payload.put("categoryId", entry.categoryId());
        payload.put("faqChunkId", entry.id());
        payload.put("chunkIndex", entry.chunkIndex());
        payload.put("title", entry.question());
        payload.put("question", entry.question());
        payload.put("answer", entry.content());
        payload.put("content", entry.content());
        payload.put("keywords", entry.keywords());
        addEmbeddingMetadata(payload, entry.embeddingModel(), entry.embeddingVersion(),
            entry.embeddingDimensions(), entry.embeddingContentHash());
        return payload;
    }

    private Map<String, Object> chunkPayload(ChunkEntry entry) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "chunk");
        payload.put("sourceType", "IMAGE".equals(entry.mediaType()) ? "image" : "document");
        payload.put("sourceId", entry.id());
        payload.put("chunkId", entry.id());
        payload.put("documentId", entry.documentId());
        payload.put("chunkIndex", entry.chunkIndex());
        payload.put("title", entry.documentTitle());
        payload.put("mediaType", entry.mediaType());
        if (entry.categoryId() != null) payload.put("categoryId", entry.categoryId());
        if (!entry.sourceScope().isBlank()) payload.put("sourceScope", entry.sourceScope());
        if (!entry.expiresAt().isBlank()) payload.put("expiresAt", entry.expiresAt());
        payload.put("sectionPath", entry.sectionPath());
        if (entry.charCount() != null) payload.put("charCount", entry.charCount());
        if (!entry.chunkStrategyVersion().isBlank()) {
            payload.put("chunkStrategyVersion", entry.chunkStrategyVersion());
        }
        if ("IMAGE".equals(entry.mediaType())) {
            payload.put("previewUrl", "/api/admin/doc/" + entry.documentId() + "/preview");
        }
        payload.put("question", entry.structuredQa()
            ? entry.qaQuestion() : truncate(entry.content(), 100));
        payload.put("answer", entry.structuredQa() ? entry.qaAnswer() : entry.content());
        payload.put("content", entry.content());
        if (entry.structuredQa()) {
            payload.put("structuredQa", true);
            payload.put("knowledgeType", "structured_qa");
            payload.put("qaKey", entry.qaKey());
            payload.put("qaGroupKey", entry.qaGroupKey());
            payload.put("qaVersion", entry.qaVersion());
            payload.put("directAnswerEnabled", entry.directAnswerEnabled());
            payload.put("directAnswerEligible", entry.directAnswerEligible());
            payload.put("qaConflict", entry.qaConflict());
            payload.put("qaDirectStatus", entry.qaDirectStatus());
            payload.put("fullAnswer", entry.qaAnswer());
        }
        addEmbeddingMetadata(payload, entry.embeddingModel(), entry.embeddingVersion(),
            entry.embeddingDimensions(), entry.embeddingContentHash());
        return payload;
    }

    private Map<Long, QaDirectState> qaDirectStates(List<BotKnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return Collections.emptyMap();

        Map<Long, QaDirectState> result = new HashMap<>();
        Map<String, List<BotKnowledgeChunk>> sourceGroups = new HashMap<>();
        for (BotKnowledgeChunk chunk : chunks) {
            if (!isStructuredQa(chunk)) continue;
            QaDirectState base = QaDirectState.from(chunk);
            result.put(chunk.getId(), base);
            sourceGroups.computeIfAbsent(qaSourceIdentity(chunk), ignored -> new ArrayList<>())
                .add(chunk);
        }

        Map<String, List<QaSourceGroup>> readyByQuestion = new HashMap<>();
        for (List<BotKnowledgeChunk> group : sourceGroups.values()) {
            QaSourceGroup ready = readyQaGroup(group);
            if (ready != null) {
                readyByQuestion.computeIfAbsent(ready.qaKey(), ignored -> new ArrayList<>())
                    .add(ready);
            }
        }

        for (List<QaSourceGroup> groups : readyByQuestion.values()) {
            int currentVersion = groups.stream().mapToInt(QaSourceGroup::version).max().orElse(1);
            List<QaSourceGroup> current = groups.stream()
                .filter(group -> group.version() == currentVersion).toList();
            boolean conflict = current.stream().map(QaSourceGroup::answerFingerprint)
                .distinct().count() > 1;
            for (QaSourceGroup group : groups) {
                String status = group.version() < currentVersion ? "superseded"
                    : conflict ? "conflict" : "eligible";
                boolean eligible = group.version() == currentVersion && !conflict;
                for (BotKnowledgeChunk chunk : group.chunks()) {
                    result.put(chunk.getId(), new QaDirectState(
                        true, group.question(), group.answer(), group.qaKey(), group.groupKey(),
                        group.version(),
                        true, eligible, conflict, status));
                }
            }
        }
        return Map.copyOf(result);
    }

    private QaSourceGroup readyQaGroup(List<BotKnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty() || chunks.stream().anyMatch(chunk ->
                !"APPROVED".equals(chunk.getStatus())
                    || !Integer.valueOf(1).equals(chunk.getDirectAnswerEnabled())
                    || !isStructuredQa(chunk))) return null;

        Set<String> questionKeys = new TreeSet<>();
        Set<String> answerFingerprints = new TreeSet<>();
        Set<Integer> versions = new TreeSet<>();
        for (BotKnowledgeChunk chunk : chunks) {
            questionKeys.add(StructuredQaUtil.canonicalKey(chunk.getQaQuestion()));
            answerFingerprints.add(StructuredQaUtil.answerFingerprint(chunk.getQaAnswer()));
            versions.add(qaVersion(chunk));
        }
        if (questionKeys.size() != 1 || questionKeys.iterator().next().isBlank()
                || answerFingerprints.size() != 1
                || answerFingerprints.iterator().next().isBlank()
                || versions.size() != 1) return null;

        BotKnowledgeChunk first = chunks.get(0);
        return new QaSourceGroup(questionKeys.iterator().next(), sourceGroupKey(first),
            qaVersion(first),
            first.getQaQuestion().trim(), first.getQaAnswer().trim(),
            answerFingerprints.iterator().next(), List.copyOf(chunks));
    }

    private boolean isStructuredQa(BotKnowledgeChunk chunk) {
        return chunk != null && "QA".equals(chunk.getContentType())
            && chunk.getQaQuestion() != null && !chunk.getQaQuestion().isBlank()
            && chunk.getQaAnswer() != null && !chunk.getQaAnswer().isBlank();
    }

    private String qaSourceIdentity(BotKnowledgeChunk chunk) {
        return chunk.getDocumentId() + ":"
            + sourceGroupKey(chunk) + ":" + qaVersion(chunk);
    }

    private String sourceGroupKey(BotKnowledgeChunk chunk) {
        String stored = chunk.getQaGroupKey();
        if (stored != null && !stored.isBlank()) return stored;
        return StructuredQaUtil.sourceGroupKey(chunk.getQaQuestion(), chunk.getQaAnswer());
    }

    private int qaVersion(BotKnowledgeChunk chunk) {
        return chunk.getQaVersion() == null || chunk.getQaVersion() < 1
            ? 1 : chunk.getQaVersion();
    }

    private void addEmbeddingMetadata(Map<String, Object> payload, String model, String version,
                                      Integer dimensions, String contentHash) {
        if (!model.isBlank()) payload.put("embeddingModel", model);
        if (!version.isBlank()) payload.put("embeddingVersion", version);
        if (dimensions != null) payload.put("embeddingDimensions", dimensions);
        if (!contentHash.isBlank()) payload.put("embeddingContentHash", contentHash);
    }

    private List<Double> parseVector(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return List.copyOf(objectMapper.readValue(json, new TypeReference<List<Double>>() {}));
        } catch (Exception e) {
            log.debug("Ignoring invalid vector during index sync: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private Diff diff(Snapshot previous, Snapshot next) {
        Map<String, Object> oldEntries = flatten(previous);
        Map<String, Object> newEntries = flatten(next);
        int added = 0;
        int updated = 0;
        int removed = 0;
        List<String> changedKeys = new ArrayList<>();
        List<String> removedKeys = new ArrayList<>();
        for (Map.Entry<String, Object> entry : newEntries.entrySet()) {
            if (!oldEntries.containsKey(entry.getKey())) {
                added++;
                changedKeys.add(entry.getKey());
            } else if (!Objects.equals(oldEntries.get(entry.getKey()), entry.getValue())) {
                updated++;
                changedKeys.add(entry.getKey());
            }
        }
        for (String key : oldEntries.keySet()) {
            if (!newEntries.containsKey(key)) {
                removed++;
                removedKeys.add(key);
            }
        }
        Collections.sort(changedKeys);
        Collections.sort(removedKeys);
        return new Diff(added, updated, removed, List.copyOf(changedKeys), List.copyOf(removedKeys));
    }

    private Map<String, Object> flatten(Snapshot value) {
        Map<String, Object> entries = new HashMap<>();
        value.items().forEach((id, entry) -> entries.put("item:" + id, entry));
        value.faqChunks().forEach((id, entry) -> entries.put("faq-chunk:" + id, entry));
        value.chunks().forEach((id, entry) -> entries.put("chunk:" + id, entry));
        return entries;
    }

    private double cosineSimilarity(List<Double> a, List<Double> b) {
        if (a.size() != b.size() || a.isEmpty()) return 0;
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.size(); i++) {
            double av = a.get(i);
            double bv = b.get(i);
            dot += av * bv;
            normA += av * av;
            normB += bv * bv;
        }
        return normA == 0 || normB == 0 ? 0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record Snapshot(long version, Map<Long, ItemEntry> items,
                            Map<Long, FaqChunkEntry> faqChunks,
                            Map<Long, ChunkEntry> chunks,
                            Bm25SearchIndex bm25Index,
                            EmbeddingConsistency embeddingConsistency) {
        private static Snapshot empty() {
            return new Snapshot(0, Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(),
                Bm25SearchIndex.empty(), new EmbeddingConsistency(true,
                    Collections.emptyList(), 0, 0));
        }

        private Snapshot withVersion(long value) {
            return new Snapshot(value, items, faqChunks, chunks, bm25Index, embeddingConsistency);
        }

        private int size() {
            return items.size() + faqChunks.size() + chunks.size();
        }

        private int faqVectorCount() {
            return items.size() + faqChunks.size();
        }
    }

    private record ItemEntry(Long id, Long categoryId, String question, String answer, String keywords,
                             List<Double> vector, String embeddingModel, String embeddingVersion,
                               Integer embeddingDimensions, String embeddingContentHash) {}

    private record FaqChunkEntry(Long id, Long itemId, Integer chunkIndex,
                                  Long categoryId, String question, String keywords, String content,
                                  List<Double> vector, String embeddingModel,
                                 String embeddingVersion, Integer embeddingDimensions,
                                 String embeddingContentHash) {
        private String searchableText() {
            return String.join("\n", question, keywords, content);
        }
    }

    private record DocumentMeta(String title, String mediaType, Long categoryId,
                                String sourceScope, String expiresAt) {}

    private record ChunkEntry(Long id, Long documentId, Integer chunkIndex,
                               String documentTitle, String mediaType, Long categoryId,
                               String sourceScope, String expiresAt,
                               String content, String sectionPath, Integer charCount,
                               String chunkStrategyVersion, boolean structuredQa,
                               String qaQuestion, String qaAnswer, String qaKey, String qaGroupKey,
                               int qaVersion, boolean directAnswerEnabled,
                               boolean directAnswerEligible, boolean qaConflict,
                               String qaDirectStatus, List<Double> vector,
                               String embeddingModel, String embeddingVersion,
                               Integer embeddingDimensions, String embeddingContentHash) {
        private String searchableText() {
            if (structuredQa) return String.join("\n", qaQuestion, content);
            return sectionPath.isBlank() ? content : sectionPath + "\n" + content;
        }
    }

    private record QaSourceGroup(String qaKey, String groupKey, int version, String question,
                                 String answer, String answerFingerprint,
                                 List<BotKnowledgeChunk> chunks) {}

    private record QaDirectState(boolean structuredQa, String question, String answer,
                                 String qaKey, String groupKey, int version,
                                 boolean directAnswerEnabled,
                                 boolean directAnswerEligible, boolean conflict, String status) {
        private static QaDirectState from(BotKnowledgeChunk chunk) {
            boolean structured = chunk != null && "QA".equals(chunk.getContentType())
                && chunk.getQaQuestion() != null && !chunk.getQaQuestion().isBlank()
                && chunk.getQaAnswer() != null && !chunk.getQaAnswer().isBlank();
            boolean enabled = structured
                && Integer.valueOf(1).equals(chunk.getDirectAnswerEnabled());
            String status = !enabled ? "disabled"
                : !"APPROVED".equals(chunk.getStatus()) ? "not_approved" : "incomplete_group";
            return new QaDirectState(structured,
                structured ? chunk.getQaQuestion().trim() : "",
                structured ? chunk.getQaAnswer().trim() : "",
                structured ? StructuredQaUtil.canonicalKey(chunk.getQaQuestion()) : "",
                structured ? StructuredQaUtil.sourceGroupKey(
                    chunk.getQaQuestion(), chunk.getQaAnswer()) : "",
                chunk == null || chunk.getQaVersion() == null || chunk.getQaVersion() < 1
                    ? 1 : chunk.getQaVersion(),
                enabled, false, false, status);
        }
    }

    private record Diff(int added, int updated, int removed,
                        List<String> changedKeys, List<String> removedKeys) {
        private boolean changed() {
            return added > 0 || updated > 0 || removed > 0;
        }
    }

    private record QdrantSyncResult(boolean synced, int upserted, int deleted, String error) {
        private static QdrantSyncResult disabled() {
            return new QdrantSyncResult(false, 0, 0, null);
        }
    }

    public record IndexStatus(long version, int faqVectors, int chunkVectors,
                              String searchBackend, boolean qdrantReady,
                              EmbeddingConsistency embeddingConsistency,
                              QdrantVectorStore.QdrantStatus qdrant,
                              SyncReport lastSync) {}

    public record EmbeddingConsistency(boolean consistent, List<String> versions,
                                       int legacyVectors, int versionedVectors) {}

    public record SyncReport(boolean success, long version, int faqVectors, int chunkVectors,
                             int added, int updated, int removed,
                             boolean qdrantSynced, int qdrantUpserted, int qdrantDeleted,
                             long durationMs, String syncedAt, String error, String qdrantError) {
        private static SyncReport empty() {
            return new SyncReport(true, 0, 0, 0, 0, 0, 0,
                false, 0, 0, 0, null, null, null);
        }
    }

    public record QdrantReindexReport(boolean success, int expectedPoints,
                                      int upserted, int deleted, long durationMs,
                                      String completedAt, String error) {}
}
