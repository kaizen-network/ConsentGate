package io.github.consentgate.paper;

import com.destroystokyo.paper.ClientOption;
import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import io.github.consentgate.bedrock.BedrockView;
import io.github.consentgate.bedrock.GeyserBedrockBridge;
import io.github.consentgate.core.admin.AdminCommands;
import io.github.consentgate.core.admin.DatabaseJobs;
import io.github.consentgate.core.admin.PlayerOperations;
import io.github.consentgate.core.admin.PreviewQueue;
import io.github.consentgate.presentation.AdminDocuments;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class ConsentGatePaper extends JavaPlugin implements Listener {
    private final Map<PlayerConfigurationConnection, Pending> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> resetting = ConcurrentHashMap.newKeySet();
    private final Set<UUID> awaitingJoin = ConcurrentHashMap.newKeySet();
    private final PlayerOperations playerOperations = new PlayerOperations();
    private final PreviewQueue previews = new PreviewQueue();
    private boolean reloading;
    private DatabaseJobs databaseJobs;
    private volatile boolean stopping;
    private volatile boolean ready;
    private volatile ConsentGateRuntime runtime;
    private PaperDialogs dialogs;
    private ThreadPoolExecutor database;

    @Override public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        installCommand();
        try {
            defaults();
            runtime = new RuntimeLoader(getLogger()::warning).load(getDataFolder().toPath());
            var messages = new InterfaceMessages(getDataFolder().toPath().resolve("messages"));
            if (runtime.enabled()) {
                var catalog = runtime.admissionService().orElseThrow().catalog();
                PaperConfiguration.validate(new RuntimeLoader.Prepared(runtime.config(), catalog), messages);
                int capacity = Math.min(10_000, Math.max(32, runtime.config().maxPending() * 2));
                database = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity), task -> {
                    Thread thread = new Thread(task, "consentgate-paper-database");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
                databaseJobs = new DatabaseJobs(database);
                dialogs = new PaperDialogs(runtime.config(), messages);
                getLogger().info("ConsentGate is enabled with " + runtime.config().storage().type() + " storage.");
            } else getLogger().warning("ConsentGate is disabled. Configure documents before enabling it.");
            ready = true;
        } catch (Exception | LinkageError ex) {
            getLogger().log(java.util.logging.Level.SEVERE, "ConsentGate could not start. Connections will be denied.", ex);
        }
    }

    @EventHandler public void configure(AsyncPlayerConnectionConfigureEvent event) {
        var connection = event.getConnection();
        if (!ready || stopping) { connection.disconnect(Component.text("ConsentGate is unavailable.")); return; }
        if (!runtime.enabled()) return;
        UUID id = connection.getProfile().getId();
        if (id == null) { connection.disconnect(Component.text("ConsentGate could not identify this connection.")); return; }
        Pending pending;
        boolean rejected;
        synchronized (sessions) {
            String locale = runtime.config().useClientLocale() ? connection.getClientOption(ClientOption.LOCALE) : runtime.config().defaultLocale();
            pending = new Pending(connection, id, locale);
            rejected = stopping || reloading || resetting.contains(id) || awaitingJoin.contains(id)
                    || sessions.values().stream().anyMatch(active -> active.id.equals(id))
                    || sessions.size() >= runtime.config().maxPending() || sessions.putIfAbsent(connection, pending) != null;
            if (!rejected) {
                pending.preview = previews.take(id).orElse(null);
                if (pending.preview != null && pending.preview.locale() != null) pending.locale = pending.preview.locale();
            }
        }
        if (rejected) { connection.disconnect(Component.text("ConsentGate is busy. Please try again shortly.")); return; }
        execute(pending, () -> {
            var service = runtime.admissionService().orElseThrow();
            var request = pending.preview == null ? service.check(id, pending.locale) : Optional.of(service.preview(id, pending.locale));
            if (request.isEmpty()) finish(pending, null);
            else {
                synchronized (pending) {
                    if (pending.finished.get()) return;
                    if (runtime.config().nativeBedrockForms()
                            && getServer().getPluginManager().isPluginEnabled("Geyser-Spigot")) {
                        pending.bedrock = GeyserBedrockBridge.open(id, new SafeTextFormatter(runtime.config().appearance()),
                                runtime.config().bedrockButtonColor(), key -> dialogs.message(pending.locale, key),
                                () -> !pending.finished.get() && !stopping,
                                () -> finish(pending, dialogs.message(pending.locale, "denied")),
                                error -> {
                                    getLogger().log(java.util.logging.Level.WARNING, "Native Bedrock form failed", error);
                                    finish(pending, "Consent form could not be shown.");
                                });
                    }
                    if (runtime.config().languageSelector().enabled() && (pending.preview == null || pending.preview.locale() == null)) {
                        pending.selecting = true;
                        if (pending.bedrock != null) pending.bedrock.language(runtime.config().languageSelector(),
                                selected -> selectLanguage(pending, selected));
                        else connection.getAudience().showDialog(dialogs.language(pending.locale, pending.selectorToken));
                    } else begin(pending, request.orElseThrow());
                }
            }
        });
        try { pending.done.get(runtime.config().timeoutSeconds(), TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); finish(pending, "ConsentGate is stopping."); }
        catch (Exception ex) { finish(pending, "Consent request timed out or failed."); }
        finally { synchronized (sessions) { sessions.remove(connection, pending); } }
    }

    private void begin(Pending pending, AdmissionRequest request) {
        synchronized (pending) {
            if (pending.finished.get()) return;
            pending.locale = request.documents().getFirst().locale();
            pending.begin(request, session -> summary(pending, false),
                    () -> runtime.admissionService().orElseThrow().grant(pending.id, pending.session, Instant.now(), "in-game"),
                    () -> dialogs.message(pending.locale, "denied"));
        }
    }

    @EventHandler public void click(PlayerCustomClickEvent event) {
        if (!(event.getCommonConnection() instanceof PlayerConfigurationConnection connection)
                || !event.getIdentifier().namespace().equals("consentgate")) return;
        var pending = sessions.get(connection);
        if (pending == null) return;
        synchronized (pending) {
            if (pending.finished.get() || pending.bedrock != null) return;
            try {
                String[] action = event.getIdentifier().value().split("/");
                if (pending.selecting) {
                    if (action.length == 2 && action[0].equals("selector-leave") && action[1].equals(pending.selectorToken)) {
                        finish(pending, dialogs.message(pending.locale, "denied"));
                    } else if (action.length == 3 && action[0].equals("language") && action[2].equals(pending.selectorToken)) {
                        selectLanguage(pending, index(action[1]));
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
            } catch (Exception | LinkageError ex) {
                getLogger().log(java.util.logging.Level.WARNING, "Consent response failed for " + pending.id, ex);
                finish(pending, "Consent response could not be processed.");
            }
        }
    }

    private Map<String, Boolean> selections(PlayerCustomClickEvent event, AdmissionSession session) throws IOException {
        return PaperSelections.decode(event.getTag() == null ? null : event.getTag().string(),
                session.request().documents().stream().map(document -> document.id()).toList());
    }

    private void selectLanguage(Pending pending, int selected) {
        synchronized (pending) {
            if (pending.finished.get() || !pending.selecting) return;
            var options = List.copyOf(runtime.config().languageSelector().options().keySet());
            if (selected < 0 || selected >= options.size()) return;
            pending.selecting = false;
            pending.locale = options.get(selected);
            execute(pending, () -> {
                var service = runtime.admissionService().orElseThrow();
                var request = pending.preview == null ? service.checkExactLocale(pending.id, pending.locale)
                        : Optional.of(service.preview(pending.id, pending.locale));
                if (request.isEmpty()) finish(pending, null); else begin(pending, request.orElseThrow());
            });
        }
    }

    private void summary(Pending pending, boolean error) {
        pending.display++;
        if (pending.finished.get()) return;
        if (pending.bedrock != null) pending.bedrock.summary(pending.session, error);
        else pending.connection.getAudience().showDialog(dialogs.summary(pending.session, pending.locale, error));
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

    private void execute(Pending pending, PaperAdmission.Operation operation) { pending.execute(operation); }
    private void finish(Pending pending, String denial) { pending.finish(denial); }
    @EventHandler public void disconnected(PlayerConnectionCloseEvent event) {
        sessions.values().stream().filter(pending -> pending.id.equals(event.getPlayerUniqueId()))
                .forEach(pending -> finish(pending, "Connection ended."));
        awaitingJoin.remove(event.getPlayerUniqueId());
    }
    @EventHandler public void joined(PlayerJoinEvent event) { awaitingJoin.remove(event.getPlayer().getUniqueId()); }

    private void installCommand() {
        var commands = new AdminCommands(name -> Optional.ofNullable(getServer().getPlayerExact(name))
                .map(org.bukkit.entity.Player::getUniqueId), new AdminCommands.Operations() {
            public void status(UUID id, Consumer<String> reply) { adminOperation(id, false, reply); }
            public void reset(UUID id, Consumer<String> reply) { adminOperation(id, true, reply); }
            public void configuration(boolean apply, Consumer<String> reply) { configurationCheck(apply, reply); }
            public void preview(UUID id, String locale, Consumer<String> reply) { previewCommand(id, locale, reply); }
            public void document(String[] args, Consumer<String> reply) {
                if (!running(reply)) return;
                var active = runtime;
                AdminDocuments.show(active.admissionService().orElseThrow().catalog(), active.config().defaultLocale(), args, reply);
            }
        });
        var command = java.util.Objects.requireNonNull(getCommand("consentgate"), "Missing consentgate command");
        command.setExecutor((sender, ignored, label, args) -> {
            commands.execute(args, sender::hasPermission, text -> reply(sender, text));
            return true;
        });
        command.setTabCompleter((sender, ignored, label, args) -> AdminCommands.suggest(args, sender::hasPermission));
    }

    private void reply(CommandSender sender, String text) {
        if (stopping) return;
        if (getServer().isPrimaryThread()) sender.sendMessage(Component.text(text));
        else {
            try { getServer().getScheduler().runTask(this, () -> { if (!stopping) sender.sendMessage(Component.text(text)); }); }
            catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { }
        }
    }

    private void previewCommand(UUID id, String locale, Consumer<String> reply) {
        synchronized (sessions) {
            if (!running(reply)) return;
            if (reloading || resetting.contains(id)) { reply.accept("ConsentGate is busy. Please try again shortly."); return; }
            if (!"cancel".equalsIgnoreCase(locale) && (getServer().getPlayer(id) != null || awaitingJoin.contains(id)
                    || sessions.values().stream().anyMatch(pending -> pending.id.equals(id)))) {
                reply.accept("Disconnect the player first, then queue a preview using their UUID: " + id);
                return;
            }
            try { reply.accept(previews.schedule(id, locale, runtime.admissionService().orElseThrow().catalog())); }
            catch (IllegalArgumentException ex) { reply.accept(ex.getMessage()); }
        }
    }

    private void submitDatabase(Runnable operation, boolean maintenance) {
        synchronized (sessions) {
            if (stopping || (reloading && !maintenance) || database == null || database.isShutdown()) throw new RejectedExecutionException();
            databaseJobs.execute(operation);
        }
    }

    private boolean running(Consumer<String> reply) {
        if (stopping || !ready || runtime == null || !runtime.enabled()) {
            reply.accept("ConsentGate must be enabled and running before using this command.");
            return false;
        }
        return true;
    }

    private void adminOperation(UUID id, boolean reset, Consumer<String> reply) {
        if (!running(reply)) return;
        if (reset) {
            synchronized (sessions) {
                if (getServer().getPlayer(id) != null || awaitingJoin.contains(id)
                        || sessions.values().stream().anyMatch(pending -> pending.id.equals(id))) {
                    reply.accept("Disconnect the player first, then reset using their UUID: " + id);
                    return;
                }
                if (!resetting.add(id)) { reply.accept("A reset is already pending for " + id + "."); return; }
            }
        }
        try {
            submitDatabase(() -> playerOperations.run(id, () -> {
                try {
                    if (stopping) { reply.accept("ConsentGate is stopping. The command was not applied."); return; }
                    var service = runtime.admissionService().orElseThrow();
                    if (reset) {
                        service.reset(id, Instant.now());
                        reply.accept("Consent reset for " + id + " in scope " + runtime.config().scope()
                                + ". History was kept. Acceptance is required on the next connection.");
                    } else {
                        reply.accept("Consent status for " + id + " in scope " + runtime.config().scope() + ":");
                        for (var status : service.status(id)) reply.accept(status.id() + " (" + status.version() + "): "
                                + (status.accepted() ? "accepted" : "acceptance required"));
                    }
                } catch (Exception ex) {
                    getLogger().log(java.util.logging.Level.WARNING, "Consent admin operation failed for " + id, ex);
                    reply.accept("Consent records could not be processed. Check the server log before retrying.");
                } finally { if (reset) resetting.remove(id); }
            }), false);
        } catch (RejectedExecutionException ex) {
            if (reset) resetting.remove(id);
            reply.accept("ConsentGate is busy. Please try again shortly.");
        }
    }

    private void configurationCheck(boolean apply, Consumer<String> reply) {
        synchronized (sessions) {
            if (!running(reply)) return;
            if (reloading || (apply && (!sessions.isEmpty() || databaseJobs.pending() != 0 || !resetting.isEmpty()))) {
                reply.accept("Reload is busy. Wait for consent sessions and database work to finish, then retry.");
                return;
            }
            if (apply) reloading = true;
            try {
                submitDatabase(() -> {
                    try {
                        var prepared = new RuntimeLoader().prepare(getDataFolder().toPath());
                        var nextMessages = new InterfaceMessages(getDataFolder().toPath().resolve("messages"));
                        PaperConfiguration.validate(prepared, nextMessages);
                        var nextDialogs = new PaperDialogs(prepared.config(), nextMessages);
                        var nextRuntime = runtime.reconfigured(prepared);
                        synchronized (sessions) {
                            if (stopping) throw new IllegalStateException("ConsentGate is stopping");
                            if (apply) { previews.clear(); dialogs = nextDialogs; runtime = nextRuntime; }
                        }
                        reply.accept(apply ? "ConsentGate reloaded. Changes apply to new connections; existing players are not kicked."
                                : "Validation passed. Configuration, documents, messages, and saved revisions are compatible. Nothing was applied.");
                    } catch (Exception ex) {
                        getLogger().log(java.util.logging.Level.WARNING, "ConsentGate configuration check failed", ex);
                        reply.accept("Configuration check failed: " + ex.getMessage() + ". The running configuration was kept.");
                    } finally { if (apply) synchronized (sessions) { reloading = false; } }
                }, apply);
            } catch (RejectedExecutionException ex) {
                if (apply) reloading = false;
                reply.accept("ConsentGate is busy. Please try again shortly.");
            }
        }
    }

    @Override public void onDisable() {
        List<Pending> ending;
        synchronized (sessions) { stopping = true; ending = List.copyOf(sessions.values()); }
        ending.forEach(pending -> finish(pending, "ConsentGate is stopping."));
        awaitingJoin.clear();
        previews.clear();
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
    private final class Pending extends PaperAdmission {
        final PlayerConfigurationConnection connection;
        final String selectorToken = UUID.randomUUID().toString();
        volatile String locale;
        volatile BedrockView bedrock;
        PreviewQueue.Choice preview;
        boolean selecting;
        boolean redisplayQueued;
        long display;
        Pending(PlayerConfigurationConnection connection, UUID id, String locale) {
            super(id, task -> submitDatabase(task, false), playerOperations, () -> stopping,
                    error -> getLogger().log(java.util.logging.Level.WARNING, "Consent operation failed for " + id, error));
            this.connection = connection;
            this.locale = locale;
        }
        @Override protected void closePresentation() {
            if (bedrock != null) bedrock.close();
            else connection.getAudience().closeDialog();
        }
        @Override protected void disconnect(String reason) { connection.disconnect(Component.text(reason)); }
        @Override protected void admitted() { awaitingJoin.add(id); }
    }
}
