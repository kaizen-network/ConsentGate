package io.github.consentgate.core.admin;

import io.github.consentgate.core.document.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class PreviewQueueTest {
    private final AtomicLong clock = new AtomicLong(100);
    private final PreviewQueue queue = new PreviewQueue(clock::get);
    private final UUID player = UUID.randomUUID();
    private final DocumentCatalog catalog = new DocumentCatalog(List.of(new DocumentRevision("rules", "v1", true, 1,
            Map.of("en-US", new DocumentTranslation("Rules", "Summary", "Agree", "Read",
                    List.of(new DocumentPage("Page", "Text")), "a".repeat(64))))));

    @Test void consumesOnlyTheMatchingPlayerOnceAndNormalizesExplicitLocale() {
        queue.schedule(player, "EN_us", catalog);
        assertTrue(queue.take(UUID.randomUUID()).isEmpty());
        assertEquals("en-US", queue.take(player).orElseThrow().locale());
        assertTrue(queue.take(player).isEmpty());
        queue.schedule(player, null, catalog);
        assertNull(queue.take(player).orElseThrow().locale());
        assertThrows(IllegalArgumentException.class, () -> queue.schedule(player, "id-ID", catalog));
        assertTrue(queue.take(player).isEmpty());
    }

    @Test void expiresCancelsAndClearsWithoutWallClockDependence() {
        queue.schedule(player, null, catalog);
        clock.addAndGet(Duration.ofMinutes(5).toNanos());
        assertTrue(queue.take(player).isEmpty());
        queue.schedule(player, null, catalog);
        queue.schedule(player, "cancel", catalog);
        assertTrue(queue.take(player).isEmpty());
        queue.schedule(player, null, catalog);
        queue.clear();
        assertTrue(queue.take(player).isEmpty());
    }

    @Test void boundsCapacityButAllowsReplacementAndReclaimsExpiredSlots() {
        queue.schedule(player, null, catalog);
        for (int index = 1; index < 128; index++) queue.schedule(UUID.randomUUID(), null, catalog);
        assertThrows(IllegalArgumentException.class, () -> queue.schedule(UUID.randomUUID(), null, catalog));
        queue.schedule(player, "en-US", catalog);
        clock.addAndGet(Duration.ofMinutes(5).toNanos());
        queue.schedule(UUID.randomUUID(), null, catalog);
        assertTrue(queue.take(player).isEmpty());
    }
}
