package com.feisheng.bot.admin.service;

import com.feisheng.bot.admin.entity.BotKnowledgeChunk;
import com.feisheng.bot.common.dto.StructuredKnowledgeUnit;
import com.feisheng.bot.common.util.EmbeddingMetadataUtil;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strictly parses and source-validates extraction model output. */
@Component
public class StructuredKnowledgeResponseParser {
    public static final String SCHEMA_VERSION = "structured-knowledge-unit-v1";
    public static final String PROMPT_VERSION = "structured-extraction-v1";

    private static final Set<String> ROOT_FIELDS = Set.of("schema_version", "units");
    private static final Set<String> UNIT_FIELDS = Set.of(
        "unit_type", "question", "statement", "intent", "entities", "conditions",
        "exclusions", "query_variants", "metadata", "extraction_confidence", "evidence");
    private static final Set<String> METADATA_FIELDS = Set.of(
        "product", "channel", "audience", "risk_level", "effective_from", "effective_to");
    private static final Set<String> EVIDENCE_FIELDS = Set.of("chunk_id", "quote");
    private static final Set<String> UNIT_TYPES = Set.of("QA", "FACT", "PROCEDURE", "POLICY");
    private static final Set<String> RISK_LEVELS = Set.of("LOW", "MEDIUM", "HIGH", "UNKNOWN");
    private static final Pattern IDENTIFIER = Pattern.compile(
        "(?i)(?<![a-z0-9_-])(?=[a-z0-9_-]{4,64}(?![a-z0-9_-]))"
            + "(?=[a-z0-9_-]*\\d)[a-z0-9][a-z0-9_-]{3,63}");
    private static final Pattern NUMBER = Pattern.compile("(?<!\\d)\\d+(?:\\.\\d+)?(?!\\d)");
    private static final Pattern LATIN_TERM = Pattern.compile("[a-z][a-z0-9_-]{2,}");
    private static final Pattern NEGATION = Pattern.compile(
        "(?i)(?:不(?:能|可|支持|允许|适用|提供|包含|需要)?|未(?:能|支持|提供|完成)?|"
            + "没有|禁止|无法|无需|\\b(?:not|no|never|cannot|can't|without|"
            + "unsupported|prohibited)\\b)");
    private static final Pattern NEUTRAL_QUESTION = Pattern.compile(
        "(?i)(?:是否|能否|可否|吗|么|[?？]|\\b(?:whether|what|is|are|can|could|"
            + "does|do)\\b)");
    private static final Set<String> LATIN_STOP_WORDS = Set.of(
        "the", "and", "for", "how", "what", "when", "where", "why", "can", "does",
        "with", "this", "that", "please", "about", "from", "into", "user");

    private final ObjectMapper objectMapper;

