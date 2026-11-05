package io.github.consentgate.spigot;

import io.github.consentgate.bedrock.BedrockView;
import io.github.consentgate.bedrock.GeyserBedrockBridge;
import io.github.consentgate.core.admin.AdminCommands;
import io.github.consentgate.core.admin.DatabaseJobs;
import io.github.consentgate.core.admin.PlayerOperations;
import io.github.consentgate.core.admin.PreviewQueue;
import io.github.consentgate.core.admission.AdmissionHold;
import io.github.consentgate.core.admission.AdmissionRequest;
import io.github.consentgate.core.admission.AdmissionSession;
import io.github.consentgate.core.runtime.ConsentGateRuntime;
import io.github.consentgate.core.runtime.RuntimeLoader;
import io.github.consentgate.presentation.AdminDocuments;
import io.github.consentgate.presentation.DefaultFiles;
import io.github.consentgate.presentation.InterfaceMessages;
import io.github.consentgate.presentation.PresentationValidator;
import io.github.consentgate.presentation.SafeTextFormatter;
import net.md_5.bungee.api.dialog.Dialog;
import org.bstats.bukkit.Metrics;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCustomClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * The Spigot hold. Spigot has no stage before world entry that waits for a plugin, so players without a current
 * acceptance join, and stay frozen and hidden until the acceptance is saved. Only {@link ConsentGateSpigot} creates it.
 */
public final class SpigotGate implements Listener {
    private static final int BSTATS_ID = 35143;
    // Vanilla drops a login after 30 seconds, so the pre-login check must answer well before that.
    private static final long CHECK_SECONDS = 20;
    // A dialog sent in the join tick can be lost behind the terrain loading screen.
    private static final long FIRST_SHOW_TICKS = 20;
    private static final long RESHOW_NANOS = TimeUnit.SECONDS.toNanos(3);
    // Vanilla gives a login 30 seconds. A join that comes later than this is held and checked again, which is safe.
    private static final long MARK_NANOS = TimeUnit.SECONDS.toNanos(60);
    private final JavaPlugin plugin;
    private final Map<UUID, Hold> held = new ConcurrentHashMap<>();
    private final Map<UUID, Mark> marks = new ConcurrentHashMap<>();
    private final Set<UUID> resetting = ConcurrentHashMap.newKeySet();
    private final PlayerOperations playerOperations = new PlayerOperations();
    private final PreviewQueue previews = new PreviewQueue();
    private boolean reloading;
    private DatabaseJobs databaseJobs;
    private volatile boolean stopping;
    private volatile boolean ready;
    private volatile ConsentGateRuntime runtime;
    private volatile SpigotDialogs dialogs;
    private ThreadPoolExecutor database;
    private Metrics metrics;

    /** A login between the pre-login check and the join. {@code admitted} means it passed the check. */
    private record Mark(boolean admitted, PreviewQueue.Choice preview, long expires) {
        boolean valid() { return System.nanoTime() - expires < 0; }
    }

    SpigotGate(JavaPlugin plugin) { this.plugin = plugin; }

