package io.github.consentgate.core.admin;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PlayerOperationsTest {
    @Test void resetWaitsForAnOutstandingSave() throws Exception {
        var operations = new PlayerOperations();
        var player = UUID.randomUUID();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var resetStarted = new CountDownLatch(1);
        var order = new ArrayList<String>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var save = executor.submit(() -> operations.run(player, () -> {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException ex) { throw new AssertionError(ex); }
                order.add("save");
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var reset = executor.submit(() -> {
                resetStarted.countDown();
                operations.run(player, () -> order.add("reset"));
            });
            try {
                assertTrue(resetStarted.await(5, TimeUnit.SECONDS));
                assertFalse(reset.isDone());
            } finally {
                release.countDown();
            }
            save.get(5, TimeUnit.SECONDS);
            reset.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("save", "reset"), order);
        }
    }
}
