package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.feisheng.bot.admin.entity.BotFaqDraft;
import com.feisheng.bot.admin.entity.BotUnmatchedQuestion;
import com.feisheng.bot.admin.entity.BotQuestionCluster;
import com.feisheng.bot.admin.entity.BotQuestionClusterItem;
import com.feisheng.bot.admin.entity.BotQuestionClusterRun;
import com.feisheng.bot.admin.mapper.BotFaqDraftMapper;
import com.feisheng.bot.admin.mapper.BotQuestionClusterItemMapper;
import com.feisheng.bot.admin.mapper.BotQuestionClusterMapper;
import com.feisheng.bot.admin.mapper.BotQuestionClusterRunMapper;
import com.feisheng.bot.admin.mapper.BotUnmatchedQuestionMapper;
import com.feisheng.bot.admin.util.VectorUtil;
import com.feisheng.bot.core.service.TextCorrectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Produces reviewable clusters from unmatched questions. Persisted results are
 * review snapshots and never participate in the online answer flow.
 */
@Service
public class QuestionClusteringService {
    private static final Logger log = LoggerFactory.getLogger(QuestionClusteringService.class);
    private static final int DEFAULT_LIMIT = 500;
    private static final double DEFAULT_THRESHOLD = 0.82d;
    private static final int DEFAULT_MIN_CLUSTER_SIZE = 2;
    private static final int MAX_LIMIT = 1000;
    private static final int MAX_NOISE_SAMPLES = 50;
    private static final Pattern PUNCTUATION = Pattern.compile("[\\p{P}\\p{S}\\s]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final List<Map.Entry<String, String>> SYNONYMS = List.of(
        Map.entry("公司认证", "企业认证"),
        Map.entry("企业认证流程", "企业认证"),
        Map.entry("如何", "怎么"),
        Map.entry("怎样", "怎么"),
        Map.entry("签约", "签署"),
        Map.entry("签字", "签署"),
        Map.entry("费用", "价格"),
        Map.entry("收费", "价格"),
        Map.entry("多少钱", "价格"),
        Map.entry("创建合同", "发起合同"),
        Map.entry("新建合同", "发起合同"));
    private static final List<String> FILLER_PREFIXES = List.of(
        "请问", "您好", "你好", "麻烦", "我想问一下", "我想了解一下", "想问一下");

    private final BotUnmatchedQuestionMapper unmatchedQuestionMapper;
    private final EmbeddingService embeddingService;
    private final BotQuestionClusterRunMapper clusterRunMapper;
    private final BotQuestionClusterMapper clusterMapper;
    private final BotQuestionClusterItemMapper clusterItemMapper;
    private final BotFaqDraftMapper faqDraftMapper;
    private final TextCorrectionService correctionService = new TextCorrectionService();

    @Autowired
    public QuestionClusteringService(BotUnmatchedQuestionMapper unmatchedQuestionMapper,
                                      EmbeddingService embeddingService,
                                      BotQuestionClusterRunMapper clusterRunMapper,
                                      BotQuestionClusterMapper clusterMapper,
                                      BotQuestionClusterItemMapper clusterItemMapper,
                                      BotFaqDraftMapper faqDraftMapper) {
        this.unmatchedQuestionMapper = unmatchedQuestionMapper;
        this.embeddingService = embeddingService;
        this.clusterRunMapper = clusterRunMapper;
        this.clusterMapper = clusterMapper;
        this.clusterItemMapper = clusterItemMapper;
        this.faqDraftMapper = faqDraftMapper;
    }

    /** Kept for the algorithm unit tests and callers that only need a preview. */
    public QuestionClusteringService(BotUnmatchedQuestionMapper unmatchedQuestionMapper,
                                     EmbeddingService embeddingService) {
        this(unmatchedQuestionMapper, embeddingService, null, null, null, null);
    }

    /** Kept for service unit tests and callers that do not use FAQ drafts. */
    public QuestionClusteringService(BotUnmatchedQuestionMapper unmatchedQuestionMapper,
                                     EmbeddingService embeddingService,
                                     BotQuestionClusterRunMapper clusterRunMapper,
                                     BotQuestionClusterMapper clusterMapper,
                                     BotQuestionClusterItemMapper clusterItemMapper) {
        this(unmatchedQuestionMapper, embeddingService, clusterRunMapper, clusterMapper,
            clusterItemMapper, null);
    }

