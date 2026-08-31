package com.feisheng.bot.admin;

import com.feisheng.bot.admin.service.KnowledgeMigrationObservability;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgeMigrationObservabilityTest {

    @Test
    void recordsTransitionFailuresConflictCountsAndReleaseOutcomes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KnowledgeMigrationObservability observability = new KnowledgeMigrationObservability(registry);

        observability.transition(42L, "contracts", 1L, 2L, "EXTRACTING", "CONFLICT_CHECKING", 25L);
        observability.queueWait(42L, "contracts", 1L, 2L, -25L);
        observability.failure(42L, "contracts", 1L, 2L, "EXTRACTING", "extraction");
        observability.conflicts(42L, "contracts", 1L, 2L, 3, 2, 1);
        observability.release(42L, "contracts", 1L, 2L, true, false);
        observability.release(42L, "contracts", 2L, 1L, true, true);

        assertEquals(1.0, registry.get("knowledge.migration.stage.duration").timer().count());
        assertEquals(1.0, registry.get("knowledge.migration.queue.wait").timer().count());
        assertEquals(0L, registry.get("knowledge.migration.queue.wait").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS));
        assertEquals(1.0, registry.get("knowledge.migration.failures").tag("kind", "extraction").counter().count());
        assertEquals(3.0, registry.get("knowledge.migration.candidates").counter().count());
        assertEquals(2.0, registry.get("knowledge.migration.conflicts").counter().count());
        assertEquals(1.0, registry.get("knowledge.migration.unknown_judgments").counter().count());
        assertEquals(1.0, registry.get("knowledge.migration.switch.success").counter().count());
        assertEquals(1.0, registry.get("knowledge.migration.rollbacks").counter().count());
    }
}
