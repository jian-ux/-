package com.feisheng.bot.core.service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Immutable request context boundary used by the dialog pipeline stages. */
public final class DialogContextLoader {
    public RequestContext load(String channelType, String channelUserId, String text,
                               String title, String providedContext,
                               List<Map<String, Object>> providedCitations,
                               String modalityContext) {
        return new RequestContext(channelType, channelUserId, text, title,
            providedContext, providedCitations, modalityContext);
    }

    public record RequestContext(
            String channelType,
            String channelUserId,
            String text,
            String title,
            String providedContext,
            List<Map<String, Object>> providedCitations,
            String modalityContext) {
        public RequestContext {
            providedCitations = providedCitations == null
                ? List.of() : List.copyOf(providedCitations);
        }
    }
}