    public ClusterResult cluster(boolean includeResolved, int requestedLimit,
                                 double requestedThreshold, int requestedMinClusterSize) {
        int limit = clamp(requestedLimit, 1, MAX_LIMIT, DEFAULT_LIMIT);
        double threshold = clamp(requestedThreshold, 0.60d, 0.99d, DEFAULT_THRESHOLD);
        int minClusterSize = clamp(requestedMinClusterSize, 2, 20, DEFAULT_MIN_CLUSTER_SIZE);

        LambdaQueryWrapper<BotUnmatchedQuestion> query = new LambdaQueryWrapper<BotUnmatchedQuestion>()
            .orderByDesc(BotUnmatchedQuestion::getSimilarCount)
            .orderByDesc(BotUnmatchedQuestion::getCreateTime)
            .last("LIMIT " + limit);
        if (!includeResolved) query.eq(BotUnmatchedQuestion::getIsResolved, 0);

        List<BotUnmatchedQuestion> rows = unmatchedQuestionMapper.selectList(query);
        List<Candidate> candidates = deduplicate(rows);
        if (candidates.isEmpty()) {
            return new ClusterResult(0, 0, 0, false, "", "", threshold,
                List.of(), List.of());
        }

        EmbeddingInfo embeddingInfo = generateEmbeddings(candidates);
        boolean embeddingUsed = embeddingInfo.used;
        // Keep the requested threshold in lexical fallback mode as well. A
        // lower fallback threshold would merge short questions that only
        // share a common phrase such as "怎么".
        double thresholdUsed = threshold;
        List<MutableCluster> clusters = new ArrayList<>();
        List<Candidate> noise = new ArrayList<>();

        for (Candidate candidate : candidates) {
            MutableCluster bestCluster = null;
            double bestScore = 0d;
            for (MutableCluster cluster : clusters) {
                double score = cluster.maxSimilarity(candidate);
                if (score > bestScore) {
                    bestScore = score;
                    bestCluster = cluster;
                }
            }
            if (bestCluster != null && bestScore >= thresholdUsed) {
                bestCluster.add(candidate);
            } else {
                MutableCluster newCluster = new MutableCluster(clusters.size() + 1);
                newCluster.add(candidate);
                clusters.add(newCluster);
            }
        }

        List<Cluster> resultClusters = new ArrayList<>();
        for (MutableCluster cluster : clusters) {
            if (cluster.variantCount() < minClusterSize) {
                noise.addAll(cluster.members);
                continue;
            }
            resultClusters.add(cluster.toResult());
        }
        resultClusters.sort(Comparator.comparingInt(Cluster::totalOccurrences).reversed());
        noise.sort(Candidate.ORDER);
        List<Question> noiseSamples = noise.stream()
            .flatMap(candidate -> candidate.toQuestions(0d).stream())
            .limit(MAX_NOISE_SAMPLES)
            .toList();

        int questionCount = candidates.stream().mapToInt(Candidate::variantCount).sum();
        int noiseCount = noise.stream().mapToInt(Candidate::variantCount).sum();
        return new ClusterResult(questionCount, resultClusters.size(), noiseCount,
            embeddingUsed, embeddingInfo.model, embeddingInfo.version, thresholdUsed,
            resultClusters, noiseSamples);
    }

