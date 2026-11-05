package io.github.consentgate.velocity;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OpenConnectionTest {
    @Test void sendsWhileOpen() {
        var sent = new AtomicInteger();
        OpenConnection.send(() -> true, sent::incrementAndGet);
        assertEquals(1, sent.get());
    }

    @Test void skipsAClosedConnection() {
        var sent = new AtomicInteger();
        OpenConnection.send(() -> false, sent::incrementAndGet);
        assertEquals(0, sent.get());
    }

    @Test void ignoresAFailureFromAConnectionThatClosedDuringTheSend() {
        var open = new AtomicBoolean(true);
        assertDoesNotThrow(() -> OpenConnection.send(open::get, () -> {
            open.set(false);
            throw new NullPointerException("user is null");
        }));
    }

    @Test void reportsAFailureWhileStillOpen() {
        assertThrows(IllegalStateException.class, () -> OpenConnection.send(() -> true, () -> {
            throw new IllegalStateException("encoder failed");
        }));
    }
}
