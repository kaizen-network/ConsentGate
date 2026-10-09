package io.github.consentgate.paper;

import io.github.consentgate.core.admin.PlayerOperations;
import io.github.consentgate.core.admin.PreviewQueue;
import io.github.consentgate.core.admission.AdmissionSession;
import io.github.consentgate.core.admission.AdmissionRequest;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Keeps the configuration hold until storage and presentation have finished successfully. */
abstract class PaperAdmission {
    final UUID id;
    final CompletableFuture<Void> done = new CompletableFuture<>();
    final AtomicBoolean finished = new AtomicBoolean();
    volatile AdmissionSession session;
    private final Executor database;
    private final PlayerOperations players;
    private final BooleanSupplier stopping;
    private final Consumer<Throwable> failure;

    PaperAdmission(UUID id, Executor database, PlayerOperations players, BooleanSupplier stopping, Consumer<Throwable> failure) {
        this.id = id;
        this.database = database;
        this.players = players;
        this.stopping = stopping;
        this.failure = failure;
    }

    final synchronized void begin(AdmissionRequest request, Consumer<AdmissionSession> show, Operation save, Supplier<String> denied) {
        if (finished.get()) return;
        if (session != null) throw new IllegalStateException("Consent session already started");
        session = new AdmissionSession(request);
        session.result().whenComplete((decision, error) -> {
            if (error == null && decision == AdmissionSession.Decision.ACCEPTED) {
                if (request.preview()) finish(PreviewQueue.COMPLETE);
                else execute("error-save", () -> { save.run(); finish(null); });
            } else finish(denied.get());
        });
        try { show.accept(session); }
        catch (RuntimeException | LinkageError ex) {
            try { failure.accept(ex); }
            finally { finish(message("error-form")); }
        }
    }

    final void execute(String failureKey, Operation operation) {
        try {
            database.execute(() -> players.run(id, () -> {
                if (finished.get() || stopping.getAsBoolean()) { finish(message("error-stopping")); return; }
                try { operation.run(); }
                catch (Exception | LinkageError ex) {
                    try { failure.accept(ex); }
                    finally { finish(message(failureKey)); }
                }
            }));
        } catch (RejectedExecutionException ex) { finish(message("error-busy")); }
    }

    final void finish(String denial) {
        AdmissionSession ending = null;
        try {
            synchronized (this) {
                if (!finished.compareAndSet(false, true)) return;
                ending = session;
                try {
                    if (denial == null && stopping.getAsBoolean()) denial = message("error-stopping");
                    try { closePresentation(); }
                    catch (RuntimeException | LinkageError ex) {
                        if (denial == null) denial = message("error-form");
                        try { failure.accept(ex); }
                        catch (RuntimeException | LinkageError ignored) { }
                    }
                    if (denial != null) disconnect(denial);
                    else admitted();
                } finally { done.complete(null); }
            }
        } finally {
            // A native response can hold the session lock while its callback completes this connection.
            if (ending != null && ending.pending()) ending.end(AdmissionSession.Decision.DISCONNECTED);
        }
    }

    protected abstract String message(String key);
    protected abstract void closePresentation();
    protected abstract void disconnect(String reason);
    protected abstract void admitted();
    @FunctionalInterface interface Operation { void run() throws Exception; }
}
