package io.github.consentgate.velocity;

import java.util.function.BooleanSupplier;

/**
 * Sends to a connection only while it is open. PacketEvents drops a user when its channel closes, and a later
 * send then fails inside PacketEvents with a NullPointerException.
 */
final class OpenConnection {
    private OpenConnection() { }

    static void send(BooleanSupplier open, Runnable send) {
        if (!open.getAsBoolean()) return;
        try {
            send.run();
        } catch (RuntimeException ex) {
            if (open.getAsBoolean()) throw ex;
        }
    }
}