    @Transactional
    public ClusterReviewResult runAndSave(boolean includeResolved, int requestedLimit,
                                          double requestedThreshold, int requestedMinClusterSize) {
        requirePersistence();
        ClusterResult result = cluster(includeResolved, requestedLimit,
            requestedThreshold, requestedMinClusterSize);
        Date now = new Date();
        BotQuestionClusterRun run = new BotQuestionClusterRun();
        run.setIncludeResolved(includeResolved ? 1 : 0);
        run.setQuestionCount(result.questionCount());
        run.setClusterCount(result.clusterCount());
        run.setNoiseCount(result.noiseCount());
        run.setThreshold(result.thresholdUsed());
        run.setEmbeddingUsed(result.embeddingUsed() ? 1 : 0);
        run.setEmbeddingModel(result.embeddingModel());
        run.setEmbeddingVersion(result.embeddingVersion());
        run.setCreateTime(now);
        run.setUpdateTime(now);
        clusterRunMapper.insert(run);

        List<BotQuestionCluster> savedClusters = new ArrayList<>();
        List<BotQuestionClusterItem> savedItems = new ArrayList<>();
        for (Cluster cluster : result.clusters()) {
            BotQuestionCluster saved = new BotQuestionCluster();
            saved.setRunId(run.getId());
            saved.setClusterNumber(cluster.number());
            saved.setTitle(cluster.title());
            saved.setQuestionCount(cluster.questionCount());
            saved.setTotalOccurrences(cluster.totalOccurrences());
            saved.setCohesion(cluster.cohesion());
            saved.setIgnored(0);
            saved.setCreateTime(now);
            saved.setUpdateTime(now);
            clusterMapper.insert(saved);
            savedClusters.add(saved);

            for (Question question : cluster.questions()) {
                BotQuestionClusterItem item = new BotQuestionClusterItem();
                item.setClusterId(saved.getId());
                item.setUnmatchedQuestionId(question.id());
                item.setQuestion(question.question());
                item.setAnalysisQuestion(question.analysisQuestion());
                item.setSimilarCount(question.similarCount());
                item.setSimilarityToTitle(question.similarityToTitle());
                item.setCreateTime(now);
                item.setUpdateTime(now);
                clusterItemMapper.insert(item);
                savedItems.add(item);
            }
        }
        return toReview(run, savedClusters, savedItems, result.noiseQuestions());
    }

    public ClusterReviewResult latestReview() {
        requirePersistence();
        List<BotQuestionClusterRun> runs = clusterRunMapper.selectList(
            new LambdaQueryWrapper<BotQuestionClusterRun>()
                .orderByDesc(BotQuestionClusterRun::getCreateTime)
                .last("LIMIT 1"));
        if (runs.isEmpty()) return null;
        BotQuestionClusterRun run = runs.get(0);
        List<BotQuestionCluster> clusters = clusterMapper.selectList(
            new LambdaQueryWrapper<BotQuestionCluster>()
                .eq(BotQuestionCluster::getRunId, run.getId())
                .orderByAsc(BotQuestionCluster::getClusterNumber));
        List<BotQuestionClusterItem> items = List.of();
        if (!clusters.isEmpty()) {
            items = clusterItemMapper.selectList(
                new LambdaQueryWrapper<BotQuestionClusterItem>()
                    .in(BotQuestionClusterItem::getClusterId,
                        clusters.stream().map(BotQuestionCluster::getId).toList())
                    .orderByAsc(BotQuestionClusterItem::getId));
        }
        return toReview(run, clusters, items, List.of());
    }

    public boolean rename(Long id, String title) {
        requirePersistence();
        BotQuestionCluster cluster = id == null ? null : clusterMapper.selectById(id);
        if (cluster == null || title == null || title.isBlank()) return false;
        cluster.setTitle(title.trim());
        return clusterMapper.updateById(cluster) > 0;
    }

    public boolean ignore(Long id) {
        requirePersistence();
        BotQuestionCluster cluster = id == null ? null : clusterMapper.selectById(id);
        if (cluster == null) return false;
        cluster.setIgnored(1);
        return clusterMapper.updateById(cluster) > 0;
    }

    /**
     * Removes a review cluster snapshot without changing the source unmatched questions.
     * Any unpublished FAQ draft belongs to the review snapshot and is removed with it.
     */
    @Transactional
    public MutationResult delete(Long clusterId) {
        requirePersistence();
        if (clusterId == null) return MutationResult.badRequest("聚类不能为空");

        BotQuestionCluster cluster = clusterMapper.selectById(clusterId);
        if (cluster == null) return MutationResult.notFound("聚类不存在");
        if (Integer.valueOf(1).equals(cluster.getIgnored())) {
            return MutationResult.badRequest("已忽略的聚类不能删除");
        }

        BotFaqDraft draft = findFaqDraft(clusterId);
        if (draft != null && "PUBLISHED".equals(draft.getStatus())) {
            return MutationResult.conflict("已发布FAQ的聚类不能删除，请先处理正式FAQ");
        }

        List<BotQuestionClusterItem> items = clusterItemMapper.selectList(
            new LambdaQueryWrapper<BotQuestionClusterItem>()
                .eq(BotQuestionClusterItem::getClusterId, clusterId));
        for (BotQuestionClusterItem item : items) {
            clusterItemMapper.deleteById(item.getId());
        }
        if (draft != null) faqDraftMapper.deleteById(draft.getId());
        return clusterMapper.deleteById(clusterId) > 0
            ? MutationResult.ok() : MutationResult.notFound("聚类不存在");
    }

