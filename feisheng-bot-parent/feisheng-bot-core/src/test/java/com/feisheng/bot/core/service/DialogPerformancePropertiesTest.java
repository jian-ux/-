package com.feisheng.bot.core.service;

import com.feisheng.bot.core.config.DialogPerformanceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DialogPerformancePropertiesTest {
    @Test
    void bindsOperationalDefaultsAndOverrides() {
        DialogPerformanceProperties properties = new Binder(new MapConfigurationPropertySource(Map.of(
            "dialog.performance.context-core-threads", "4",
            "dialog.performance.recall-deadline-ms", "300",
            "dialog.performance.model-read-timeout-ms", "45000"
        ))).bind("dialog.performance", Bindable.of(DialogPerformanceProperties.class)).get();
        assertEquals(4, properties.getContextCoreThreads());
        assertEquals(300, properties.getRecallDeadlineMs());
        assertEquals(45000, properties.getModelReadTimeoutMs());
    }

    @Test
    void rejectsNonPositiveLimits() {
        DialogPerformanceProperties properties = new DialogPerformanceProperties();
        assertThrows(IllegalArgumentException.class, () -> properties.setContextCoreThreads(0));
    }
}
