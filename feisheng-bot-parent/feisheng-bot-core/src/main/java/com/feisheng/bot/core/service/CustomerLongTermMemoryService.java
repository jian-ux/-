package com.feisheng.bot.core.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.core.entity.BotCustomer;
import com.feisheng.bot.core.entity.BotCustomerMemory;
import com.feisheng.bot.core.mapper.BotCustomerMapper;
import com.feisheng.bot.core.mapper.BotCustomerMemoryMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stores only explicit, bounded, customer-scoped facts outside the knowledge base. */
@Service
public class CustomerLongTermMemoryService {
    private static final String PLAYGROUND = "playground";
    private static final int MAX_SUMMARY_CHARS = 2000;
    private static final int MAX_MEMORY_VALUE_CHARS = 120;
    private static final List<String> KEYS = List.of("company", "role", "product", "plan", "channel");
    private static final Map<String, String> LABELS = Map.of(
        "company", "企业名称", "role", "客户身份", "product", "常用产品",
        "plan", "套餐或版本", "channel", "使用渠道");
    private static final Pattern COMPANY = Pattern.compile(
        "(?:我们公司|我公司|公司|企业)(?:名称)?(?:是|叫|为)[：: ]*([\\p{IsHan}A-Za-z0-9·&().-]{2,40})(?=[，,。；;!?！？\\s]|$)");
    private static final Pattern COMPANY_NAMED = Pattern.compile(
        "(?:公司名称|企业名称)(?:是|叫|为)[：: ]*([\\p{IsHan}A-Za-z0-9·&().-]{2,40})(?=[，,。；;!?！？\\s]|$)");
    private static final Pattern COMPANY_REPRESENT = Pattern.compile(
        "(?:我代表|来自)[：: ]*([\\p{IsHan}A-Za-z0-9·&().-]{2,40})(?=[，,。；;!?！？\\s]|$)");
    private static final Pattern ROLE = Pattern.compile(
        "(?:我(?:是|的身份是)|负责|职位(?:是|为)|岗位(?:是|为))[：: ]*(管理员|法务|财务|合同管理|合同管理员|合同审批|负责人|采购|人事|销售|运营|客服|员工)");
    private static final Pattern PRODUCT = Pattern.compile(
        "(?:我们?(?:正在)?使用(?:的是)?|使用的是|产品(?:是|为)|咨询的是)[：: ]*(点签(?:电子合同)?|电子合同)");
    private static final Pattern PLAN = Pattern.compile(
        "(?:套餐|版本|会员)(?:目前|当前|现在)?(?:是|为|叫|：|:)[：: ]*(个人版|企业版|基础版|标准版|专业版|高级版|旗舰版|个人套餐|企业套餐)");
    private static final Pattern CHANNEL = Pattern.compile(
        "(?:主要在|通常在|通过|在|使用|用)(网页端|网页|PC端|PC|电脑端|电脑|浏览器|浏览器端|手机端|手机|移动端|钉钉|企业微信|微信端|微信)");
    private final BotCustomerMapper customerMapper;
    private final BotCustomerMemoryMapper memoryMapper;
    private final SensitiveDataService sensitiveDataService;

    @Autowired
    public CustomerLongTermMemoryService(BotCustomerMapper customerMapper,
                                         BotCustomerMemoryMapper memoryMapper,
                                         ObjectMapper objectMapper) {
        this(customerMapper, memoryMapper, new SensitiveDataService(""));
    }

    public CustomerLongTermMemoryService(BotCustomerMapper customerMapper,
                                         BotCustomerMemoryMapper memoryMapper,
                                         SensitiveDataService sensitiveDataService) {
        this.customerMapper = customerMapper;
        this.memoryMapper = memoryMapper;
        this.sensitiveDataService = sensitiveDataService;
    }