    private BotFaqDraft findFaqDraft(Long clusterId) {
        if (faqDraftMapper == null) return null;
        return faqDraftMapper.selectOne(new LambdaQueryWrapper<BotFaqDraft>()
            .eq(BotFaqDraft::getClusterId, clusterId).last("LIMIT 1"));
    }

    /** Copies source members into the target and keeps the source clusters as ignored history. */
    @Transactional
    public MutationResult merge(Long targetId, List<Long> requestedSourceIds) {
        requirePersistence();
        if (targetId == null || requestedSourceIds == null) {
            return MutationResult.badRequest("请选择要合并的聚类");
        }
        Set<Long> sourceIds = new LinkedHashSet<>(requestedSourceIds.stream()
            .filter(Objects::nonNull).toList());
        sourceIds.remove(targetId);
        if (sourceIds.isEmpty()) return MutationResult.badRequest("至少选择两个聚类");

        BotQuestionCluster target = clusterMapper.selectById(targetId);
        if (target == null) return MutationResult.notFound("目标聚类不存在");
        if (Integer.valueOf(1).equals(target.getIgnored())) {
            return MutationResult.badRequest("已忽略的聚类不能作为合并目标");
        }
        List<BotQuestionCluster> sources = clusterMapper.selectList(
            new LambdaQueryWrapper<BotQuestionCluster>()
                .in(BotQuestionCluster::getId, sourceIds));
        if (sources.size() != sourceIds.size()) {
            return MutationResult.notFound("部分聚类不存在");
        }
        for (BotQuestionCluster source : sources) {
            if (!Objects.equals(source.getRunId(), target.getRunId())) {
                return MutationResult.badRequest("只能合并同一审核批次中的聚类");
            }
            if (Integer.valueOf(1).equals(source.getIgnored())) {
                return MutationResult.badRequest("已忽略的聚类不能再次合并");
            }
        }

        Date now = new Date();
        for (BotQuestionCluster source : sources) {
            List<BotQuestionClusterItem> sourceItems = clusterItemMapper.selectList(
                new LambdaQueryWrapper<BotQuestionClusterItem>()
                    .eq(BotQuestionClusterItem::getClusterId, source.getId())
                    .orderByAsc(BotQuestionClusterItem::getId));
            for (BotQuestionClusterItem sourceItem : sourceItems) {
                BotQuestionClusterItem copy = copyItem(sourceItem, targetId, now);
                clusterItemMapper.insert(copy);
            }
            source.setIgnored(1);
            source.setMergedIntoId(targetId);
            source.setUpdateTime(now);
            clusterMapper.updateById(source);
        }
        refreshStats(target, now);
        return MutationResult.ok();
    }

    /** Moves selected source members into a new cluster in the same review batch. */
    @Transactional
    public MutationResult split(Long clusterId, List<Long> requestedQuestionIds, String requestedTitle) {
        requirePersistence();
        if (clusterId == null || requestedQuestionIds == null) {
            return MutationResult.badRequest("请选择要拆分的问题");
        }
        Set<Long> questionIds = new LinkedHashSet<>(requestedQuestionIds.stream()
            .filter(Objects::nonNull).toList());
        if (questionIds.isEmpty()) return MutationResult.badRequest("请选择要拆分的问题");

        BotQuestionCluster source = clusterMapper.selectById(clusterId);
        if (source == null) return MutationResult.notFound("聚类不存在");
        if (Integer.valueOf(1).equals(source.getIgnored())) {
            return MutationResult.badRequest("已忽略的聚类不能拆分");
        }
        List<BotQuestionClusterItem> allItems = clusterItemMapper.selectList(
            new LambdaQueryWrapper<BotQuestionClusterItem>()
                .eq(BotQuestionClusterItem::getClusterId, clusterId)
                .orderByAsc(BotQuestionClusterItem::getId));
        List<BotQuestionClusterItem> selected = allItems.stream()
            .filter(item -> questionIds.contains(item.getUnmatchedQuestionId()))
            .toList();
        if (selected.size() != questionIds.size()) {
            return MutationResult.badRequest("部分问题不属于该聚类");
        }
        if (selected.size() >= allItems.size()) {
            return MutationResult.badRequest("拆分后原聚类至少要保留一个问题");
        }

        String title = requestedTitle == null || requestedTitle.isBlank()
            ? selected.get(0).getQuestion() : requestedTitle.trim();
        if (title.length() > 500) return MutationResult.badRequest("聚类标题不能超过500个字符");
        Date now = new Date();
        BotQuestionCluster created = new BotQuestionCluster();
        created.setRunId(source.getRunId());
        created.setClusterNumber(nextClusterNumber(source.getRunId()));
        created.setTitle(title);
        created.setQuestionCount(selected.size());
        created.setTotalOccurrences(totalOccurrences(selected));
        created.setCohesion(averageSimilarity(selected));
        created.setIgnored(0);
        created.setCreateTime(now);
        created.setUpdateTime(now);
        clusterMapper.insert(created);
        for (BotQuestionClusterItem item : selected) {
            clusterItemMapper.update(null, new UpdateWrapper<BotQuestionClusterItem>()
                .eq("id", item.getId())
                .set("cluster_id", created.getId()));
        }
        refreshStats(source, now);
        return MutationResult.ok();
    }

