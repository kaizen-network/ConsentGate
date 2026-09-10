package io.github.consentgate.core;

import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** One connection's temporary prototype decision. This is not a stored acceptance. */
public final class GateSession {
    public enum Decision { ACCEPTED, DECLINED, TIMED_OUT, DISCONNECTED, SHUTDOWN, FAILED }

    private final String token = UUID.randomUUID().toString();
    private final CompletableFuture<Decision> result = new CompletableFuture<>();

    public String token() { return token; }
    public CompletionStage<Decision> result() { return result.minimalCompletionStage(); }
    public boolean pending() { return !result.isDone(); }

    public boolean accept(String suppliedToken, boolean checked) {
        return token.equals(suppliedToken) && checked && result.complete(Decision.ACCEPTED);
    }

    public boolean decline(String suppliedToken) {
        return token.equals(suppliedToken) && result.complete(Decision.DECLINED);
    }

    public boolean end(Decision reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason == Decision.ACCEPTED || reason == Decision.DECLINED) {
            throw new IllegalArgumentException("Player decisions require a validated token");
        }
        return result.complete(reason);
    }
}