    public StructuredKnowledgeResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    }

    public List<StructuredKnowledgeUnit> parse(
            String content,
            List<BotKnowledgeChunk> sourceChunks,
            String sourceHash,
            String extractorModel,
            int maxUnits) throws IOException {
        ParseResult result = parsePartial(
            content, sourceChunks, sourceHash, extractorModel, maxUnits);
        if (!result.rejections().isEmpty()) {
            throw new ValidationException(result.rejections().get(0).message());
        }
        return result.units();
    }

    /** Keeps valid units while reporting each source-validation rejection separately. */
    public ParseResult parsePartial(
            String content,
            List<BotKnowledgeChunk> sourceChunks,
            String sourceHash,
            String extractorModel,
            int maxUnits) throws IOException {
        JsonNode root = readSingleJsonValue(content);
        requireExactFields(root, ROOT_FIELDS);
        if (!SCHEMA_VERSION.equals(text(root.get("schema_version"), 64, false))) {
            throw new ValidationException("unsupported schema_version");
        }
        JsonNode unitsNode = root.get("units");
        if (!unitsNode.isArray() || unitsNode.size() > Math.max(1, maxUnits)) {
            throw new ValidationException("units must be an array within the configured limit");
        }

        Map<Long, BotKnowledgeChunk> chunksById = new HashMap<>();
        for (BotKnowledgeChunk chunk : sourceChunks) {
            if (chunk != null && chunk.getId() != null && chunk.getContent() != null) {
                chunksById.put(chunk.getId(), chunk);
            }
        }

        Map<String, StructuredKnowledgeUnit> distinct = new java.util.LinkedHashMap<>();
        List<UnitRejection> rejections = new ArrayList<>();
        for (int index = 0; index < unitsNode.size(); index++) {
            try {
                StructuredKnowledgeUnit unit = parseUnit(
                    unitsNode.get(index), chunksById, sourceHash, extractorModel);
                distinct.putIfAbsent(unit.unitKey(), unit);
            } catch (ValidationException e) {
                rejections.add(new UnitRejection(index, e.getMessage()));
            }
        }
        return new ParseResult(List.copyOf(distinct.values()), List.copyOf(rejections));
    }

    private StructuredKnowledgeUnit parseUnit(
            JsonNode node,
            Map<Long, BotKnowledgeChunk> chunksById,
            String sourceHash,
            String extractorModel) {
        requireExactFields(node, UNIT_FIELDS);
        String unitTypeValue = text(node.get("unit_type"), 32, false).toUpperCase(Locale.ROOT);
        if (!UNIT_TYPES.contains(unitTypeValue)) {
            throw new ValidationException("invalid unit_type");
        }
        String question = text(node.get("question"), 1000, false);
        String statement = text(node.get("statement"), 20_000, false);
        String intent = text(node.get("intent"), 128, false);
        List<String> entities = textArray(node.get("entities"), 30, 300);
        List<String> conditions = textArray(node.get("conditions"), 20, 1000);
        List<String> exclusions = textArray(node.get("exclusions"), 20, 1000);
        List<String> queryVariants = textArray(node.get("query_variants"), 5, 1000);
        StructuredKnowledgeUnit.BusinessMetadata metadata = parseMetadata(node.get("metadata"));

        JsonNode confidenceNode = node.get("extraction_confidence");
        if (!confidenceNode.isNumber()) {
            throw new ValidationException("extraction_confidence must be numeric");
        }
        double confidence = confidenceNode.doubleValue();
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new ValidationException("extraction_confidence must be in [0, 1]");
        }

        Evidence evidence = parseEvidence(node.get("evidence"), chunksById);
        String evidenceText = evidence.sourceText();
        if (!evidenceCovers(statement, evidence.spans())) {
            throw new ValidationException("statement is not a verbatim evidence span");
        }
        if (!sharesTopic(question, evidenceText)) {
            throw new ValidationException("question drifted from its evidence");
        }
        requireSourceBacked(conditions, evidenceText, "condition");
        requireSourceBacked(exclusions, evidenceText, "exclusion");
        entities = sourceBackedValues(entities, evidenceText);
        metadata = sourceBackedMetadata(metadata, evidenceText);
        question = preserveQualifiers(question, conditions, exclusions);

        List<String> anchors = combine(List.of(metadata.product()), entities).stream()
            .filter(value -> !value.isBlank())
            .toList();
        List<String> normalizedVariants = new ArrayList<>(queryVariants.size());
        for (String variant : queryVariants) {
            normalizedVariants.add(preserveQualifiers(
                preserveAnchor(variant, anchors), conditions, exclusions));
        }
        queryVariants = List.copyOf(normalizedVariants);

        String generated = String.join("\n", combine(
            List.of(question, intent), entities, conditions, exclusions, queryVariants,
            List.of(metadata.product(), metadata.channel(), metadata.audience(),
                metadata.effectiveFrom(), metadata.effectiveTo())));
        requireProtectedTokensFromEvidence(generated, evidenceText);
        requirePolarityPreserved(question, statement);
        requireQualifiersPreserved(question, conditions, exclusions, "question");
        for (String variant : queryVariants) {
            if (!sharesTopic(question + "\n" + statement, variant)) {
                throw new ValidationException("query variant drifted from the unit");
            }
            if (!anchors.stream().filter(value -> !value.isBlank())
                    .toList().isEmpty()
                    && anchors.stream().filter(value -> !value.isBlank())
                        .noneMatch(variant::contains)) {
                throw new ValidationException("query variant dropped every entity anchor");
            }
            requirePolarityPreserved(variant, statement);
            requireQualifiersPreserved(variant, conditions, exclusions, "query variant");
        }

        StructuredKnowledgeUnit.UnitType unitType =
            StructuredKnowledgeUnit.UnitType.valueOf(unitTypeValue);
        String unitKey = EmbeddingMetadataUtil.contentHash(String.join("|",
            unitTypeValue, normalize(question), normalize(statement), normalize(intent)));
        return new StructuredKnowledgeUnit(
            unitKey, unitType, question, statement, intent,
            entities, conditions, exclusions, queryVariants,
            evidence.chunkIds(), evidence.spans(), metadata, round5(confidence),
            extractorModel, PROMPT_VERSION, SCHEMA_VERSION, sourceHash,
            "DRAFT", true);
    }

    private StructuredKnowledgeUnit.BusinessMetadata parseMetadata(JsonNode node) {
        requireExactFields(node, METADATA_FIELDS);
        String risk = text(node.get("risk_level"), 20, false).toUpperCase(Locale.ROOT);
        if (!RISK_LEVELS.contains(risk)) throw new ValidationException("invalid risk_level");
        if (!"UNKNOWN".equals(risk)) {
            throw new ValidationException(
                "risk_level must be UNKNOWN until trusted review metadata is injected");
        }
        return new StructuredKnowledgeUnit.BusinessMetadata(
            text(node.get("product"), 300, true),
            text(node.get("channel"), 300, true),
            text(node.get("audience"), 300, true),
            StructuredKnowledgeUnit.RiskLevel.valueOf(risk),
            text(node.get("effective_from"), 100, true),
            text(node.get("effective_to"), 100, true));
    }

    private Evidence parseEvidence(JsonNode node, Map<Long, BotKnowledgeChunk> chunksById) {
        if (node == null || !node.isArray() || node.isEmpty() || node.size() > 8) {
            throw new ValidationException("evidence must contain 1 to 8 entries");
        }
        Set<Long> chunkIds = new LinkedHashSet<>();
        List<StructuredKnowledgeUnit.SourceSpan> spans = new ArrayList<>();
        List<String> quotes = new ArrayList<>();
        for (JsonNode evidenceNode : node) {
            requireExactFields(evidenceNode, EVIDENCE_FIELDS);
            JsonNode chunkIdNode = evidenceNode.get("chunk_id");
            if (!chunkIdNode.isIntegralNumber() || !chunkIdNode.canConvertToLong()) {
                throw new ValidationException("evidence chunk_id must be an integer");
            }
            long chunkId = chunkIdNode.longValue();
            BotKnowledgeChunk source = chunksById.get(chunkId);
            if (source == null) {
                throw new ValidationException("evidence references a chunk outside the batch");
            }
            String quote = text(evidenceNode.get("quote"), 20_000, false);
            int start = source.getContent().indexOf(quote);
            if (start < 0) {
                throw new ValidationException("evidence quote is not an exact chunk substring");
            }
            chunkIds.add(chunkId);
            quotes.add(quote);
            spans.add(new StructuredKnowledgeUnit.SourceSpan(
                chunkId, start, start + quote.length(), quote));
        }
        return new Evidence(List.copyOf(chunkIds), List.copyOf(spans),
            String.join("\n", quotes));
    }

    private JsonNode readSingleJsonValue(String content) throws IOException {
        if (content == null || content.isBlank()) {
            throw new ValidationException("model returned an empty response");
        }
        try (JsonParser parser = objectMapper.createParser(content)) {
            JsonNode root = objectMapper.readTree(parser);
            if (root == null || parser.nextToken() != null) {
                throw new ValidationException("response must contain exactly one JSON value");
            }
            return root;
        }
    }

    private void requireExactFields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) {
            throw new ValidationException("JSON value must be an object");
        }
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new ValidationException("JSON object contains missing or unexpected fields");
        }
    }

    private String text(JsonNode node, int maxLength, boolean blankAllowed) {
        if (node == null || !node.isTextual()) {
            throw new ValidationException("expected a string field");
        }
        String value = node.textValue().trim();
        if ((!blankAllowed && value.isEmpty()) || value.length() > maxLength) {
            throw new ValidationException("string field is blank or too long");
        }
        return value;
    }

    private List<String> textArray(JsonNode node, int maxItems, int maxItemLength) {
        if (node == null || !node.isArray() || node.size() > maxItems) {
            throw new ValidationException("invalid string array");
        }
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode item : node) values.add(text(item, maxItemLength, false));
        return List.copyOf(values);
    }

    private void requireSourceBacked(List<String> values, String evidence, String field) {
        for (String value : values) {
            if (!value.isBlank() && !evidence.contains(value)) {
                throw new ValidationException(field + " is not present in cited evidence");
            }
        }
    }

    private List<String> sourceBackedValues(List<String> values, String evidence) {
        return values.stream()
            .filter(value -> !value.isBlank() && evidence.contains(value))
            .distinct()
            .toList();
    }

    private StructuredKnowledgeUnit.BusinessMetadata sourceBackedMetadata(
            StructuredKnowledgeUnit.BusinessMetadata metadata, String evidence) {
        return new StructuredKnowledgeUnit.BusinessMetadata(
            sourceBackedOrBlank(metadata.product(), evidence),
            sourceBackedOrBlank(metadata.channel(), evidence),
            sourceBackedOrBlank(metadata.audience(), evidence),
            metadata.riskLevel(),
            sourceBackedOrBlank(metadata.effectiveFrom(), evidence),
            sourceBackedOrBlank(metadata.effectiveTo(), evidence));
    }

    private String sourceBackedOrBlank(String value, String evidence) {
        return value != null && !value.isBlank() && evidence.contains(value) ? value : "";
    }

    private String preserveAnchor(String value, List<String> anchors) {
        if (anchors.isEmpty() || anchors.stream().anyMatch(value::contains)) return value;
        return boundedAppend(anchors.get(0), value);
    }

    private String preserveQualifiers(String value, List<String> conditions,
                                      List<String> exclusions) {
        String result = value;
        for (String qualifier : combine(conditions, exclusions)) {
            if (!result.contains(qualifier)) result = boundedAppend(result, qualifier);
        }
        return result;
    }

    private String boundedAppend(String left, String right) {
        String result = left + " " + right;
        if (result.length() > 1000) {
            throw new ValidationException("generated query exceeds the configured limit");
        }
        return result;
    }

    private void requireProtectedTokensFromEvidence(String generated, String evidence) {
        Set<String> sourceIdentifiers = matches(IDENTIFIER, evidence);
        Set<String> sourceNumbers = matches(NUMBER, evidence);
        if (!sourceIdentifiers.containsAll(matches(IDENTIFIER, generated))
                || !sourceNumbers.containsAll(matches(NUMBER, generated))) {
            throw new ValidationException("generated content introduced an identifier or number");
        }
    }

    private void requirePolarityPreserved(String generated, String statement) {
        boolean generatedNegative = NEGATION.matcher(generated).find();
        boolean statementNegative = NEGATION.matcher(statement).find();
        if (generatedNegative && !statementNegative) {
            throw new ValidationException("generated query introduced unsupported negation");
        }
        if (statementNegative && !generatedNegative
                && !NEUTRAL_QUESTION.matcher(generated).find()) {
            throw new ValidationException("generated query dropped negative polarity");
        }
    }

    private void requireQualifiersPreserved(String generated, List<String> conditions,
                                             List<String> exclusions, String field) {
        for (String qualifier : combine(conditions, exclusions)) {
            if (!qualifier.isBlank() && !generated.contains(qualifier)) {
                throw new ValidationException(field + " dropped a condition or exclusion");
            }
        }
    }

    private boolean evidenceCovers(
            String statement, List<StructuredKnowledgeUnit.SourceSpan> spans) {
        return spans.stream().anyMatch(span -> span.quote().contains(statement));
    }

    private boolean sharesTopic(String left, String right) {
        String normalizedLeft = normalize(left);
        String normalizedRight = normalize(right);
        if (normalizedLeft.isBlank() || normalizedRight.isBlank()) return false;
        if (normalizedLeft.contains(normalizedRight) || normalizedRight.contains(normalizedLeft)) {
            return true;
        }
        Set<String> leftLatin = latinTerms(normalizedLeft);
        Set<String> rightLatin = latinTerms(normalizedRight);
        Set<String> sharedLatin = new HashSet<>(leftLatin);
        sharedLatin.retainAll(rightLatin);
        if (!sharedLatin.isEmpty()) return true;

        Set<String> leftBigrams = cjkBigrams(left);
        Set<String> rightBigrams = cjkBigrams(right);
        leftBigrams.retainAll(rightBigrams);
        if (!leftBigrams.isEmpty()) return true;

        Set<Integer> leftChars = cjkChars(left);
        Set<Integer> rightChars = cjkChars(right);
        leftChars.retainAll(rightChars);
        return leftChars.size() >= 2;
    }

    private Set<String> latinTerms(String value) {
        Set<String> terms = matches(LATIN_TERM, value);
        terms.removeAll(LATIN_STOP_WORDS);
        return terms;
    }

    private Set<String> cjkBigrams(String value) {
        List<Integer> chars = value.codePoints()
            .filter(codePoint -> Character.UnicodeScript.of(codePoint)
                == Character.UnicodeScript.HAN)
            .boxed().toList();
        Set<String> values = new HashSet<>();
        for (int i = 1; i < chars.size(); i++) {
            values.add(new String(new int[]{chars.get(i - 1), chars.get(i)}, 0, 2));
        }
        return values;
    }

    private Set<Integer> cjkChars(String value) {
        Set<Integer> values = new HashSet<>();
        value.codePoints()
            .filter(codePoint -> Character.UnicodeScript.of(codePoint)
                == Character.UnicodeScript.HAN)
            .forEach(values::add);
        return values;
    }

    private Set<String> matches(Pattern pattern, String value) {
        Set<String> values = new HashSet<>();
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) values.add(matcher.group().toLowerCase(Locale.ROOT));
        return values;
    }

    @SafeVarargs
    private final List<String> combine(List<String>... lists) {
        List<String> values = new ArrayList<>();
        for (List<String> list : lists) values.addAll(list);
        return values;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
            .replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }

    private double round5(double value) {
        return Math.round(value * 100_000.0) / 100_000.0;
    }

    private record Evidence(List<Long> chunkIds,
                            List<StructuredKnowledgeUnit.SourceSpan> spans,
                            String sourceText) {}

    public record UnitRejection(int unitIndex, String message) {}

    public record ParseResult(List<StructuredKnowledgeUnit> units,
                              List<UnitRejection> rejections) {}

    public static class ValidationException extends IllegalArgumentException {
        public ValidationException(String message) {
            super(message);
        }
    }
}