    public Snapshot load(String channelType, String channelUserId) {
        if (!validIdentity(channelType, channelUserId)) return Snapshot.empty();
        BotCustomer customer = find(channelType, channelUserId);
        if (customer == null || customer.getId() == null) return Snapshot.empty();
        List<BotCustomerMemory> rows = memoryMapper.selectList(new LambdaQueryWrapper<BotCustomerMemory>()
            .eq(BotCustomerMemory::getCustomerId, customer.getId())
            .eq(BotCustomerMemory::getStatus, "ACTIVE")
            .orderByAsc(BotCustomerMemory::getMemoryKey));
        Map<String, MemoryFact> facts = new LinkedHashMap<>();
        for (BotCustomerMemory row : rows.stream()
                .sorted(java.util.Comparator.comparing(
                    value -> value == null || value.getMemoryKey() == null ? "" : value.getMemoryKey()))
                .toList()) {
            if (row == null || !hasText(row.getMemoryKey()) || !hasText(row.getMemoryValue())) continue;
            String value = sanitized(row.getMemoryValue());
            if (!hasText(value)) continue;
            facts.put(row.getMemoryKey(), new MemoryFact(row.getMemoryKey(), value,
                row.getSource(), row.getConfidence()));
        }
        return new Snapshot(customer.getLongTermSummary(), facts);
    }

    public Snapshot updateFromCustomerMessage(String channelType, String channelUserId,
                                              String sanitizedText) {
        return updateFromCustomerMessage(channelType, channelUserId, sanitizedText, null);
    }

