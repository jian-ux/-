package com.feisheng.bot.core.service;

import com.feisheng.bot.core.dto.QueryVariant;
import com.feisheng.bot.core.service.impl.RagRetrievalService;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Coordinates retrieval overload selection while keeping customer context out of cacheable facts. */
public final class DialogRetrievalCoordinator {
    private final RagRetrievalService retrievalService;

    public DialogRetrievalCoordinator(RagRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    public RagRetrievalService.RetrievalResult retrieve(
            String primaryQuery,
            List<QueryVariant> supplementalVariants,
            String conversationContext,
            String modalityContext,
            Map<String, Object> filters,
            boolean trackHit) {
        String query = primaryQuery == null ? "" : primaryQuery.trim();
        List<QueryVariant> variants = supplementalVariants == null
            ? Collections.emptyList() : List.copyOf(supplementalVariants);
        Map<String, Object> safeFilters = filters == null ? Collections.emptyMap() : filters;
        if (variants.isEmpty()) {
            if ((conversationContext == null || conversationContext.isBlank())
                    && (modalityContext == null || modalityContext.isBlank())) {
                return retrievalService.retrieve(query, safeFilters, trackHit);
            }
            return retrievalService.retrieve(query, conversationContext, modalityContext,
                safeFilters, trackHit);
        }
        return retrievalService.retrieve(query, conversationContext, modalityContext,
            safeFilters, variants, trackHit);
    }
}
