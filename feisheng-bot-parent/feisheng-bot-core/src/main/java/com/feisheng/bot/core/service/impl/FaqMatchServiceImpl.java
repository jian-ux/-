package com.feisheng.bot.core.service.impl;
import com.feisheng.bot.core.client.KnowledgeClient;
import org.springframework.stereotype.Service;
import java.util.Collections;
import java.util.Map;
@Service
public class FaqMatchServiceImpl {
    private final KnowledgeClient knowledgeClient;
    public FaqMatchServiceImpl(KnowledgeClient kc) { this.knowledgeClient = kc; }
    public Map<String,Object> match(String text) { return knowledgeClient.match(text); }
    public Map<String,Object> match(String text, boolean trackHit) {
        return knowledgeClient.match(text, trackHit);
    }
    public Map<String,Object> match(String text, boolean trackHit, Map<String,Object> filters) {
        return knowledgeClient.match(text, trackHit,
            filters == null ? Collections.emptyMap() : filters);
    }
}
