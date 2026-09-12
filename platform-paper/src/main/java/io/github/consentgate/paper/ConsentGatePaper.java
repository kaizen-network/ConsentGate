package io.github.consentgate.paper;

import com.destroystokyo.paper.ClientOption;
import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import io.github.consentgate.core.GateSession;
import io.github.consentgate.core.admission.AdmissionRequest;
import io.github.consentgate.core.admission.AdmissionSession;
import io.github.consentgate.core.runtime.ConsentGateRuntime;
import io.github.consentgate.core.runtime.RuntimeLoader;
import io.github.consentgate.presentation.InterfaceMessages;
import io.github.consentgate.presentation.SafeTextFormatter;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ConsentGatePaper extends JavaPlugin implements Listener {
    private final Map<PlayerConfigurationConnection, Pending> sessions = new ConcurrentHashMap<>();
    private volatile boolean stopping;
    private volatile boolean ready;
    private ConsentGateRuntime runtime;
    private PaperDialogs dialogs;
    private ThreadPoolExecutor database;

    @Override public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        try {
            defaults();
            runtime = new RuntimeLoader().load(getDataFolder().toPath());
            var messages = new InterfaceMessages(getDataFolder().toPath().resolve("messages"));
            if (runtime.enabled()) {
                if (runtime.config().nativeBedrockForms()) throw new IllegalArgumentException("Native Bedrock forms are not implemented on Paper yet; set bedrock.native-forms to false");
                var catalog = runtime.admissionService().orElseThrow().catalog();
                SafeTextFormatter.validateCatalog(catalog);
                var locales = new java.util.HashSet<String>();
                locales.add(runtime.config().defaultLocale());
                catalog.documents().forEach(document -> locales.addAll(document.translations().keySet()));
                messages.validateFor(locales, runtime.config().defaultLocale());
                var selector = runtime.config().languageSelector();
                SafeTextFormatter.validate(selector.title());
                SafeTextFormatter.validate(selector.prompt());
                selector.options().values().forEach(SafeTextFormatter::validate);
                int capacity = Math.min(10_000, Math.max(32, runtime.config().maxPending() * 2));
                database = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity), task -> {
                    Thread thread = new Thread(task, "consentgate-paper-database");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
                dialogs = new PaperDialogs(runtime.config(), messages);
                getLogger().info("ConsentGate is enabled with SQLite storage.");
            } else getLogger().warning("ConsentGate is disabled. Configure documents before enabling it.");
            ready = true;
        } catch (Exception ex) {
            getLogger().log(java.util.logging.Level.SEVERE, "ConsentGate could not start. Connections will be denied.", ex);
        }
    }

    @EventHandler public void configure(AsyncPlayerConnectionConfigureEvent event) {
        var connection = event.getConnection();
        if (!ready || stopping) { connection.disconnect(Component.text("ConsentGate is unavailable.")); return; }
        if (!runtime.enabled()) return;
        UUID id = connection.getProfile().getId();
        if (id == null) { connection.disconnect(Component.text("ConsentGate could not identify this connection.")); return; }
        String locale = runtime.config().useClientLocale() ? connection.getClientOption(ClientOption.LOCALE) : runtime.config().defaultLocale();
        var pending = new Pending(connection, id, locale);
        boolean rejected;
        synchronized (sessions) {
            rejected = stopping || sessions.size() >= runtime.config().maxPending() || sessions.putIfAbsent(connection, pending) != null;
        }
        if (rejected) { connection.disconnect(Component.text("ConsentGate is busy. Please try again shortly.")); return; }
        execute(pending, () -> {
            var request = runtime.admissionService().orElseThrow().check(id, pending.locale);
            if (request.isEmpty()) finish(pending, null);
            else if (runtime.config().languageSelector().enabled()) {
                synchronized (pending) {
                    if (!pending.finished.get()) {
                        pending.selecting = true;
                        connection.getAudience().showDialog(dialogs.language(pending.locale, pending.selectorToken));
                    }
                }
            } else begin(pending, request.orElseThrow());
        });
        try { pending.done.get(runtime.config().timeoutSeconds(), TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); finish(pending, "ConsentGate is stopping."); }
        catch (Exception ex) { finish(pending, "Consent request timed out or failed."); }
        finally { sessions.remove(connection, pending); }
    }

    private void begin(Pending pending, AdmissionRequest request) {
        synchronized (pending) {
            if (pending.finished.get()) return;
            pending.session = new AdmissionSession(request);
            pending.locale = request.documents().getFirst().locale();
            pending.session.result().whenComplete((decision, error) -> {
                if (error == null && decision == GateSession.Decision.ACCEPTED) {
                    execute(pending, () -> {
                        runtime.admissionService().orElseThrow().grant(pending.id, pending.session, Instant.now(), "in-game");
                        finish(pending, null);
                    });
                } else finish(pending, dialogs.message(pending.locale, "denied"));
            });
            summary(pending, false);
        }
    }

    @EventHandler public void click(PlayerCustomClickEvent event) {
        if (!(event.getCommonConnection() instanceof PlayerConfigurationConnection connection)
                || !event.getIdentifier().namespace().equals("consentgate")) return;
        var pending = sessions.get(connection);
        if (pending == null) return;
        synchronized (pending) {
            if (pending.finished.get()) return;
            try {
                String[] action = event.getIdentifier().value().split("/");
                if (pending.selecting) {
                    if (action.length == 2 && action[0].equals("selector-leave") && action[1].equals(pending.selectorToken)) {
                        finish(pending, dialogs.message(pending.locale, "denied"));
                    } else if (action.length == 3 && action[0].equals("language") && action[2].equals(pending.selectorToken)) {
                        var options = List.copyOf(runtime.config().languageSelector().options().keySet());
                        int selected = index(action[1]);
                        if (selected < 0 || selected >= options.size()) return;
                        pending.selecting = false;
                        pending.locale = options.get(selected);
                        execute(pending, () -> {
                            var request = runtime.admissionService().orElseThrow().checkExactLocale(pending.id, pending.locale);
                            if (request.isEmpty()) finish(pending, null); else begin(pending, request.orElseThrow());
                        });
                    }
                    return;
                }
                var session = pending.session;
                if (session == null || !session.pending() || action.length < 2 || !action[action.length - 1].equals(session.token())) return;
                switch (action[0]) {
                    case "leave" -> { if (action.length == 2) session.decline(session.token()); }
                    case "accept" -> {
                        if (action.length != 2) return;
                        var selections = selections(event, session);
                        if (selections == null || !session.accept(session.token(), selections)) redisplay(pending);
                    }
                    case "back" -> { if (action.length == 2) summary(pending, false); }
                    case "read" -> {
                        if (action.length != 3) return;
                        var selections = selections(event, session);
                        if (selections != null) session.updateSelections(session.token(), selections);
                        page(pending, index(action[1]), 0);
                    }
                    case "previous", "next" -> {
                        if (action.length == 4) page(pending, index(action[1]), index(action[2]) + (action[0].equals("next") ? 1 : -1));
                    }
                    default -> { }
                }
            } catch (Exception ex) { finish(pending, "Invalid consent response."); }
        }
    }

    private Map<String, Boolean> selections(PlayerCustomClickEvent event, AdmissionSession session) throws IOException {
        return PaperSelections.decode(event.getTag() == null ? null : event.getTag().string(),
                session.request().documents().stream().map(document -> document.id()).toList());
    }

    private void summary(Pending pending, boolean error) {
        pending.display++;
        if (!pending.finished.get()) pending.connection.getAudience().showDialog(dialogs.summary(pending.session, pending.locale, error));
    }
    private void redisplay(Pending pending) {
        if (pending.redisplayQueued) return;
        pending.redisplayQueued = true;
        long display = pending.display;
        getServer().getScheduler().runTaskLater(this, () -> {
            synchronized (pending) {
                pending.redisplayQueued = false;
                if (!pending.finished.get() && pending.session.pending() && pending.display == display) summary(pending, true);
            }
        }, 5L);
    }
    private void page(Pending pending, int document, int page) {
        var documents = pending.session.request().documents();
        if (document < 0 || document >= documents.size() || page < 0 || page >= documents.get(document).pages().size()) return;
        pending.display++;
        pending.connection.getAudience().showDialog(dialogs.page(pending.session, pending.locale, document, page));
    }
    private static int index(String value) { try { return Integer.parseInt(value); } catch (NumberFormatException ex) { return -1; } }

    private void execute(Pending pending, DatabaseOperation operation) {
        try {
            database.execute(() -> {
                if (pending.finished.get() || stopping) { finish(pending, "ConsentGate is stopping."); return; }
                try { operation.run(); }
                catch (Exception ex) {
                    getLogger().log(java.util.logging.Level.WARNING, "Consent operation failed for " + pending.id, ex);
                    finish(pending, "Consent records could not be processed. Please try again later.");
                }
            });
        } catch (RejectedExecutionException ex) { finish(pending, "ConsentGate is busy. Please try again shortly."); }
    }
    private void finish(Pending pending, String denial) {
        synchronized (pending) {
            if (!pending.finished.compareAndSet(false, true)) return;
            try {
                if (denial == null && stopping) denial = "ConsentGate is stopping.";
                try { pending.connection.getAudience().closeDialog(); }
                catch (RuntimeException ex) { if (denial == null) denial = "Consent dialog could not be closed."; }
                if (denial != null) pending.connection.disconnect(Component.text(denial));
            } finally { pending.done.complete(null); }
        }
    }
    @EventHandler public void disconnected(PlayerConnectionCloseEvent event) {
        sessions.values().stream().filter(pending -> pending.id.equals(event.getPlayerUniqueId()))
                .forEach(pending -> finish(pending, "Connection ended."));
    }
    @Override public void onDisable() {
        List<Pending> ending;
        synchronized (sessions) { stopping = true; ending = List.copyOf(sessions.values()); }
        ending.forEach(pending -> finish(pending, "ConsentGate is stopping."));
        if (database != null) {
            database.shutdownNow();
            try { database.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }
        if (runtime != null) {
            try { runtime.close(); } catch (Exception ex) { getLogger().warning("ConsentGate storage did not close cleanly."); }
        }
    }
    private void defaults() throws IOException {
        Path root = getDataFolder().toPath();
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) throw new IOException("Plugin data directory cannot be a symbolic link");
        for (String directory : List.of("messages", "documents")) {
            if (Files.isSymbolicLink(root.resolve(directory))) throw new IOException("Default directory cannot be a symbolic link");
            Files.createDirectories(root.resolve(directory));
        }
        for (var entry : Map.of("config.yml", "config.yml", "messages/en-US.properties", "messages/en-US.properties",
                "messages/id-ID.properties", "messages/id-ID.properties", "example-terms.yml", "documents/terms.yml.example",
                "example-privacy.yml", "documents/privacy.yml.example").entrySet()) {
            Path target = root.resolve(entry.getValue());
            if (Files.isSymbolicLink(target)) throw new IOException("Default target cannot be a symbolic link");
            if (Files.exists(target)) continue;
            try (var input = getClass().getClassLoader().getResourceAsStream(entry.getKey())) {
                if (input == null) throw new IOException("Missing bundled resource: " + entry.getKey());
                Files.copy(input, target);
            }
        }
    }
    @FunctionalInterface private interface DatabaseOperation { void run() throws Exception; }
    private static final class Pending {
        final PlayerConfigurationConnection connection;
        final UUID id;
        final String selectorToken = UUID.randomUUID().toString();
        final CompletableFuture<Void> done = new CompletableFuture<>();
        final AtomicBoolean finished = new AtomicBoolean();
        volatile String locale;
        volatile AdmissionSession session;
        boolean selecting;
        boolean redisplayQueued;
        long display;
        Pending(PlayerConfigurationConnection connection, UUID id, String locale) {
            this.connection = connection;
            this.id = id;
            this.locale = locale;
        }
    }
}
