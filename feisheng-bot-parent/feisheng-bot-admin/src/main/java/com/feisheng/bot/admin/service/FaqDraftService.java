package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.admin.entity.BotFaqDraft;
import com.feisheng.bot.admin.entity.BotKnowledgeItem;
import com.feisheng.bot.admin.entity.BotQuestionCluster;
import com.feisheng.bot.admin.entity.BotQuestionClusterItem;
import com.feisheng.bot.admin.mapper.BotFaqDraftMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeItemMapper;
import com.feisheng.bot.admin.mapper.BotQuestionClusterItemMapper;
import com.feisheng.bot.admin.mapper.BotQuestionClusterMapper;
import com.feisheng.bot.core.dto.ChatResponse;
import com.feisheng.bot.core.service.impl.AiModelServiceImpl;
import com.feisheng.bot.core.service.impl.RagRetrievalService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FaqDraftService {
    static final String DRAFT = "DRAFT";
    static final String REJECTED = "REJECTED";
    static final String PUBLISHED = "PUBLISHED";
    static final String SUPPORTED = "SUPPORTED";
    static final String MISSING = "MISSING";
    static final String STALE = "STALE";

    private static final String SYSTEM_PROMPT = """
        你是点签电子合同FAQ草稿助手。只能依据给出的企业内部事实生成答案，禁止补充、猜测或承诺事实中没有的内容。
        输出一个JSON对象且不要输出Markdown，格式为：{"answer":"面向客户的简洁答案","keywords":["短关键词"]}。
        答案使用中文客服口吻，不提及知识库、证据或引用；信息不足时answer必须为空字符串。
        """;

    private final BotFaqDraftMapper draftMapper;
    private final BotQuestionClusterMapper clusterMapper;
    private final BotQuestionClusterItemMapper clusterItemMapper;
    private final BotKnowledgeItemMapper knowledgeItemMapper;
    private final RagRetrievalService retrievalService;
    private final AiModelServiceImpl aiModelService;
    private final FaqPublicationService publicationService;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<Long, Object> generationLocks = new ConcurrentHashMap<>();

    public FaqDraftService(BotFaqDraftMapper draftMapper,
                           BotQuestionClusterMapper clusterMapper,
                           BotQuestionClusterItemMapper clusterItemMapper,
                           BotKnowledgeItemMapper knowledgeItemMapper,
                           RagRetrievalService retrievalService,
                           AiModelServiceImpl aiModelService,
                           FaqPublicationService publicationService,
                           ObjectMapper objectMapper) {
        this.draftMapper = draftMapper;
        this.clusterMapper = clusterMapper;
        this.clusterItemMapper = clusterItemMapper;
        this.knowledgeItemMapper = knowledgeItemMapper;
        this.retrievalService = retrievalService;
        this.aiModelService = aiModelService;
        this.publicationService = publicationService;
        this.objectMapper = objectMapper;
    }

    public List<DraftView> list(Long runId) {
        QueryWrapper<BotFaqDraft> query = new QueryWrapper<BotFaqDraft>()
            .eq(runId != null, "run_id", runId)
            .orderByDesc("id")
            .last("LIMIT 500");
        return draftMapper.selectList(query).stream().map(this::toView).toList();
    }

    public DraftView generate(Long clusterId, Long operatorId) {
        return generate(clusterId, operatorId, false);
    }

    public DraftView generate(Long clusterId, Long operatorId, boolean regenerate) {
        if (clusterId == null) throw new FaqDraftException(400, "聚类不能为空");
        Object lock = generationLocks.computeIfAbsent(clusterId, ignored -> new Object());
        synchronized (lock) {
            return generateLocked(clusterId, operatorId, regenerate);
        }
    }

    private DraftView generateLocked(Long clusterId, Long operatorId, boolean regenerate) {
        BotQuestionCluster cluster = clusterId == null ? null : clusterMapper.selectById(clusterId);
        if (cluster == null) throw new FaqDraftException(404, "聚类不存在");
        if (Integer.valueOf(1).equals(cluster.getIgnored())) {
            throw new FaqDraftException(400, "已忽略的聚类不能生成FAQ草稿");
        }
        List<BotQuestionClusterItem> items = clusterItemMapper.selectList(
            new QueryWrapper<BotQuestionClusterItem>()
                .eq("cluster_id", clusterId).orderByAsc("id"));
        if (items.isEmpty()) throw new FaqDraftException(400, "聚类中没有可用问题");

        BotFaqDraft existing = draftMapper.selectOne(new QueryWrapper<BotFaqDraft>()
            .eq("cluster_id", clusterId).last("LIMIT 1"));
        if (existing != null && (!regenerate || PUBLISHED.equals(existing.getStatus()))) {
            return toView(existing);
        }

        String question = requiredText(cluster.getTitle(), 500, "聚类标题不能为空");
        List<String> similarQuestions = items.stream().map(BotQuestionClusterItem::getQuestion)
            .filter(Objects::nonNull).map(String::trim).filter(value -> !value.isBlank())
            .distinct().limit(50).toList();
        RagRetrievalService.RetrievalResult retrieval = retrievalService.retrieve(question, false);
        boolean supported = retrieval.answerable()
            && retrieval.context() != null && !retrieval.context().isBlank()
            && retrieval.citations() != null && !retrieval.citations().isEmpty();

        GeneratedContent generated = supported
            ? generateContent(question, similarQuestions, retrieval)
            : new GeneratedContent("", keywordsFromQuestions(similarQuestions), "",
                "知识库中没有找到足够依据，请补充真实知识后重新生成");
        Duplicate duplicate = findDuplicate(question, retrieval);
        Date now = new Date();
        BotFaqDraft draft = existing == null ? new BotFaqDraft() : existing;
        draft.setRunId(cluster.getRunId());
        draft.setClusterId(clusterId);
        draft.setQuestion(question);
        draft.setAnswer(generated.answer());
        draft.setKeywords(limit(generated.keywords(), 500));
        draft.setSimilarQuestionsJson(json(similarQuestions));
        draft.setEvidenceJson(supported ? json(retrieval.citations()) : "[]");
        draft.setEvidenceStatus(supported ? SUPPORTED : MISSING);
        draft.setGenerationMessage(generated.message());
        draft.setGeneratorModel(limit(generated.model(), 200));
        draft.setDuplicateItemId(duplicate.itemId());
        draft.setDuplicateScore(duplicate.score());
        draft.setStatus(DRAFT);
        draft.setReviewedBy(null);
        draft.setReviewedAt(null);
        draft.setReviewReason(null);
        draft.setPublishedItemId(null);
        draft.setPublishedAt(null);
        draft.setUpdateTime(now);
        if (existing == null) {
            draft.setCreatedBy(operatorId);
            draft.setCreateTime(now);
            try {
                draftMapper.insert(draft);
            } catch (DuplicateKeyException duplicateKey) {
                // A request from another application instance may have won the unique race.
                BotFaqDraft concurrent = draftMapper.selectOne(new QueryWrapper<BotFaqDraft>()
                    .eq("cluster_id", clusterId).last("LIMIT 1"));
                if (concurrent != null) return toView(concurrent);
                throw duplicateKey;
            }
        } else {
            draftMapper.updateById(draft);
            draftMapper.update(null, new UpdateWrapper<BotFaqDraft>()
                .eq("id", draft.getId())
                .set("duplicate_item_id", draft.getDuplicateItemId())
                .set("duplicate_score", draft.getDuplicateItemId() == null
                    ? null : draft.getDuplicateScore())
                .set("reviewed_by", null)
                .set("reviewed_at", null)
                .set("review_reason", null));
        }
        return toView(draft);
    }

    public DraftView update(Long id, String question, String answer, String keywords,
                            Long operatorId) {
        BotFaqDraft draft = requireDraft(id);
        String normalizedQuestion = requiredText(question, 500, "标准问题不能为空");
        String normalizedAnswer = optionalText(answer, 20_000, "答案不能超过20000个字符");
        String normalizedKeywords = optionalText(keywords, 500, "关键词不能超过500个字符");
        if (!Objects.equals(draft.getQuestion(), normalizedQuestion)) {
            draft.setEvidenceStatus(STALE);
            draft.setGenerationMessage("标准问题已修改，请重新生成以校验知识依据");
            Duplicate duplicate = findExactDuplicate(normalizedQuestion);
            draft.setDuplicateItemId(duplicate.itemId());
            draft.setDuplicateScore(duplicate.score());
        }
        draft.setQuestion(normalizedQuestion);
        draft.setAnswer(normalizedAnswer);
        draft.setKeywords(normalizedKeywords);
        draft.setReviewedBy(operatorId);
        draft.setUpdateTime(new Date());
        draftMapper.updateById(draft);
        return toView(draft);
    }

    public DraftView reject(Long id, String reason, Long operatorId) {
        BotFaqDraft draft = requireDraft(id);
        draft.setStatus(REJECTED);
        draft.setReviewReason(requiredText(reason, 1000, "请填写拒绝原因"));
        draft.setReviewedBy(operatorId);
        draft.setReviewedAt(new Date());
        draft.setUpdateTime(new Date());
        draftMapper.updateById(draft);
        return toView(draft);
    }

    @Transactional
    public DraftView publish(Long id, Long operatorId) {
        BotFaqDraft draft = requireDraft(id);
        if (!SUPPORTED.equals(draft.getEvidenceStatus())
                || draft.getEvidenceJson() == null || draft.getEvidenceJson().isBlank()) {
            throw new FaqDraftException(400, "知识依据不足或已过期，请补充知识并重新生成");
        }
        requiredText(draft.getQuestion(), 500, "标准问题不能为空");
        requiredText(draft.getAnswer(), 20_000, "答案不能为空");
        Long itemId = publicationService.publish(draft);
        Date now = new Date();
        draft.setStatus(PUBLISHED);
        draft.setPublishedItemId(itemId);
        draft.setPublishedAt(now);
        draft.setReviewedBy(operatorId);
        draft.setReviewedAt(now);
        draft.setReviewReason(null);
        draft.setUpdateTime(now);
        draftMapper.updateById(draft);
        return toView(draft);
    }

    private BotFaqDraft requireDraft(Long id) {
        BotFaqDraft draft = id == null ? null : draftMapper.selectById(id);
        if (draft == null) throw new FaqDraftException(404, "FAQ草稿不存在");
        if (!DRAFT.equals(draft.getStatus())) {
            throw new FaqDraftException(409, "只有待审核草稿可以执行该操作");
        }
        return draft;
    }

    private GeneratedContent generateContent(String question, List<String> variants,
                                             RagRetrievalService.RetrievalResult retrieval) {
        if (retrieval.directAnswer() && retrieval.directAnswerText() != null
                && !retrieval.directAnswerText().isBlank()) {
            return new GeneratedContent(limit(retrieval.directAnswerText().trim(), 20_000),
                keywordsFromQuestions(variants), "knowledge-direct", "草稿已根据知识库生成");
        }
        String prompt = json(Map.of(
            "standard_question", question,
            "customer_variants", variants,
            "trusted_knowledge", retrieval.context()));
        ChatResponse response = aiModelService.chat(prompt, SYSTEM_PROMPT);
        if (response == null || !response.isSuccess() || response.getContent() == null) {
            return new GeneratedContent("", keywordsFromQuestions(variants), "",
                "模型暂时无法生成答案，请稍后重新生成");
        }
        try {
            JsonNode root = firstJsonObject(response.getContent());
            String answer = text(root.get("answer"));
            List<String> keywords = new ArrayList<>();
            JsonNode keywordNode = root.get("keywords");
            if (keywordNode != null && keywordNode.isArray()) {
                for (JsonNode node : keywordNode) {
                    String value = text(node);
                    if (!value.isBlank()) keywords.add(value);
                }
            }
            String mergedKeywords = joinKeywords(keywords, variants);
            String model = String.join("/", blank(response.getProviderCode()), blank(response.getModel()))
                .replaceAll("^/+|/+$", "");
            return answer.isBlank()
                ? new GeneratedContent("", mergedKeywords, model,
                    "模型判断现有知识不足，请补充知识后重新生成")
                : new GeneratedContent(limit(answer, 20_000), mergedKeywords, model,
                    "草稿已根据知识库生成");
        } catch (Exception error) {
            return new GeneratedContent("", keywordsFromQuestions(variants), "",
                "模型返回格式异常，请重新生成");
        }
    }

    private Duplicate findDuplicate(String question,
                                    RagRetrievalService.RetrievalResult retrieval) {
        Duplicate exact = findExactDuplicate(question);
        if (exact.itemId() != null) return exact;
        if (retrieval == null || retrieval.candidates() == null) return Duplicate.none();
        return retrieval.candidates().stream()
            .filter(candidate -> candidate.get("itemId") instanceof Number)
            .map(candidate -> new Duplicate(
                ((Number) candidate.get("itemId")).longValue(), number(candidate.get("score"))))
            .max(java.util.Comparator.comparingDouble(Duplicate::score))
            .orElse(Duplicate.none());
    }

    private Duplicate findExactDuplicate(String question) {
        BotKnowledgeItem item = knowledgeItemMapper.selectOne(new QueryWrapper<BotKnowledgeItem>()
            .eq("question", question).last("LIMIT 1"));
        return item == null ? Duplicate.none() : new Duplicate(item.getId(), 1d);
    }

    private DraftView toView(BotFaqDraft draft) {
        int hitCount = 0;
        if (draft.getPublishedItemId() != null) {
            BotKnowledgeItem item = knowledgeItemMapper.selectById(draft.getPublishedItemId());
            if (item != null && item.getHitCount() != null) hitCount = item.getHitCount();
        }
        return new DraftView(draft.getId(), draft.getRunId(), draft.getClusterId(),
            draft.getQuestion(), blank(draft.getAnswer()), blank(draft.getKeywords()),
            stringList(draft.getSimilarQuestionsJson()), mapList(draft.getEvidenceJson()),
            draft.getEvidenceStatus(), blank(draft.getGenerationMessage()),
            blank(draft.getGeneratorModel()), draft.getDuplicateItemId(),
            draft.getDuplicateScore(), draft.getStatus(), draft.getCreatedBy(),
            draft.getReviewedBy(), draft.getReviewedAt(), blank(draft.getReviewReason()),
            draft.getPublishedItemId(), draft.getPublishedAt(), hitCount,
            draft.getCreateTime(), draft.getUpdateTime());
    }

    private JsonNode firstJsonObject(String content) throws IOException {
        try (JsonParser parser = objectMapper.getFactory().createParser(content)) {
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token == JsonToken.START_OBJECT) return objectMapper.readTree(parser);
            }
        }
        throw new IOException("No JSON object");
    }

    private String keywordsFromQuestions(List<String> variants) {
        return joinKeywords(List.of(), variants);
    }

    private String joinKeywords(List<String> modelKeywords, List<String> variants) {
        Set<String> values = new LinkedHashSet<>();
        modelKeywords.stream().map(String::trim).filter(value -> !value.isBlank()).forEach(values::add);
        variants.stream().map(this::questionKeyword).filter(value -> !value.isBlank()).forEach(values::add);
        StringBuilder joined = new StringBuilder();
        for (String value : values) {
            if (joined.length() + value.length() + (joined.isEmpty() ? 0 : 1) > 500) break;
            if (!joined.isEmpty()) joined.append(',');
            joined.append(value);
        }
        return joined.toString();
    }

    private String questionKeyword(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) return "";
        return normalized.matches(".*[?？]$") ? normalized : normalized + "？";
    }

    private String requiredText(String value, int maxLength, String message) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) throw new FaqDraftException(400, message);
        if (normalized.length() > maxLength) throw new FaqDraftException(400, message);
        return normalized;
    }

    private String optionalText(String value, int maxLength, String message) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maxLength) throw new FaqDraftException(400, message);
        return normalized;
    }

    private String limit(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new FaqDraftException(500, "FAQ草稿数据保存失败");
        }
    }

    private List<String> stringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, LinkedHashMap.class));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String text(JsonNode node) {
        return node == null || !node.isTextual() ? "" : node.asText().trim();
    }

    private String blank(String value) {
        return value == null ? "" : value;
    }

    private double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0d;
    }

    private record GeneratedContent(String answer, String keywords, String model, String message) {}
    private record Duplicate(Long itemId, double score) {
        private static Duplicate none() { return new Duplicate(null, 0d); }
    }

    public record DraftView(Long id, Long runId, Long clusterId, String question,
                            String answer, String keywords, List<String> similarQuestions,
                            List<Map<String, Object>> evidence, String evidenceStatus,
                            String generationMessage, String generatorModel,
                            Long duplicateItemId, Double duplicateScore, String status,
                            Long createdBy, Long reviewedBy, Date reviewedAt,
                            String reviewReason, Long publishedItemId, Date publishedAt,
                            int publishedHitCount, Date createTime, Date updateTime) {}

    public static class FaqDraftException extends RuntimeException {
        private final int status;

        public FaqDraftException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
