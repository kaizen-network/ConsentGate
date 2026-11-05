package io.github.consentgate.velocity;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class DenialCompletion {
    private DenialCompletion() { }

    static void releaseThenDisconnect(Runnable release, BooleanSupplier active, Runnable disconnect,
                                      Consumer<RuntimeException> failure) {
        Objects.requireNonNull(release, "release").run();
        try {
            if (Objects.requireNonNull(active, "active").getAsBoolean()) {
                Objects.requireNonNull(disconnect, "disconnect").run();
            }
        } catch (RuntimeException ex) {
            Objects.requireNonNull(failure, "failure").accept(ex);
        }
    }
}