    private BotQuestionClusterItem copyItem(BotQuestionClusterItem source, Long clusterId,
                                            Date now) {
        BotQuestionClusterItem copy = new BotQuestionClusterItem();
        copy.setClusterId(clusterId);
        copy.setUnmatchedQuestionId(source.getUnmatchedQuestionId());
        copy.setQuestion(source.getQuestion());
        copy.setAnalysisQuestion(source.getAnalysisQuestion());
        copy.setSimilarCount(source.getSimilarCount());
        copy.setSimilarityToTitle(source.getSimilarityToTitle());
        copy.setCreateTime(now);
        copy.setUpdateTime(now);
        return copy;
    }

    private int nextClusterNumber(Long runId) {
        List<BotQuestionCluster> latest = clusterMapper.selectList(
            new LambdaQueryWrapper<BotQuestionCluster>()
                .eq(BotQuestionCluster::getRunId, runId)
                .orderByDesc(BotQuestionCluster::getClusterNumber)
                .last("LIMIT 1"));
        return latest.isEmpty() || latest.get(0).getClusterNumber() == null
            ? 1 : latest.get(0).getClusterNumber() + 1;
    }

    private void refreshStats(BotQuestionCluster cluster, Date now) {
        List<BotQuestionClusterItem> items = clusterItemMapper.selectList(
            new LambdaQueryWrapper<BotQuestionClusterItem>()
                .eq(BotQuestionClusterItem::getClusterId, cluster.getId()));
        cluster.setQuestionCount(items.size());
        cluster.setTotalOccurrences(totalOccurrences(items));
        cluster.setCohesion(averageSimilarity(items));
        cluster.setUpdateTime(now);
        clusterMapper.updateById(cluster);
    }

    private static int totalOccurrences(List<BotQuestionClusterItem> items) {
        return items.stream().mapToInt(item -> positive(item.getSimilarCount())).sum();
    }

    private static double averageSimilarity(List<BotQuestionClusterItem> items) {
        return round(items.stream().mapToDouble(item -> item.getSimilarityToTitle() == null
            ? 0d : item.getSimilarityToTitle()).average().orElse(0d));
    }

    private void requirePersistence() {
        if (clusterRunMapper == null || clusterMapper == null || clusterItemMapper == null) {
            throw new IllegalStateException("聚类审核存储未配置");
        }
    }

    private ClusterReviewResult toReview(BotQuestionClusterRun run,
                                         List<BotQuestionCluster> clusters,
                                         List<BotQuestionClusterItem> items,
                                         List<Question> noiseQuestions) {
        Map<Long, List<BotQuestionClusterItem>> itemsByCluster = new LinkedHashMap<>();
        for (BotQuestionClusterItem item : items) {
            itemsByCluster.computeIfAbsent(item.getClusterId(), ignored -> new ArrayList<>()).add(item);
        }
        List<ReviewCluster> reviewClusters = clusters.stream()
            .map(cluster -> new ReviewCluster(cluster.getId(), cluster.getClusterNumber(),
                cluster.getTitle(), cluster.getQuestionCount(), cluster.getTotalOccurrences(),
                cluster.getCohesion(), cluster.getIgnored(),
                itemsByCluster.getOrDefault(cluster.getId(), List.of()).stream()
                    .map(item -> new Question(item.getUnmatchedQuestionId(), item.getQuestion(),
                        item.getAnalysisQuestion(), item.getSimilarCount(), item.getCreateTime(),
                        item.getSimilarityToTitle()))
                    .toList()))
            .toList();
        return new ClusterReviewResult(run.getId(), run.getCreateTime(), run.getQuestionCount(),
            run.getClusterCount(), run.getNoiseCount(), run.getEmbeddingUsed() != null
                && run.getEmbeddingUsed() == 1, safe(run.getEmbeddingModel()),
            safe(run.getEmbeddingVersion()), run.getThreshold(), reviewClusters,
            noiseQuestions == null ? List.of() : noiseQuestions);
    }

