package io.github.consentgate.core.admission;

import io.github.consentgate.core.GateSession;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class AdmissionSession {
    private final String token = UUID.randomUUID().toString();
    private final AdmissionRequest request;
    private final Map<String, Boolean> selections = new LinkedHashMap<>();
    private final CompletableFuture<GateSession.Decision> result = new CompletableFuture<>();

    public AdmissionSession(AdmissionRequest request) {
        this.request = Objects.requireNonNull(request, "request");
        for (AdmissionDocument document : request.documents()) selections.put(document.id(), false);
        if (selections.size() != request.documents().size()) {
            throw new IllegalArgumentException("Admission request contains duplicate document ids");
        }
    }

    public String token() { return token; }
    public AdmissionRequest request() { return request; }
    public CompletionStage<GateSession.Decision> result() { return result.minimalCompletionStage(); }
    public boolean pending() { return !result.isDone(); }
    public boolean accepted() { return result.getNow(null) == GateSession.Decision.ACCEPTED; }

    public synchronized Map<String, Boolean> selections() { return Map.copyOf(selections); }

    public synchronized boolean updateSelections(String suppliedToken, Map<String, Boolean> supplied) {
        if (supplied == null || !pending() || !token.equals(suppliedToken) || !selections.keySet().equals(supplied.keySet())
                || supplied.values().stream().anyMatch(Objects::isNull)) return false;
        selections.putAll(supplied);
        return true;
    }

    public synchronized boolean accept(String suppliedToken, Map<String, Boolean> supplied) {
        if (!updateSelections(suppliedToken, supplied) || selections.containsValue(false)) return false;
        return result.complete(GateSession.Decision.ACCEPTED);
    }

    public synchronized boolean decline(String suppliedToken) {
        return token.equals(suppliedToken) && result.complete(GateSession.Decision.DECLINED);
    }

    public synchronized boolean end(GateSession.Decision reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason == GateSession.Decision.ACCEPTED || reason == GateSession.Decision.DECLINED) {
            throw new IllegalArgumentException("Player decisions require a validated token");
        }
        return result.complete(reason);
    }
}
