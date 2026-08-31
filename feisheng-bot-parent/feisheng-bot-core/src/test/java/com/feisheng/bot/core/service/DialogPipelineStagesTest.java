package com.feisheng.bot.core.service;

import com.feisheng.bot.core.dto.QueryVariant;
import com.feisheng.bot.core.service.impl.RagRetrievalService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DialogPipelineStagesTest {
    @Test
    void contextLoaderReturnsImmutableCitations() {
        DialogContextLoader.RequestContext context = new DialogContextLoader().load(
            "web", "u1", "问题", "标题", null,
            List.of(Map.of("title", "事实")), null);
        assertEquals("web", context.channelType());
        assertThrows(UnsupportedOperationException.class,
            () -> context.providedCitations().add(Map.of()));
    }

    @Test
    void coordinatorSelectsVariantAwareRetrieval() {
        RagRetrievalService service = mock(RagRetrievalService.class);
        RagRetrievalService.RetrievalResult expected = mock(RagRetrievalService.RetrievalResult.class);
        when(service.retrieve(eq("问题"), eq("历史"), isNull(), anyMap(), anyList(), eq(true)))
            .thenReturn(expected);
        RagRetrievalService.RetrievalResult actual = new DialogRetrievalCoordinator(service)
            .retrieve("问题", List.of(new QueryVariant("问题扩展", 0.8, "semantic", false)),
                "历史", null, Map.of("sourceScope", "KNOWLEDGE"), true);
        assertSame(expected, actual);
        verify(service).retrieve(eq("问题"), eq("历史"), isNull(), anyMap(), anyList(), eq(true));
    }

    @Test
    void responsePostProcessorKeepsProtocolParserInOnePlace() {
        DialogResponsePostProcessor processor = new DialogResponsePostProcessor(null);
        assertTrue(processor.parseModelOutput("答案").isAnswer());
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        processor.applyMetadata(response, new DialogResponseMetadata(Map.of("source", "faq")));
        assertEquals("faq", response.get("source"));
    }
}