    private List<Candidate> deduplicate(List<BotUnmatchedQuestion> rows) {
        Map<String, Candidate> unique = new LinkedHashMap<>();
        if (rows == null) return List.of();
        for (BotUnmatchedQuestion row : rows) {
            if (row == null || row.getQuestion() == null || row.getQuestion().isBlank()) continue;
            String normalized = normalize(row.getQuestion());
            if (normalized.isBlank()) continue;
            Candidate existing = unique.get(normalized);
            int occurrences = positive(row.getSimilarCount());
            if (existing == null) {
                unique.put(normalized, new Candidate(row.getId(), row.getQuestion().trim(),
                    normalized, occurrences, row.getCreateTime()));
            } else {
                existing.addVariant(row, occurrences);
                if (isAfter(row.getCreateTime(), existing.createTime)) existing.createTime = row.getCreateTime();
            }
        }
        List<Candidate> candidates = new ArrayList<>(unique.values());
        candidates.sort(Candidate.ORDER);
        return candidates;
    }

    private EmbeddingInfo generateEmbeddings(List<Candidate> candidates) {
        List<String> texts = candidates.stream().map(candidate -> candidate.normalized).toList();
        List<float[]> vectors;
        try {
            vectors = embeddingService.embedBatch(texts);
        } catch (RuntimeException error) {
            log.warn("Question clustering embedding failed; using text similarity fallback", error);
            vectors = List.of();
        }
        int valid = 0;
        if (vectors != null) {
            for (int i = 0; i < candidates.size() && i < vectors.size(); i++) {
                float[] vector = vectors.get(i);
                if (vector != null && vector.length > 0) {
                    candidates.get(i).vector = vector;
                    valid++;
                }
            }
        }
        EmbeddingService.EmbeddingDescriptor descriptor = null;
        try {
            descriptor = embeddingService.descriptor();
        } catch (RuntimeException error) {
            log.debug("Unable to read embedding descriptor", error);
        }
        String model = descriptor == null ? "" : safe(descriptor.model());
        String version = descriptor == null ? "" : safe(descriptor.version());
        return new EmbeddingInfo(valid > 0, model, version);
    }

