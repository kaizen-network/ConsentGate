package io.github.consentgate.paper;

import io.github.consentgate.core.admin.PlayerOperations;
import io.github.consentgate.core.runtime.ConsentGateRuntime;
import io.github.consentgate.core.runtime.RuntimeLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PaperAdmissionTest {
    @TempDir Path directory;
    private final UUID player = UUID.randomUUID();
    private final PlayerOperations players = new PlayerOperations();
    private final ArrayDeque<Runnable> queued = new ArrayDeque<>();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicInteger failures = new AtomicInteger();
    private ConsentGateRuntime runtime;

    @BeforeEach void setup() throws Exception {
        Files.createDirectories(directory.resolve("documents"));
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            Files.writeString(directory.resolve("config.yml"), new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("enabled: false", "enabled: true"));
        }
        try (var input = getClass().getResourceAsStream("/example-terms.yml")) {
            Files.copy(input, directory.resolve("documents/terms.yml"));
        }
        runtime = new RuntimeLoader().load(directory);
    }

    @AfterEach void close() throws Exception { runtime.close(); }

    @Test void admissionWaitsForTheCommitAndDuplicateAcceptanceDoesNotQueueAnotherSave() throws Exception {
        var probe = begin(queued::add);
        assertFalse(probe.done.isDone());
        accept(probe);
        assertFalse(probe.session.accept(probe.session.token(), Map.of("terms-of-service", true)));
        assertEquals(1, queued.size());
        assertFalse(probe.done.isDone());
        assertTrue(needsConsent());
        queued.remove().run();
        assertTrue(probe.done.isDone());
        assertEquals(1, probe.admits);
        assertEquals(0, probe.disconnects);
        assertFalse(needsConsent());
        probe.finish(null);
        assertEquals(1, probe.admits);
    }

    @Test void disconnectBeforeQueuedSaveCancelsTheWrite() throws Exception {
        var probe = begin(queued::add);
        accept(probe);
        probe.finish("Connection ended.");
        queued.remove().run();
        assertDenied(probe);
        assertTrue(needsConsent());
    }

    @Test void acceptedPreviewDisconnectsWithoutInvokingTheSaveOrDatabaseQueue() throws Exception {
        var probe = new Probe(queued::add);
        probe.begin(runtime.admissionService().orElseThrow().preview(player, "en-US"), ignored -> { },
                () -> fail("Preview invoked storage"), () -> "Declined");
        accept(probe);
        assertDenied(probe);
        assertTrue(queued.isEmpty());
        assertTrue(needsConsent());
    }

    @Test void shutdownBeforeQueuedSaveCancelsTheWrite() throws Exception {
        var probe = begin(queued::add);
        accept(probe);
        stopping.set(true);
        queued.remove().run();
        assertDenied(probe);
        assertTrue(needsConsent());
    }

    @Test void fullQueueDeniesWithoutSaving() throws Exception {
        var probe = begin(task -> { throw new RejectedExecutionException(); });
        accept(probe);
        assertDenied(probe);
        assertTrue(needsConsent());
    }

    @Test void lockedSqliteDeniesWithoutSaving() throws Exception {
        var probe = begin(queued::add);
        accept(probe);
        try (var lock = DriverManager.getConnection("jdbc:sqlite:" + runtime.config().sqliteFile());
             var statement = lock.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            queued.remove().run();
            assertDenied(probe);
            assertEquals(1, failures.get());
            statement.execute("ROLLBACK");
        }
        assertTrue(needsConsent());
    }

    @Test void disconnectDuringSaveCannotAdmitAndResetWaitsForTheEarlierWrite() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var resetAttempted = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var probe = new Probe(pool);
            probe.begin(request(), ignored -> { }, () -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                save(probe);
            }, () -> "Declined");
            accept(probe);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try {
                probe.finish("Connection ended.");
                var reset = pool.submit(() -> {
                    resetAttempted.countDown();
                    players.run(player, () -> {
                        try { runtime.admissionService().orElseThrow().reset(player, Instant.now()); }
                        catch (Exception ex) { throw new RuntimeException(ex); }
                    });
                });
                assertTrue(resetAttempted.await(5, TimeUnit.SECONDS));
                assertFalse(reset.isDone());
                release.countDown();
                reset.get(5, TimeUnit.SECONDS);
                assertDenied(probe);
                assertTrue(needsConsent());
                assertEquals(0, failures.get());
            } finally { release.countDown(); }
        }
    }

    @Test void shutdownDuringSaveCannotAdmitAfterTheCommit() throws Exception {
        var probe = new Probe(queued::add);
        probe.begin(request(), ignored -> { }, () -> {
            save(probe);
            stopping.set(true);
        }, () -> "Declined");
        accept(probe);
        queued.remove().run();
        assertDenied(probe);
        assertFalse(needsConsent(), "Confirmed acceptance remains in history even when admission is cancelled");
    }

    @Test void dialogLinkageFailureDeniesEvenAfterAConfirmedSave() throws Exception {
        var probe = begin(queued::add);
        probe.closeFailure = true;
        accept(probe);
        queued.remove().run();
        assertDenied(probe);
        assertEquals("error-close", probe.reason);
        assertFalse(needsConsent());
    }

    @Test void failedRenderingAndLateCallbacksCannotGrantConsent() throws Exception {
        var probe = new Probe(queued::add);
        probe.begin(request(), ignored -> { throw new NoClassDefFoundError("Missing optional renderer"); },
                () -> save(probe), () -> "Declined");
        assertDenied(probe);
        assertEquals("error-form", probe.reason);
        assertFalse(probe.session.accept(probe.session.token(), Map.of("terms-of-service", true)));
        assertTrue(queued.isEmpty());
        assertTrue(needsConsent());
    }

    @Test void timeoutAndRepeatedCompletionDisconnectOnlyOnce() throws Exception {
        var probe = begin(queued::add);
        probe.finish("Timed out");
        probe.finish(null);
        probe.finish("Disconnected");
        assertDenied(probe);
        assertFalse(probe.session.accept(probe.session.token(), Map.of("terms-of-service", true)));
        assertTrue(needsConsent());
    }

    @Test void timeoutDoesNotHoldTheConnectionLockWhileCancellingANativeResponse() throws Exception {
        var responseStarted = new CountDownLatch(1);
        var closeStarted = new CountDownLatch(1);
        var probe = begin(task -> { throw new RejectedExecutionException(); });
        probe.onClose = closeStarted::countDown;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var response = pool.submit(() -> {
                synchronized (probe.session) {
                    responseStarted.countDown();
                    try { assertTrue(closeStarted.await(2, TimeUnit.SECONDS), "Timeout must not wait for the response lock before disconnecting"); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new RuntimeException(ex); }
                    probe.session.accept(probe.session.token(), Map.of("terms-of-service", true));
                }
            });
            assertTrue(responseStarted.await(2, TimeUnit.SECONDS));
            var timeout = pool.submit(() -> probe.finish("Timed out"));
            response.get(5, TimeUnit.SECONDS);
            timeout.get(5, TimeUnit.SECONDS);
        }
        assertDenied(probe);
        assertTrue(needsConsent());
    }

    private Probe begin(Executor executor) throws Exception {
        var probe = new Probe(executor);
        probe.begin(request(), ignored -> { }, () -> save(probe), () -> "Declined");
        return probe;
    }
    private io.github.consentgate.core.admission.AdmissionRequest request() throws Exception {
        return runtime.admissionService().orElseThrow().check(player, "en-US").orElseThrow();
    }
    private void save(Probe probe) throws Exception {
        runtime.admissionService().orElseThrow().grant(player, probe.session, Instant.now(), "in-game");
    }
    private boolean needsConsent() throws Exception {
        return runtime.admissionService().orElseThrow().check(player, "en-US").isPresent();
    }
    private static void accept(Probe probe) {
        assertTrue(probe.session.accept(probe.session.token(), Map.of("terms-of-service", true)));
    }
    private static void assertDenied(Probe probe) {
        assertTrue(probe.done.isDone());
        assertEquals(0, probe.admits);
        assertEquals(1, probe.disconnects);
    }

    private final class Probe extends PaperAdmission {
        int admits;
        int disconnects;
        String reason;
        boolean closeFailure;
        Runnable onClose = () -> { };
        Probe(Executor executor) { super(player, executor, players, stopping::get, ignored -> failures.incrementAndGet()); }
        @Override protected String message(String key) { return key; }
        @Override protected void closePresentation() {
            onClose.run();
            if (closeFailure) throw new NoClassDefFoundError("Missing renderer");
        }
        @Override protected void disconnect(String reason) { disconnects++; this.reason = reason; }
        @Override protected void admitted() { admits++; }
    }
}
