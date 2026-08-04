package com.feisheng.bot.core.client;

import com.feisheng.bot.common.vo.R;
import com.feisheng.bot.knowledge.controller.KnowledgeItemController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeClientTest {
    @Mock private KnowledgeItemController controller;

    private KnowledgeClient client;

    @BeforeEach
    void setUp() {
        client = new KnowledgeClient(controller);
    }

    @Test
    void forwardsTrustedFiltersToSemanticSearch() {
        when(controller.semanticMatch(any())).thenReturn(R.ok(Collections.emptyList()));

        client.semanticMatch("企业怎么签合同", List.of(1.0, 0.0), 10,
            Map.of("categoryId", 7L, "sourceScope", "KNOWLEDGE"));

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(controller).semanticMatch(body.capture());
        assertEquals(Map.of("categoryId", 7L, "sourceScope", "KNOWLEDGE"),
            body.getValue().get("filters"));
    }

    @Test
    void legacySearchDoesNotAddAnEmptyFilterObject() {
        when(controller.bm25Match(any())).thenReturn(R.ok(Collections.emptyList()));

        client.bm25Match("合同", 10, 0.0);

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(controller).bm25Match(body.capture());
        assertFalse(body.getValue().containsKey("filters"));
    }
}
