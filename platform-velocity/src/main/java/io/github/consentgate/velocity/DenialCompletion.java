package io.github.consentgate.velocity;

import java.util.Objects;
import java.util.function.Consumer;

final class DenialCompletion {
    private DenialCompletion() { }

    static void releaseThenDisconnect(Runnable release, Runnable disconnect, Consumer<RuntimeException> failure) {
        Objects.requireNonNull(release, "release").run();
        try {
            Objects.requireNonNull(disconnect, "disconnect").run();
        } catch (RuntimeException ex) {
            Objects.requireNonNull(failure, "failure").accept(ex);
        }
    }
}