    private String normalize(String value) {
        String normalized = correctionService.correct(value).toLowerCase(Locale.ROOT).trim();
        for (String prefix : FILLER_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                normalized = normalized.substring(prefix.length());
                break;
            }
        }
        normalized = PUNCTUATION.matcher(normalized).replaceAll("");
        for (Map.Entry<String, String> synonym : SYNONYMS) {
            normalized = normalized.replace(synonym.getKey(), synonym.getValue());
        }
        return normalized;
    }

    private static double similarity(Candidate left, Candidate right) {
        double lexical = lexicalSimilarity(left.normalized, right.normalized);
        if (hasCompatibleVectors(left.vector, right.vector)) {
            return Math.max(VectorUtil.cosineSimilarity(left.vector, right.vector), lexical);
        }
        return lexical;
    }

    private static double lexicalSimilarity(String left, String right) {
        if (left.equals(right)) return 1d;
        if (left.isBlank() || right.isBlank()) return 0d;
        Set<String> leftGrams = grams(left);
        Set<String> rightGrams = grams(right);
        Set<String> intersection = new HashSet<>(leftGrams);
        intersection.retainAll(rightGrams);
        if (intersection.isEmpty()) return 0d;
        return (2d * intersection.size()) / (leftGrams.size() + rightGrams.size());
    }

    private static Set<String> grams(String value) {
        Set<String> grams = new HashSet<>();
        for (int i = 0; i < value.length(); i++) {
            grams.add(value.substring(i, i + 1));
            if (i + 1 < value.length()) grams.add(value.substring(i, i + 2));
        }
        return grams;
    }

    private static boolean hasCompatibleVectors(float[] left, float[] right) {
        return left != null && right != null && left.length > 0 && left.length == right.length;
    }

    private static int positive(Integer value) {
        return value == null || value < 1 ? 1 : value;
    }

    private static boolean isAfter(Date candidate, Date current) {
        return candidate != null && (current == null || candidate.after(current));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static int clamp(int value, int min, int max, int fallback) {
        int actual = value <= 0 ? fallback : value;
        return Math.min(max, Math.max(min, actual));
    }

    private static double clamp(double value, double min, double max, double fallback) {
        double actual = Double.isFinite(value) ? value : fallback;
        return Math.min(max, Math.max(min, actual));
    }

    public record ClusterResult(int questionCount, int clusterCount, int noiseCount,
                                boolean embeddingUsed, String embeddingModel,
                                String embeddingVersion, double thresholdUsed,
                                List<Cluster> clusters, List<Question> noiseQuestions) {}

    public record ClusterReviewResult(Long runId, Date createTime, int questionCount,
                                      int clusterCount, int noiseCount, boolean embeddingUsed,
                                      String embeddingModel, String embeddingVersion,
                                      double thresholdUsed, List<ReviewCluster> clusters,
                                      List<Question> noiseQuestions) {}

    public record ReviewCluster(Long id, int number, String title, int questionCount,
                                int totalOccurrences, double cohesion, Integer ignored,
                                List<Question> questions) {}

    public record MutationResult(boolean success, int code, String message) {
        static MutationResult ok() { return new MutationResult(true, 200, "success"); }
        static MutationResult badRequest(String message) { return new MutationResult(false, 400, message); }
        static MutationResult conflict(String message) { return new MutationResult(false, 409, message); }
        static MutationResult notFound(String message) { return new MutationResult(false, 404, message); }
    }

    public record Cluster(int number, String title, int questionCount,
                          int totalOccurrences, double cohesion,
                          List<Question> questions) {}

    public record Question(Long id, String question, String analysisQuestion,
                           Integer similarCount, Date createTime,
                           double similarityToTitle) {}

    private record EmbeddingInfo(boolean used, String model, String version) {}

    private static final class Candidate {
        private static final Comparator<Candidate> ORDER = Comparator
            .comparingInt((Candidate candidate) -> candidate.occurrences).reversed()
            .thenComparing(candidate -> candidate.createTime,
                Comparator.nullsLast(Comparator.reverseOrder()));
        private final String question;
        private final String normalized;
        private final List<SourceQuestion> variants = new ArrayList<>();
        private int occurrences;
        private Date createTime;
        private float[] vector;

        private Candidate(Long id, String question, String normalized,
                          int occurrences, Date createTime) {
            this.question = question;
            this.normalized = normalized;
            this.occurrences = occurrences;
            this.createTime = createTime;
            this.variants.add(new SourceQuestion(id, question, occurrences, createTime));
        }

        private void addVariant(BotUnmatchedQuestion row, int rowOccurrences) {
            variants.add(new SourceQuestion(row.getId(), row.getQuestion().trim(),
                rowOccurrences, row.getCreateTime()));
            occurrences += rowOccurrences;
        }

        private int variantCount() {
            return variants.size();
        }

        private List<Question> toQuestions(double similarity) {
            return variants.stream()
                .map(variant -> new Question(variant.id, variant.question, normalized,
                    variant.similarCount, variant.createTime, similarity))
                .toList();
        }
    }

    private record SourceQuestion(Long id, String question, int similarCount, Date createTime) {}

    private static final class MutableCluster {
        private final int number;
        private final List<Candidate> members = new ArrayList<>();

        private MutableCluster(int number) {
            this.number = number;
        }

        private void add(Candidate candidate) {
            members.add(candidate);
        }

        private double maxSimilarity(Candidate candidate) {
            double best = 0d;
            for (Candidate member : members) best = Math.max(best, similarity(candidate, member));
            return best;
        }

        private int variantCount() {
            return members.stream().mapToInt(Candidate::variantCount).sum();
        }

        private Cluster toResult() {
            members.sort(Candidate.ORDER);
            Candidate title = members.get(0);
            List<Question> questions = members.stream()
                .flatMap(member -> member.toQuestions(member == title ? 1d : similarity(member, title)).stream())
                .toList();
            double cohesion = questions.stream().mapToDouble(Question::similarityToTitle).average().orElse(1d);
            int totalOccurrences = members.stream().mapToInt(member -> member.occurrences).sum();
            return new Cluster(number, title.question, questions.size(), totalOccurrences,
                round(cohesion), questions);
        }
    }

    private static double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }
}
