package io.github.consentgate.velocity;

import java.util.UUID;
import java.util.stream.IntStream;

/** Bounded locks keep a disconnected player's outstanding save ahead of an admin reset. */
final class PlayerOperations {
    private final Object[] locks = IntStream.range(0, 64).mapToObj(index -> new Object()).toArray();

    void run(UUID playerId, Runnable operation) {
        synchronized (locks[Math.floorMod(playerId.hashCode(), locks.length)]) {
            operation.run();
        }
    }
}