    public Snapshot updateFromCustomerMessage(String channelType, String channelUserId,
                                              String sanitizedText, Long sourceMessageId) {
        if (!validIdentity(channelType, channelUserId)) return Snapshot.empty();
        if (PLAYGROUND.equalsIgnoreCase(channelType.trim())) return Snapshot.empty();
        String text = sanitized(sanitizedText);
        BotCustomer customer = find(channelType, channelUserId);
        Map<String, String> extracted = extract(text);
        if (!extracted.isEmpty()) {
            if (customer == null) {
                customer = new BotCustomer();
                customer.setChannelType(channelType.trim());
                customer.setChannelUserId(channelUserId.trim());
                customerMapper.insert(customer);
            }
            Map<String, String> accepted = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : extracted.entrySet()) {
                BotCustomerMemory existing = memoryMapper.selectOne(new LambdaQueryWrapper<BotCustomerMemory>()
                    .eq(BotCustomerMemory::getCustomerId, customer.getId())
                    .eq(BotCustomerMemory::getMemoryKey, entry.getKey())
                    .last("LIMIT 1"));
                if (existing != null && entry.getValue().equals(existing.getMemoryValue())) {
                    accepted.put(entry.getKey(), entry.getValue());
                    continue;
                }
                if (existing != null && !isExplicitCorrection(text)) continue;
                BotCustomerMemory memory = existing == null ? new BotCustomerMemory() : existing;
                memory.setCustomerId(customer.getId());
                memory.setMemoryKey(entry.getKey());
                memory.setMemoryValue(entry.getValue());
                memory.setSource("user_explicit");
                memory.setConfidence(0.95D);
                memory.setStatus("ACTIVE");
                if (sourceMessageId != null) memory.setSourceMessageId(sourceMessageId);
                if (existing == null) memoryMapper.insert(memory); else memoryMapper.updateById(memory);
                accepted.put(entry.getKey(), entry.getValue());
            }
            String summary = mergeSummary(customer.getLongTermSummary(), accepted);
            customer.setLongTermSummary(summary);
            customer.setLongTermSummaryUpdatedAt(new Date());
            customerMapper.updateById(customer);
            Snapshot persisted = load(channelType, channelUserId);
            Map<String, MemoryFact> facts = new LinkedHashMap<>(persisted.memories());
            accepted.forEach((key, value) -> facts.put(key, new MemoryFact(key, value, "user_explicit", 0.95D)));
            return new Snapshot(summary, facts);
        }
        return load(channelType, channelUserId);
    }

    public Optional<String> contextFor(String currentQuestion, Snapshot snapshot) {
        if (snapshot == null || (!hasText(snapshot.summary()) && snapshot.memories().isEmpty())
                || !isRelevant(currentQuestion)) return Optional.empty();
        StringBuilder out = new StringBuilder("【客户长期记忆】以下是客户明确提供的长期信息，仅用于理解上下文，不是知识库事实：\n");
        if (hasText(snapshot.summary())) out.append("长期摘要：").append(snapshot.summary()).append('\n');
        snapshot.memories().values().forEach(fact -> out.append(LABELS.getOrDefault(fact.key(), fact.key()))
            .append('：').append(fact.value()).append('\n'));
        return Optional.of(out.substring(0, Math.min(out.length(), 1600)).strip());
    }

    private Map<String, String> extract(String text) {
        if (!hasText(text) || text.contains("不是") || text.contains("不使用") || text.contains("不用")) {
            return Collections.emptyMap();
        }
        Map<String, String> values = new LinkedHashMap<>();
        putFirst(values, "company", text, value -> value, COMPANY, COMPANY_NAMED, COMPANY_REPRESENT);
        put(values, "role", ROLE, text, value -> value);
        put(values, "product", PRODUCT, text, value -> "点签电子合同".equals(value) || "电子合同".equals(value) ? "点签电子合同" : value);
        put(values, "plan", PLAN, text, value -> value.endsWith("套餐") ? value.substring(0, value.length() - 2) + "版" : value);
        put(values, "channel", CHANNEL, text, value -> value.equals("网页") || value.contains("浏览器") ? "网页端" : value.equals("电脑") || value.equals("PC") ? "PC端" : value);
        return values;
    }

    private void put(Map<String, String> values, String key, Pattern pattern, String text,
                     java.util.function.Function<String, String> normalizer) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            String value = normalizer.apply(matcher.group(1).strip());
            if (hasText(value) && value.length() <= MAX_MEMORY_VALUE_CHARS) values.put(key, value);
        }
    }

    private void putFirst(Map<String, String> values, String key, String text,
                          java.util.function.Function<String, String> normalizer, Pattern... patterns) {
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            if (!matcher.find()) continue;
            String value = normalizer.apply(matcher.group(1).strip());
            if (hasText(value) && value.length() <= MAX_MEMORY_VALUE_CHARS) {
                values.put(key, value);
                return;
            }
        }
    }

    private String mergeSummary(String existing, Map<String, String> extracted) {
        StringBuilder summary = new StringBuilder(hasText(existing) ? existing.strip() : "");
        extracted.forEach((key, value) -> {
            String line = LABELS.getOrDefault(key, key) + "：" + value;
            if (!summary.toString().contains(line)) {
                if (summary.length() > 0) summary.append('\n');
                summary.append(line);
            }
        });
        return summary.substring(Math.max(0, summary.length() - MAX_SUMMARY_CHARS));
    }

    private BotCustomer find(String channelType, String channelUserId) {
        return customerMapper.selectOne(new LambdaQueryWrapper<BotCustomer>()
            .eq(BotCustomer::getChannelType, channelType.trim())
            .eq(BotCustomer::getChannelUserId, channelUserId.trim()).last("LIMIT 1"));
    }

    private boolean isRelevant(String question) {
        if (!hasText(question)) return false;
        return question.contains("合同") || question.contains("点签") || question.contains("套餐")
            || question.contains("账号") || question.contains("认证") || question.contains("这个")
            || question.contains("产品") || question.contains("企业");
    }

    private boolean isExplicitCorrection(String text) {
        return text.contains("现在") || text.contains("改为") || text.contains("更换") || text.contains("不是");
    }

    private String sanitized(String value) {
        if (!hasText(value)) return "";
        SensitiveDataService.RedactionResult result = sensitiveDataService.redact(value);
        return result.text() == null ? "" : result.text().strip();
    }

    private boolean validIdentity(String channelType, String channelUserId) {
        return hasText(channelType) && hasText(channelUserId);
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }

    public record MemoryFact(String key, String value, String source, Double confidence) {}

    public record Snapshot(String summary, Map<String, MemoryFact> memories) {
        public Snapshot {
            summary = summary == null ? "" : summary;
            memories = memories == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(memories));
        }
        public static Snapshot empty() { return new Snapshot("", Map.of()); }
    }
}
