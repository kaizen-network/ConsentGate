package io.github.consentgate.core.admission;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class AdmissionSession {
    public enum Decision { ACCEPTED, DECLINED, TIMED_OUT, DISCONNECTED, SHUTDOWN, FAILED }

    private final String token = UUID.randomUUID().toString();
    private final AdmissionRequest request;
    private final Map<String, Boolean> selections = new LinkedHashMap<>();
    private final CompletableFuture<Decision> result = new CompletableFuture<>();
    private volatile Decision decision;

    public AdmissionSession(AdmissionRequest request) {
        this.request = Objects.requireNonNull(request, "request");
        for (AdmissionDocument document : request.documents()) selections.put(document.id(), false);
        if (selections.size() != request.documents().size()) {
            throw new IllegalArgumentException("Admission request contains duplicate document ids");
        }
    }

    public String token() { return token; }
    public AdmissionRequest request() { return request; }
    public CompletionStage<Decision> result() { return result.minimalCompletionStage(); }
    public boolean pending() { return decision == null; }
    public boolean accepted() { return decision == Decision.ACCEPTED; }

    public synchronized Map<String, Boolean> selections() { return Map.copyOf(selections); }

    public synchronized boolean updateSelections(String suppliedToken, Map<String, Boolean> supplied) {
        if (supplied == null || !pending() || !token.equals(suppliedToken) || !selections.keySet().equals(supplied.keySet())
                || supplied.values().stream().anyMatch(Objects::isNull)) return false;
        selections.putAll(supplied);
        return true;
    }

    public boolean accept(String suppliedToken, Map<String, Boolean> supplied) {
        synchronized (this) {
            if (!updateSelections(suppliedToken, supplied) || selections.containsValue(false)) return false;
            decision = Decision.ACCEPTED;
        }
        result.complete(Decision.ACCEPTED);
        return true;
    }

    public boolean decline(String suppliedToken) {
        return token.equals(suppliedToken) && decide(Decision.DECLINED);
    }

    public boolean end(Decision reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason == Decision.ACCEPTED || reason == Decision.DECLINED) {
            throw new IllegalArgumentException("Player decisions require a validated token");
        }
        return decide(reason);
    }

    private boolean decide(Decision reason) {
        synchronized (this) {
            if (decision != null) return false;
            decision = reason;
        }
        // Platform callbacks can acquire connection locks or submit database work.
        result.complete(reason);
        return true;
    }
}