    void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        new SpigotFreeze(this::isHeld, this::isJoining, this::reshow).register(plugin);
        installCommand();
        try {
            defaults();
            runtime = new RuntimeLoader(plugin.getLogger()::warning).load(plugin.getDataFolder().toPath());
            var messages = new InterfaceMessages(plugin.getDataFolder().toPath().resolve("messages"));
            int capacity = Math.min(10_000, Math.max(32, runtime.config().maxPending() * 2));
            database = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity), task -> {
                Thread thread = new Thread(task, "consentgate-spigot-database");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
            databaseJobs = new DatabaseJobs(database);
            if (runtime.enabled()) {
                var catalog = runtime.admissionService().orElseThrow().catalog();
                PresentationValidator.validate(runtime.config(), catalog, messages);
                dialogs = new SpigotDialogs(runtime.config(), messages);
                plugin.getLogger().info("ConsentGate is enabled with " + runtime.config().storage().type() + " storage.");
            } else plugin.getLogger().warning("ConsentGate is disabled. Configure documents before enabling it.");
            ready = true;
        } catch (Exception | LinkageError ex) {
            plugin.getLogger().log(Level.SEVERE, "ConsentGate could not start. Connections will be denied.", ex);
        }
        // Players already online after /reload or a late load go through the same join path.
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!ready) refuse(player, "error-unavailable");
            else if (runtime.enabled()) hold(player, null);
        }
        // Usage statistics must never stop the gate from enabling.
        try { metrics = new Metrics(plugin, BSTATS_ID); }
        catch (RuntimeException | LinkageError ex) { plugin.getLogger().log(Level.WARNING, "bStats could not start", ex); }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void preLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        if (!ready || stopping) { refuse(event, "error-unavailable"); return; }
        var active = runtime;
        if (!active.enabled()) return;
        UUID id = event.getUniqueId();
        long expires = System.nanoTime() + MARK_NANOS;
        PreviewQueue.Choice preview;
        Mark pending;
        synchronized (held) {
            marks.values().removeIf(mark -> !mark.valid());
            // Only players still deciding count toward the limit: not logins that dropped before joining, and not
            // players kept frozen after another plugin cancelled their kick.
            if (stopping || reloading || resetting.contains(id) || held.containsKey(id)
                    || deciding().size() >= active.config().maxPending()) { refuse(event, "error-busy"); return; }
            preview = previews.take(id).orElse(null);
            // Marked before the check, so a reset started now waits until this login has joined or the mark expired.
            pending = new Mark(false, preview, expires);
            marks.put(id, pending);
        }
        if (preview != null) return;
        var accepted = new CompletableFuture<Boolean>();
        try {
            submitDatabase(() -> playerOperations.run(id, () -> {
                try { accepted.complete(active.admissionService().orElseThrow().check(id, active.config().defaultLocale()).isEmpty()); }
                catch (Exception | LinkageError ex) { accepted.completeExceptionally(ex); }
            }), false);
        } catch (RejectedExecutionException ex) { marks.remove(id, pending); refuse(event, "error-busy"); return; }
        try {
            if (accepted.get(CHECK_SECONDS, TimeUnit.SECONDS)) marks.replace(id, pending, new Mark(true, null, expires));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            marks.remove(id, pending);
            refuse(event, "error-check");
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Consent check failed for " + id, ex);
            marks.remove(id, pending);
            refuse(event, "error-check");
        }
    }

    private void refuse(AsyncPlayerPreLoginEvent event, String key) {
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, localized(null, key));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void login(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) marks.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void joinFirst(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        for (var hold : held.values()) player.hidePlayer(plugin, hold.player);
        Mark mark = marks.remove(player.getUniqueId());
        if (mark != null && mark.admitted() && mark.valid()) return;
        // Anyone else is held, including a player with no mark at all.
        if (!ready || stopping) { refuse(player, "error-unavailable"); return; }
        if (!runtime.enabled()) return;
        hold(player, mark == null ? null : mark.preview());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void joinLast(PlayerJoinEvent event) {
        var hold = held.get(event.getPlayer().getUniqueId());
        if (hold == null || hold.player != event.getPlayer()) return;
        hold.joinMessage = event.getJoinMessage();
        event.setJoinMessage(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void quit(PlayerQuitEvent event) {
        var hold = held.get(event.getPlayer().getUniqueId());
        if (hold == null || hold.player != event.getPlayer()) return;
        event.setQuitMessage(null);
        hold.quit = true;
        held.remove(hold.id, hold);
        hold.cancelTimeout();
        hold.finish("Connection ended.");
    }

    private void hold(Player player, PreviewQueue.Choice preview) {
        var config = runtime.config();
        String locale = preview != null && preview.locale() != null ? preview.locale()
                : config.useClientLocale() ? player.getLocale() : config.defaultLocale();
        var hold = new Hold(player, locale, preview);
        var previous = held.put(hold.id, hold);
        if (previous != null) { previous.quit = true; previous.finish("Connection ended."); }
        hide(player);
        settle(hold, () -> { });
        var scheduler = plugin.getServer().getScheduler();
        hold.timeout = scheduler.runTaskLater(plugin, () -> hold.finish(hold.message("error-timeout")), config.timeoutSeconds() * 20L);
        scheduler.runTaskLater(plugin, () -> execute(hold, () -> {
            var service = runtime.admissionService().orElseThrow();
            var request = hold.preview == null ? service.check(hold.id, hold.locale) : Optional.of(service.preview(hold.id, hold.locale));
            // A current acceptance, maybe given on another server since the pre-login check, releases at once.
            if (request.isEmpty()) hold.finish(null);
            else onMain(() -> present(hold, request.orElseThrow()));
        }), FIRST_SHOW_TICKS);
    }

    /** For a player who cannot be held. If another plugin cancels the kick, the hold keeps them frozen and hidden. */
    private void refuse(Player player, String key) {
        var hold = new Hold(player, null, null);
        held.put(hold.id, hold);
        hide(player);
        settle(hold, () -> hold.finish(hold.message(key)));
    }

    /**
     * Spigot restores a saved vehicle after the join event. A cancelled mount, or a kick during the join, would make it
     * skip or delete the vehicle. So the mount goes through, and a tick later the held player gets off before anything
     * else happens. This also covers players held while already riding.
     */
    private void settle(Hold hold, Runnable then) {
        hold.joining = true;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            hold.joining = false;
            dismount(hold);
            then.run();
        });
    }

    private void dismount(Hold hold) {
        Player player = hold.player;
        if (held.get(hold.id) != hold || !player.isOnline() || !player.isInsideVehicle()) return;
        // leaveVehicle() reports success even when another plugin cancels the dismount, so check again.
        player.leaveVehicle();
        if (!player.isInsideVehicle()) return;
        if (!hold.dismountRefused) {
            hold.dismountRefused = true;
            plugin.getLogger().severe("Another plugin cancelled ConsentGate's dismount for " + player.getName()
                    + ". Trying again every tick while they are held.");
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> dismount(hold));
    }

    private List<Hold> deciding() { return held.values().stream().filter(hold -> !hold.finished.get()).toList(); }

    private static String names(List<Hold> holds) {
        return String.join(", ", holds.stream().map(hold -> hold.player.getName()).sorted().toList());
    }

    private void hide(Player player) {
        for (Player viewer : plugin.getServer().getOnlinePlayers()) if (viewer != player) viewer.hidePlayer(plugin, player);
    }

    private void present(Hold hold, AdmissionRequest request) {
        synchronized (hold) {
            if (hold.finished.get()) return;
            try {
                if (runtime.config().nativeBedrockForms() && plugin.getServer().getPluginManager().isPluginEnabled("Geyser-Spigot")) {
                    hold.bedrock = GeyserBedrockBridge.open(hold.id, new SafeTextFormatter(runtime.config().appearance()),
                            runtime.config().bedrockButtonColor(), key -> dialogs.message(hold.locale, key),
                            () -> !hold.finished.get() && !stopping,
                            () -> hold.finish(dialogs.message(hold.locale, "denied")),
                            error -> {
                                plugin.getLogger().log(Level.WARNING, "Native Bedrock form failed", error);
                                hold.finish(hold.message("error-form"));
                            });
                }
                if (runtime.config().languageSelector().enabled() && (hold.preview == null || hold.preview.locale() == null)) {
                    hold.selecting = true;
                    if (hold.bedrock != null) hold.bedrock.language(runtime.config().languageSelector(), selected -> selectLanguage(hold, selected));
                    else show(hold, dialogs.language(hold.locale, hold.selectorToken));
                } else begin(hold, request);
            } catch (RuntimeException | LinkageError ex) {
                plugin.getLogger().log(Level.WARNING, "Consent screen failed for " + hold.id, ex);
                hold.finish(hold.message("error-form"));
            }
        }
    }

    private void begin(Hold hold, AdmissionRequest request) {
        synchronized (hold) {
            if (hold.finished.get()) return;
            hold.locale = request.documents().getFirst().locale();
            hold.begin(request, session -> summary(hold, false),
                    () -> runtime.admissionService().orElseThrow().grant(hold.id, hold.session, Instant.now(), "in-game"),
                    () -> dialogs.message(hold.locale, "denied"));
        }
    }

    @EventHandler public void click(PlayerCustomClickEvent event) {
        if (!event.getId().getNamespace().equals("consentgate")) return;
        var hold = held.get(event.getPlayer().getUniqueId());
        if (hold == null || hold.player != event.getPlayer()) return;
        synchronized (hold) {
            if (hold.finished.get() || hold.bedrock != null) return;
            try {
                String[] action = event.getId().getKey().split("/");
                if (hold.selecting) {
                    if (action.length == 2 && action[0].equals("selector-leave") && action[1].equals(hold.selectorToken)) {
                        hold.finish(dialogs.message(hold.locale, "denied"));
                    } else if (action.length == 3 && action[0].equals("language") && action[2].equals(hold.selectorToken)) {
                        selectLanguage(hold, index(action[1]));
                    }
                    return;
                }
                var session = hold.session;
                if (session == null || !session.pending() || action.length < 2 || !action[action.length - 1].equals(session.token())) return;
                switch (action[0]) {
                    case "leave" -> { if (action.length == 2) session.decline(session.token()); }
                    case "accept" -> {
                        if (action.length != 2) return;
                        var selections = selections(event, session);
                        if (selections == null || !session.accept(session.token(), selections)) redisplay(hold);
                    }
                    case "back" -> { if (action.length == 2) summary(hold, false); }
                    case "read" -> {
                        if (action.length != 3) return;
                        var selections = selections(event, session);
                        if (selections != null) session.updateSelections(session.token(), selections);
                        page(hold, index(action[1]), 0);
                    }
                    case "previous", "next" -> {
                        if (action.length == 4) page(hold, index(action[1]), index(action[2]) + (action[0].equals("next") ? 1 : -1));
                    }
                    default -> { }
                }
            } catch (Exception | LinkageError ex) {
                plugin.getLogger().log(Level.WARNING, "Consent response failed for " + hold.id, ex);
                hold.finish(hold.message("error-response"));
            }
        }
    }

    private static Map<String, Boolean> selections(PlayerCustomClickEvent event, AdmissionSession session) {
        return SpigotSelections.decode(event.getData(), session.request().documents().stream().map(document -> document.id()).toList());
    }

    private void selectLanguage(Hold hold, int selected) {
        synchronized (hold) {
            if (hold.finished.get() || !hold.selecting) return;
            var options = List.copyOf(runtime.config().languageSelector().options().keySet());
            if (selected < 0 || selected >= options.size()) return;
            hold.selecting = false;
            hold.locale = options.get(selected);
            execute(hold, () -> {
                var service = runtime.admissionService().orElseThrow();
                var request = hold.preview == null ? service.checkExactLocale(hold.id, hold.locale)
                        : Optional.of(service.preview(hold.id, hold.locale));
                if (request.isEmpty()) hold.finish(null); else onMain(() -> begin(hold, request.orElseThrow()));
            });
        }
    }

    private void summary(Hold hold, boolean error) {
        hold.display++;
        if (hold.finished.get()) return;
        if (hold.bedrock != null) hold.bedrock.summary(hold.session, error);
        else show(hold, dialogs.summary(hold.session, hold.locale, error));
    }
    private void redisplay(Hold hold) {
        if (hold.redisplayQueued) return;
        hold.redisplayQueued = true;
        long display = hold.display;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            synchronized (hold) {
                hold.redisplayQueued = false;
                if (!hold.finished.get() && hold.session.pending() && hold.display == display) summary(hold, true);
            }
        }, 5L);
    }
    private void page(Hold hold, int document, int page) {
        var documents = hold.session.request().documents();
        if (document < 0 || document >= documents.size() || page < 0 || page >= documents.get(document).pages().size()) return;
        hold.display++;
        show(hold, dialogs.page(hold.session, hold.locale, document, page));
    }
    private void show(Hold hold, Dialog dialog) {
        hold.current = dialog;
        hold.shownAt = System.nanoTime();
        hold.player.showDialog(dialog);
    }
    private static int index(String value) { try { return Integer.parseInt(value); } catch (NumberFormatException ex) { return -1; } }

    boolean isHeld(Player player) {
        var hold = held.get(player.getUniqueId());
        return hold != null && hold.player == player;
    }

    boolean isJoining(Player player) {
        var hold = held.get(player.getUniqueId());
        return hold != null && hold.player == player && hold.joining;
    }

    /**
     * The client never says when a dialog closed, for example when another plugin opened its own screen. A held player who
     * chats, runs a command, interacts, or walks sideways has no screen open, so the current one is sent again. A timer
     * would clear checkboxes the player just ticked.
     */
    private void reshow(Player player) {
        onMain(() -> {
            var hold = held.get(player.getUniqueId());
            if (hold == null || hold.player != player) return;
            synchronized (hold) {
                if (hold.finished.get() || hold.bedrock != null || hold.current == null || hold.reshowQueued) return;
                long wait = RESHOW_NANOS - (System.nanoTime() - hold.shownAt);
                if (wait <= 0) { show(hold, hold.current); return; }
                // The client sends movement keys only when they change, so a press inside the window would never come again.
                hold.reshowQueued = true;
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    synchronized (hold) {
                        hold.reshowQueued = false;
                        if (held.get(hold.id) == hold && !hold.finished.get() && hold.current != null) show(hold, hold.current);
                    }
                }, wait / 50_000_000L + 1);
            }
        });
    }

    private void release(Hold hold) {
        if (!held.remove(hold.id, hold)) return;
        hold.cancelTimeout();
        Player player = hold.player;
        if (!player.isOnline()) return;
        for (Player viewer : plugin.getServer().getOnlinePlayers()) if (viewer != player) viewer.showPlayer(plugin, player);
        player.clearDialog();
        if (hold.joinMessage != null && !hold.joinMessage.isEmpty()) {
            for (Player viewer : plugin.getServer().getOnlinePlayers()) viewer.sendMessage(hold.joinMessage);
            plugin.getServer().getConsoleSender().sendMessage(hold.joinMessage);
        }
    }

    /** Bukkit calls must run on the main thread. While the plugin disables, {@link #disable()} kicks every held player itself. */
    private void onMain(Runnable task) {
        if (plugin.getServer().isPrimaryThread()) { task.run(); return; }
        try { plugin.getServer().getScheduler().runTask(plugin, task); }
        catch (IllegalPluginAccessException ignored) { }
    }

    private void execute(Hold hold, AdmissionHold.Operation operation) { hold.execute("error-check", operation); }

    private void installCommand() {
        var commands = new AdminCommands(name -> Optional.ofNullable(plugin.getServer().getPlayerExact(name))
                .map(Player::getUniqueId), new AdminCommands.Operations() {
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
        var command = java.util.Objects.requireNonNull(plugin.getCommand("consentgate"), "Missing consentgate command");
        command.setExecutor((sender, ignored, label, args) -> {
            commands.execute(args, sender::hasPermission, text -> reply(sender, text));
            return true;
        });
        command.setTabCompleter((sender, ignored, label, args) -> AdminCommands.suggest(args, sender::hasPermission));
    }

    private void reply(CommandSender sender, String text) {
        if (stopping) return;
        if (plugin.getServer().isPrimaryThread()) sender.sendMessage(text);
        else {
            try { plugin.getServer().getScheduler().runTask(plugin, () -> { if (!stopping) sender.sendMessage(text); }); }
            catch (IllegalPluginAccessException ignored) { }
        }
    }

    /** Online, held, or between the pre-login check and the join. */
    private boolean connected(UUID id) {
        var mark = marks.get(id);
        return plugin.getServer().getPlayer(id) != null || held.containsKey(id) || mark != null && mark.valid();
    }

    private void previewCommand(UUID id, String locale, Consumer<String> reply) {
        synchronized (held) {
            if (!running(reply)) return;
            if (reloading || resetting.contains(id)) { reply.accept("ConsentGate is busy. Please try again shortly."); return; }
            if (!"cancel".equalsIgnoreCase(locale) && connected(id)) {
                reply.accept("Disconnect the player first, then queue a preview using their UUID: " + id);
                return;
            }
            try { reply.accept(previews.schedule(id, locale, runtime.admissionService().orElseThrow().catalog())); }
            catch (IllegalArgumentException ex) { reply.accept(ex.getMessage()); }
        }
    }

    private void submitDatabase(Runnable operation, boolean maintenance) {
        synchronized (held) {
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
            synchronized (held) {
                if (connected(id)) {
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
                    plugin.getLogger().log(Level.WARNING, "Consent admin operation failed for " + id, ex);
                    reply.accept("Consent records could not be processed. Check the server log before retrying.");
                } finally { if (reset) resetting.remove(id); }
            }), false);
        } catch (RejectedExecutionException ex) {
            if (reset) resetting.remove(id);
            reply.accept("ConsentGate is busy. Please try again shortly.");
        }
    }

    private void configurationCheck(boolean apply, Consumer<String> reply) {
        synchronized (held) {
            if (stopping || !ready || runtime == null) {
                reply.accept("ConsentGate did not start successfully. Fix the startup error and restart.");
                return;
            }
            var deciding = deciding();
            if (reloading || (apply && (!deciding.isEmpty() || databaseJobs.pending() != 0 || !resetting.isEmpty()))) {
                var stuck = held.values().stream().filter(hold -> hold.kickRefused).toList();
                reply.accept("Reload is busy. Wait for consent sessions and database work to finish, then retry."
                        + (deciding.isEmpty() ? "" : " On the consent screen: " + names(deciding) + ".")
                        + (stuck.isEmpty() ? "" : " Kept frozen because another plugin cancelled their kick: " + names(stuck) + "."));
                return;
            }
            if (apply) reloading = true;
            try {
                submitDatabase(() -> {
                    boolean activating = !runtime.enabled();
                    ConsentGateRuntime nextRuntime = null;
                    boolean installed = false;
                    try {
                        var prepared = new RuntimeLoader().prepare(plugin.getDataFolder().toPath());
                        var nextMessages = new InterfaceMessages(plugin.getDataFolder().toPath().resolve("messages"));
                        PresentationValidator.validate(prepared.config(), prepared.catalog(), nextMessages);
                        var nextDialogs = new SpigotDialogs(prepared.config(), nextMessages);
                        nextRuntime = activating && !apply ? runtime : runtime.reconfigured(prepared);
                        synchronized (held) {
                            if (stopping) throw new IllegalStateException("ConsentGate is stopping");
                            if (apply) { previews.clear(); dialogs = nextDialogs; runtime = nextRuntime; installed = true; }
                        }
                        reply.accept(apply ? "ConsentGate reloaded. Changes apply to new connections; existing players are not kicked."
                                : activating ? "Validation passed. Configuration, documents, and messages are valid. Reload to enable; storage will be checked then."
                                : "Validation passed. Configuration, documents, messages, and saved revisions are compatible. Nothing was applied.");
                    } catch (Exception ex) {
                        plugin.getLogger().log(Level.WARNING, "ConsentGate configuration check failed", ex);
                        reply.accept("Configuration check failed: " + ex.getMessage() + ". The running configuration was kept.");
                    } finally {
                        if (activating && nextRuntime != null && !installed && apply) {
                            try { nextRuntime.close(); } catch (Exception ex) { plugin.getLogger().log(Level.WARNING, "Could not close unused runtime", ex); }
                        }
                        if (apply) synchronized (held) { reloading = false; }
                    }
                }, apply);
            } catch (RejectedExecutionException ex) {
                if (apply) reloading = false;
                reply.accept("ConsentGate is busy. Please try again shortly.");
            }
        }
    }

    void disable() {
        if (metrics != null) metrics.shutdown();
        List<Hold> ending;
        synchronized (held) { stopping = true; ending = List.copyOf(held.values()); }
        ending.forEach(hold -> hold.finish(hold.message("error-stopping")));
        // No held player stays online without the plugin, including one whose release was still queued.
        for (var hold : List.copyOf(held.values())) {
            hold.cancelTimeout();
            if (hold.player.isOnline()) hold.player.kickPlayer(hold.message("error-stopping"));
        }
        held.clear();
        marks.clear();
        previews.clear();
        if (database != null) {
            database.shutdownNow();
            try { database.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }
        if (runtime != null) {
            try { runtime.close(); } catch (Exception ex) { plugin.getLogger().warning("ConsentGate storage did not close cleanly."); }
        }
    }
    private String localized(String locale, String key) {
        var active = dialogs;
        return active == null ? InterfaceMessages.defaultText(key) : active.message(locale, key);
    }
    private void defaults() throws IOException {
        Path root = plugin.getDataFolder().toPath();
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
            if (Files.exists(target)) {
                // Missing messages still resolve from the JAR, so a file that cannot be updated does not stop startup.
                try {
                    var added = DefaultFiles.merge(entry.getKey(), target);
                    if (!added.isEmpty()) plugin.getLogger().info("Added new defaults to " + target.getFileName() + ": " + String.join(", ", added));
                } catch (DefaultFiles.ReadOnlyException ex) {
                    plugin.getLogger().info(target.getFileName() + " is read-only, so new defaults were not added to it.");
                } catch (IOException | RuntimeException ex) {
                    plugin.getLogger().warning("Could not add new defaults to " + target.getFileName() + ". Copy them from the default file if needed: " + ex);
                }
                continue;
            }
            try (var input = getClass().getClassLoader().getResourceAsStream(entry.getKey())) {
                if (input == null) throw new IOException("Missing bundled resource: " + entry.getKey());
                Files.copy(input, target);
            }
        }
    }
    private final class Hold extends AdmissionHold {
        final Player player;
        final String selectorToken = UUID.randomUUID().toString();
        final PreviewQueue.Choice preview;
        volatile String locale;
        volatile BedrockView bedrock;
        volatile String joinMessage;
        volatile boolean quit;
        volatile boolean joining;
        boolean dismountRefused;
        volatile boolean kickRefused;
        boolean reshowQueued;
        volatile BukkitTask timeout;
        Dialog current;
        long shownAt;
        boolean selecting;
        boolean redisplayQueued;
        long display;
        Hold(Player player, String locale, PreviewQueue.Choice preview) {
            super(player.getUniqueId(), task -> submitDatabase(task, false), playerOperations, () -> stopping,
                    error -> plugin.getLogger().log(Level.WARNING, "Consent operation failed for " + player.getUniqueId(), error));
            this.player = player;
            this.locale = locale;
            this.preview = preview;
        }
        void cancelTimeout() { if (timeout != null) timeout.cancel(); }
        @Override protected String message(String key) { return localized(locale, key); }
        @Override protected void closePresentation() { if (bedrock != null) bedrock.close(); }
        @Override protected void disconnect(String reason) {
            onMain(() -> {
                cancelTimeout();
                if (quit || !player.isOnline()) { held.remove(id, this); return; }
                // The kick fires the quit event at once; that handler hides the quit message and removes this hold.
                player.kickPlayer(reason);
                // A plugin that cancels PlayerKickEvent leaves the player online. The hold stays until they leave.
                if (!player.isOnline()) return;
                kickRefused = true;
                plugin.getLogger().severe("Another plugin cancelled ConsentGate's kick for " + player.getName()
                        + ". They stay frozen and hidden until they leave.");
            });
        }
        @Override protected void admitted() { onMain(() -> release(this)); }
    }
}
