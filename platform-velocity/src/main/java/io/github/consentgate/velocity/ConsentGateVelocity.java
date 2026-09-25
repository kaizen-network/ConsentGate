package io.github.consentgate.velocity;

import io.github.consentgate.bedrock.BedrockView;
import io.github.consentgate.bedrock.GeyserBedrockBridge;

import io.github.consentgate.core.admin.PlayerOperations;
import io.github.consentgate.core.admin.PreviewQueue;
import io.github.consentgate.presentation.AdminDocuments;

import io.github.consentgate.presentation.SafeTextFormatter;
import io.github.consentgate.presentation.InterfaceMessages;
import io.github.consentgate.presentation.PresentationValidator;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.dialog.CommonDialogData;
import com.github.retrooper.packetevents.protocol.dialog.DialogAction;
import com.github.retrooper.packetevents.protocol.dialog.MultiActionDialog;
import com.github.retrooper.packetevents.protocol.dialog.action.DynamicCustomAction;
import com.github.retrooper.packetevents.protocol.dialog.body.PlainMessage;
import com.github.retrooper.packetevents.protocol.dialog.body.PlainMessageDialogBody;
import com.github.retrooper.packetevents.protocol.dialog.button.ActionButton;
import com.github.retrooper.packetevents.protocol.dialog.button.CommonButtonData;
import com.github.retrooper.packetevents.protocol.dialog.input.BooleanInputControl;
import com.github.retrooper.packetevents.protocol.dialog.input.Input;
import com.github.retrooper.packetevents.protocol.nbt.NBTByte;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientCustomClickAction;
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientKeepAlive;
import com.github.retrooper.packetevents.wrapper.configuration.server.WrapperConfigServerClearDialog;
import com.github.retrooper.packetevents.wrapper.configuration.server.WrapperConfigServerKeepAlive;
import com.github.retrooper.packetevents.wrapper.configuration.server.WrapperConfigServerShowDialog;
import com.google.inject.Inject;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import io.github.consentgate.core.admission.AdmissionDocument;
import io.github.consentgate.core.admission.AdmissionRequest;
import io.github.consentgate.core.admission.AdmissionService;
import io.github.consentgate.core.admission.AdmissionSession;
import io.github.consentgate.core.runtime.ConsentGateRuntime;
import io.github.consentgate.core.runtime.RuntimeLoader;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

@Plugin(id = "consentgate", name = "ConsentGate", version = "0.1.0",
        description = "Configurable pre-admission agreements",
        dependencies = {@Dependency(id = "packetevents"), @Dependency(id = "geyser", optional = true)})
public final class ConsentGateVelocity {
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(10);
    private static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(25);
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Map<Player, Pending> sessions = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<User, Pending> sessionsByUser = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<Player> admitted = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<UUID> resetting = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final PlayerOperations playerOperations = new PlayerOperations();
    private final PreviewQueue previews = new PreviewQueue();
    private final Map<User, Heartbeat> lateHeartbeats = new java.util.concurrent.ConcurrentHashMap<>();
    private final PacketListenerAbstract packets = new PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
        @Override public void onPacketReceive(PacketReceiveEvent event) { receiveSafely(event); }
    };
    private volatile ConsentGateRuntime runtime;
    private SafeTextFormatter formatter;
    private InterfaceMessages messages;
    private ThreadPoolExecutor databaseExecutor;
    private ScheduledTask timer;
    private volatile String startupFailure;
    private volatile boolean stopping;
    private boolean reloading;
    private int databaseWork;

    @Inject public ConsentGateVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe(priority = Short.MIN_VALUE) public void initialize(ProxyInitializeEvent event) {
        try {
            installDefaults();
            runtime = new RuntimeLoader(logger::warn).load(dataDirectory);
            formatter = new SafeTextFormatter(runtime.config().appearance());
            messages = new InterfaceMessages(dataDirectory.resolve("messages"));
            int queueSize = Math.min(10_000, Math.max(32, runtime.config().maxPending() * 2));
            databaseExecutor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(queueSize), threadFactory(), new ThreadPoolExecutor.AbortPolicy());
            if (runtime.enabled()) {
                PresentationValidator.validate(runtime.config(), runtime.admissionService().orElseThrow().catalog(), messages);
                logger.info("ConsentGate is enabled with {} storage.", runtime.config().storage().type());
            } else {
                logger.warn("ConsentGate is disabled. Edit config.yml and add a required document before enabling it.");
            }
        } catch (Exception ex) {
            if (runtime != null) {
                try { runtime.close(); }
                catch (Exception closeFailure) { ex.addSuppressed(closeFailure); }
                runtime = null;
            }
            startupFailure = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            logger.error("ConsentGate could not start. Connections will be denied: {}", startupFailure, ex);
        }
        PacketEvents.getAPI().getEventManager().registerListener(packets);
        timer = proxy.getScheduler().buildTask(this, this::tick).repeat(Duration.ofSeconds(1)).schedule();
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("consentgate").plugin(this).build(),
                new AdminCommand(name -> proxy.getPlayer(name).map(Player::getUniqueId), new AdminCommand.Operations() {
                    @Override public void status(UUID playerId, java.util.function.Consumer<String> reply) {
                        adminOperation(playerId, false, reply);
                    }
                    @Override public void reset(UUID playerId, java.util.function.Consumer<String> reply) {
                        adminOperation(playerId, true, reply);
                    }
                    @Override public void configuration(boolean apply, java.util.function.Consumer<String> reply) {
                        configure(apply, reply);
                    }
                    @Override public void preview(UUID id, String locale, java.util.function.Consumer<String> reply) {
                        previewCommand(id, locale, reply);
                    }
                    @Override public void document(String[] args, java.util.function.Consumer<String> reply) {
                        var active = runtime;
                        if (stopping || startupFailure != null || active == null || !active.enabled()) {
                            reply.accept("ConsentGate must be enabled and running before using this command.");
                            return;
                        }
                        AdminDocuments.show(active.admissionService().orElseThrow().catalog(), active.config().defaultLocale(), args, reply);
                    }
                }));
    }

    @Subscribe public EventTask choose(PlayerChooseInitialServerEvent event) {
        return EventTask.withContinuation(continuation -> {
            var held = new HeldConnection(continuation);
            Player player = event.getPlayer();
            if (stopping || startupFailure != null || runtime == null) {
                deny(player, startupFailure == null ? "ConsentGate is unavailable." : "ConsentGate configuration is invalid.", held);
                return;
            }
            if (!runtime.enabled()) {
                admitted.add(player);
                held.resume();
                return;
            }
            User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
            if (player.getProtocolVersion().getProtocol() < 771 || user == null) {
                deny(player, "ConsentGate requires a Java 1.21.6 or newer connection.", held);
                return;
            }
            var pending = new Pending(player, user, held);
            boolean rejected;
            synchronized (sessions) {
                rejected = stopping || reloading || resetting.contains(player.getUniqueId()) || sessions.size() >= runtime.config().maxPending()
                        || sessions.putIfAbsent(player, pending) != null || sessionsByUser.putIfAbsent(user, pending) != null;
                if (rejected) {
                    sessions.remove(player, pending);
                    sessionsByUser.remove(user, pending);
                } else {
                    pending.preview = previews.take(player.getUniqueId()).orElse(null);
                    if (pending.preview != null) pending.selectedLocale = pending.preview.locale();
                }
            }
            if (rejected) {
                deny(player, "ConsentGate is busy. Please try again shortly.", held);
                return;
            }
            executeDatabase(pending, () -> checkAcceptance(pending));
        });
    }

    private void checkAcceptance(Pending pending) {
        try {
            if (pending.finished.get() || stopping || !pending.player.isActive()) {
                finishDenied(pending, "Connection ended before the consent check completed.");
                return;
            }
            AdmissionService service = runtime.admissionService().orElseThrow();
            String locale = pending.player.getEffectiveLocale() == null
                    ? null : pending.player.getEffectiveLocale().toLanguageTag();
            if (pending.selectedLocale != null) locale = pending.selectedLocale;
            else if (!runtime.config().useClientLocale()) locale = runtime.config().defaultLocale();
            var request = pending.preview == null ? service.check(pending.player.getUniqueId(), locale)
                    : java.util.Optional.of(service.preview(pending.player.getUniqueId(), locale));
            if (pending.finished.get()) return;
            if (request.isEmpty()) {
                admit(pending);
                return;
            }
            if (runtime.config().nativeBedrockForms() && proxy.getPluginManager().getPlugin("geyser").isPresent()) {
                pending.bedrock = GeyserBedrockBridge.open(pending.player.getUniqueId(), formatter,
                        runtime.config().bedrockButtonColor(),
                        key -> message(pending, key),
                        () -> !pending.finished.get() && !stopping && pending.player.isActive(),
                        () -> endOrFinish(pending, AdmissionSession.Decision.DISCONNECTED, message(pending, "denied")),
                        ex -> {
                            logger.warn("Native Bedrock form failed", ex);
                            endOrFinish(pending, AdmissionSession.Decision.FAILED, "Consent form could not be shown.");
                        });
            }
            if (runtime.config().languageSelector().enabled() && (pending.preview == null || pending.preview.locale() == null)) showLanguageSelector(pending);
            else beginSession(pending, request.orElseThrow());
        } catch (Exception ex) {
            logger.error("Consent check failed for {}", pending.player.getUniqueId(), ex);
            finishDenied(pending, "Consent records could not be checked. Please try again later.");
        }
    }

    private void beginSession(Pending pending, AdmissionRequest request) {
        if (pending.finished.get()) return;
        AdmissionSession session = new AdmissionSession(request);
        pending.session = session;
        session.result().whenComplete((decision, failure) -> {
            if (failure == null && decision == AdmissionSession.Decision.ACCEPTED) {
                if (request.preview()) finishDenied(pending, PreviewQueue.COMPLETE);
                else executeDatabase(pending, () -> persistAcceptance(pending));
            } else {
                finishDenied(pending, message(pending, "denied"));
            }
        });
        try { showSummary(pending, false); }
        catch (RuntimeException ex) {
            logger.error("Unable to show the consent dialog", ex);
            session.end(AdmissionSession.Decision.FAILED);
        }
    }

    private void persistAcceptance(Pending pending) {
        try {
            if (pending.finished.get() || stopping || !pending.player.isActive()) {
                finishDenied(pending, "Connection ended before consent was saved.");
                return;
            }
            AdmissionSession session = pending.session;
            if (session == null) throw new IllegalStateException("Admission session is missing");
            runtime.admissionService().orElseThrow().grant(pending.player.getUniqueId(), session,
                    Instant.now(), "in-game");
            if (pending.finished.get() || stopping || !pending.player.isActive()) {
                finishDenied(pending, "Connection ended before admission.");
                return;
            }
            clearPresentation(pending);
            admitted.add(pending.player);
            if (pending.finished.get() || stopping || !pending.player.isActive() || !finish(pending)) {
                admitted.remove(pending.player);
                finishDenied(pending, "Connection ended before admission.");
                return;
            }
        } catch (Exception ex) {
            logger.error("Consent save failed for {}", pending.player.getUniqueId(), ex);
            finishDenied(pending, "Consent could not be saved. Please try again later.");
        }
    }

    @Subscribe(priority = Short.MIN_VALUE) public void connecting(ServerPreConnectEvent event) {
        if (stopping || !admitted.contains(event.getPlayer())) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
        }
    }

    private void receiveSafely(PacketReceiveEvent event) {
        try { receive(event); }
        catch (RuntimeException ex) {
            Pending pending = sessionsByUser.get(event.getUser());
            if (pending != null) endOrFinish(pending, AdmissionSession.Decision.FAILED, "Invalid consent response.");
            logger.warn("Invalid consent dialog packet", ex);
        }
    }

    private void receive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Configuration.Client.KEEP_ALIVE) {
            var late = lateHeartbeats.get(event.getUser());
            if (late != null && new WrapperConfigClientKeepAlive(event).getId() == late.id()) {
                event.setCancelled(true);
                lateHeartbeats.remove(event.getUser(), late);
                return;
            }
        }
        Pending pending = sessionsByUser.get(event.getUser());
        if (pending == null) return;
        var type = event.getPacketType();
        if (type == PacketType.Configuration.Client.KEEP_ALIVE) {
            synchronized (pending) {
                long id = new WrapperConfigClientKeepAlive(event).getId();
                if (pending.heartbeat != null && pending.heartbeat.id() == id) {
                    event.setCancelled(true);
                    pending.heartbeat = null;
                }
            }
        } else if (type == PacketType.Configuration.Client.CUSTOM_CLICK_ACTION) {
            var click = new WrapperConfigClientCustomClickAction(event);
            if (click.getId().toString().startsWith("consentgate:")) {
                event.setCancelled(true);
                handleClick(pending, click);
            }
        } else if (type == PacketType.Configuration.Client.CONFIGURATION_END_ACK) {
            event.setCancelled(true);
            endOrFinish(pending, AdmissionSession.Decision.FAILED, "Connection configuration ended unexpectedly.");
        }
    }

    private void handleClick(Pending pending, WrapperConfigClientCustomClickAction click) {
        if (pending.finished.get() || pending.bedrock != null) return;
        String id = click.getId().toString();
        String prefix = "consentgate:";
        if (!id.startsWith(prefix)) return;
        String[] parts = id.substring(prefix.length()).split("/");
        if (parts.length == 3 && parts[0].equals("language")) {
            selectLanguage(pending, index(parts[1]), parts[2]);
            return;
        }
        if (parts.length == 2 && parts[0].equals("selector-leave")
                && parts[1].equals(pending.selectorToken)
                && pending.languageSelection.compareAndSet(true, false)) {
            finishDenied(pending, message(pending, "denied"));
            return;
        }
        AdmissionSession session = pending.session;
        if (session == null) return;
        if (parts.length < 2 || !parts[parts.length - 1].equals(session.token())) return;
        switch (parts[0]) {
            case "leave" -> {
                if (parts.length == 2) session.decline(session.token());
            }
            case "accept" -> {
                if (parts.length != 2) return;
                Map<String, Boolean> selections = selections(pending, click.getPayload());
                if (selections == null || !session.accept(session.token(), selections)) {
                    redisplaySummary(pending, true);
                }
            }
            case "read" -> {
                if (parts.length != 3) return;
                Map<String, Boolean> selections = selections(pending, click.getPayload());
                if (selections == null || !session.updateSelections(session.token(), selections)) {
                    redisplaySummary(pending, true);
                    return;
                }
                showPage(pending, index(parts[1]), 0);
            }
            case "back" -> {
                if (parts.length == 2) showSummary(pending, false);
            }
            case "previous" -> {
                if (parts.length == 4) showPage(pending, index(parts[1]), index(parts[2]) - 1);
            }
            case "next" -> {
                if (parts.length == 4) showPage(pending, index(parts[1]), index(parts[2]) + 1);
            }
            default -> { }
        }
    }

    private void showLanguageSelector(Pending pending) {
        if (pending.finished.get()) return;
        var selector = runtime.config().languageSelector();
        pending.selectorToken = UUID.randomUUID().toString();
        pending.languageSelection.set(true);
        if (pending.bedrock != null) {
            String token = pending.selectorToken;
            pending.bedrock.language(selector, index -> selectLanguage(pending, index, token));
            return;
        }
        var buttons = new ArrayList<ActionButton>();
        int index = 0;
        for (String label : selector.options().values()) {
            buttons.add(rawButton(label, "language/" + index++ + "/" + pending.selectorToken));
        }
        pending.user.sendPacket(new WrapperConfigServerShowDialog(new MultiActionDialog(
                common(formatter.title(selector.title()), formatter.text(selector.prompt()), List.of()),
                buttons, rawButton(message(pending, "leave"), "selector-leave/" + pending.selectorToken), selector.columns())));
    }

    private void selectLanguage(Pending pending, int optionIndex, String token) {
        var options = runtime.config().languageSelector().options();
        if (optionIndex < 0 || optionIndex >= options.size() || !token.equals(pending.selectorToken)
                || !pending.languageSelection.compareAndSet(true, false)) return;
        String locale = options.keySet().stream().skip(optionIndex).findFirst().orElseThrow();
        pending.selectedLocale = locale;
        executeDatabase(pending, () -> checkSelectedLanguage(pending, locale));
    }

    private void checkSelectedLanguage(Pending pending, String locale) {
        try {
            if (pending.finished.get() || stopping || !pending.player.isActive()) {
                finishDenied(pending, "Connection ended before the consent check completed.");
                return;
            }
            var service = runtime.admissionService().orElseThrow();
            var request = pending.preview == null ? service.checkExactLocale(pending.player.getUniqueId(), locale)
                    : java.util.Optional.of(service.preview(pending.player.getUniqueId(), locale));
            if (request.isEmpty()) admit(pending);
            else beginSession(pending, request.orElseThrow());
        } catch (Exception ex) {
            logger.error("Consent check failed for {}", pending.player.getUniqueId(), ex);
            finishDenied(pending, "Consent records could not be checked. Please try again later.");
        }
    }

    private void admit(Pending pending) {
        if (!pending.finished.get() && !stopping && pending.player.isActive()) {
            clearPresentation(pending);
            admitted.add(pending.player);
            if (pending.finished.get() || stopping || !pending.player.isActive() || !finish(pending)) {
                admitted.remove(pending.player);
            }
        } else finishDenied(pending, "ConsentGate is stopping.");
    }

    private Map<String, Boolean> selections(Pending pending, Object rawPayload) {
        if (!(rawPayload instanceof NBTCompound payload)) return null;
        AdmissionSession session = pending.session;
        if (session == null) return null;
        List<AdmissionDocument> documents = session.request().documents();
        Set<String> expected = java.util.stream.IntStream.range(0, documents.size())
                .mapToObj(ConsentGateVelocity::inputKey).collect(java.util.stream.Collectors.toSet());
        if (!payload.getTagNames().equals(expected)) return null;
        var result = new LinkedHashMap<String, Boolean>();
        for (int index = 0; index < documents.size(); index++) {
            String key = inputKey(index);
            if (!(payload.getTagOrNull(key) instanceof NBTByte value) || (value.getAsByte() != 0 && value.getAsByte() != 1)) {
                return null;
            }
            result.put(documents.get(index).id(), value.getAsByte() == 1);
        }
        return result;
    }

    private void showSummary(Pending pending, boolean error) {
        AdmissionSession session = pending.session;
        if (session == null || !session.pending() || pending.finished.get()) return;
        if (pending.bedrock != null) {
            pending.bedrock.summary(session, error);
            return;
        }
        pending.lastDisplay = System.nanoTime();
        Component body = formatter.text(message(pending, "prompt"));
        for (AdmissionDocument document : session.request().documents()) {
            body = body.append(Component.text("\n\n"))
                    .append(formatter.accent(document.title()))
                    .append(formatter.mutedPlain(" (" + message(pending, "version") + " " + document.version() + ")"))
                    .append(Component.newline())
                    .append(formatter.text(document.summary()));
        }
        if (error) body = body.append(Component.text("\n\n")).append(formatter.error(message(pending, "required")));
        Map<String, Boolean> selected = session.selections();
        List<Input> inputs = java.util.stream.IntStream.range(0, session.request().documents().size())
                .mapToObj(index -> {
                    AdmissionDocument document = session.request().documents().get(index);
                    return new Input(inputKey(index), new BooleanInputControl(formatter.text(document.checkbox()),
                            selected.getOrDefault(document.id(), false), "true", "false"));
                }).toList();
        var buttons = new ArrayList<ActionButton>();
        for (int index = 0; index < session.request().documents().size(); index++) {
            AdmissionDocument document = session.request().documents().get(index);
            buttons.add(button(document.readButton(), "read/" + index, pending));
        }
        buttons.add(button(message(pending, "continue"), "accept", pending));
        pending.user.sendPacket(new WrapperConfigServerShowDialog(
                new MultiActionDialog(common(formatter.title(message(pending, "title")), body, inputs), buttons,
                        button(message(pending, "leave"), "leave", pending), 2)));
    }

    private void redisplaySummary(Pending pending, boolean error) {
        long displayed = pending.lastDisplay;
        long delay = Duration.ofMillis(250).toNanos() - (System.nanoTime() - displayed);
        if (delay <= 0) {
            showSummary(pending, error);
        } else if (pending.redisplayQueued.compareAndSet(false, true)) {
            try {
                proxy.getScheduler().buildTask(this, () -> {
                    pending.redisplayQueued.set(false);
                    if (pending.lastDisplay == displayed) showSummary(pending, error);
                }).delay(Duration.ofNanos(delay)).schedule();
            } catch (RuntimeException ex) {
                pending.redisplayQueued.set(false);
                endOrFinish(pending, AdmissionSession.Decision.FAILED, "ConsentGate is stopping.");
            }
        }
    }

    private void showPage(Pending pending, int documentIndex, int pageIndex) {
        AdmissionSession session = pending.session;
        if (session == null || !session.pending() || pending.finished.get() || documentIndex < 0
                || documentIndex >= session.request().documents().size()) return;
        AdmissionDocument document = session.request().documents().get(documentIndex);
        if (pageIndex < 0 || pageIndex >= document.pages().size()) return;
        pending.lastDisplay = System.nanoTime();
        var page = document.pages().get(pageIndex);
        Component title = formatter.accent(document.title());
        if (document.pages().size() > 1 && !formatter.accent(page.title()).equals(title)) {
            title = title.append(formatter.muted(": ")).append(formatter.accent(page.title()));
        }
        Component body = formatter.text(page.body());
        if (document.pages().size() > 1) body = body.append(Component.text("\n\n"))
                .append(formatter.mutedPlain(message(pending, "page").replace("{page}", String.valueOf(pageIndex + 1))
                        .replace("{pages}", String.valueOf(document.pages().size()))));
        var buttons = new ArrayList<ActionButton>();
        if (pageIndex > 0) buttons.add(button(message(pending, "previous"), "previous/" + documentIndex + "/" + pageIndex, pending));
        if (pageIndex + 1 < document.pages().size()) {
            buttons.add(button(message(pending, "next"), "next/" + documentIndex + "/" + pageIndex, pending));
        }
        if (buttons.isEmpty()) buttons.add(button(message(pending, "back"), "back", pending));
        pending.user.sendPacket(new WrapperConfigServerShowDialog(
                new MultiActionDialog(common(title, body, List.of(), document.pages().size() > 1), buttons,
                        buttons.size() == 1 && document.pages().size() == 1 ? null : button(message(pending, "back"), "back", pending), 2)));
    }

    private CommonDialogData common(Component title, Component body, List<Input> inputs) {
        return common(title, body, inputs, true);
    }

    private CommonDialogData common(Component title, Component body, List<Input> inputs, boolean canClose) {
        return new CommonDialogData(title, null, canClose, false, DialogAction.CLOSE,
                List.of(new PlainMessageDialogBody(new PlainMessage(body, 500))), inputs);
    }

    private String message(Pending pending, String key) {
        String locale = pending.selectedLocale;
        if (locale == null && pending.session != null) locale = pending.session.request().documents().getFirst().locale();
        if (locale == null && runtime.config().useClientLocale() && pending.player.getEffectiveLocale() != null) {
            locale = pending.player.getEffectiveLocale().toLanguageTag();
        }
        return messages.text(locale, runtime.config().defaultLocale(), key);
    }

    private ActionButton button(String label, String action, Pending pending) {
        AdmissionSession session = pending.session;
        if (session == null) throw new IllegalStateException("Admission session is missing");
        return rawButton(label, action + "/" + session.token());
    }

    private ActionButton rawButton(String label, String action) {
        return new ActionButton(new CommonButtonData(formatter.button(label), null, 200),
                new DynamicCustomAction(new ResourceLocation("consentgate", action), new NBTCompound()));
    }

    private static int index(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ex) { return -1; }
    }

    private static String inputKey(int index) {
        return "document_" + index;
    }

    private void tick() {
        long now = System.nanoTime();
        lateHeartbeats.entrySet().removeIf(entry -> now - entry.getValue().sent() > Duration.ofSeconds(30).toNanos());
        for (var pending : sessions.values()) {
            try {
                synchronized (pending) {
                    if (pending.finished.get()) continue;
                    if (!pending.player.isActive()) {
                        endOrFinish(pending, AdmissionSession.Decision.DISCONNECTED, "Connection ended.");
                    } else if (now - pending.started > Duration.ofSeconds(runtime.config().timeoutSeconds()).toNanos()) {
                        endOrFinish(pending, AdmissionSession.Decision.TIMED_OUT, "Consent request timed out.");
                    } else if (pending.heartbeat != null && now - pending.heartbeat.sent() > HEARTBEAT_TIMEOUT.toNanos()) {
                        endOrFinish(pending, AdmissionSession.Decision.TIMED_OUT, "Consent connection timed out.");
                    } else if (pending.heartbeat == null && now - pending.lastHeartbeat > HEARTBEAT_INTERVAL.toNanos()) {
                        pending.heartbeat = new Heartbeat(ThreadLocalRandom.current().nextLong(), now);
                        pending.lastHeartbeat = now;
                        pending.user.sendPacket(new WrapperConfigServerKeepAlive(pending.heartbeat.id()));
                    }
                }
            } catch (RuntimeException ex) {
                logger.warn("Connection heartbeat failed", ex);
                endOrFinish(pending, AdmissionSession.Decision.FAILED, "ConsentGate connection handling failed.");
            }
        }
    }

    private void executeDatabase(Pending pending, Runnable operation) {
        try {
            submitDatabase(() -> playerOperations.run(pending.player.getUniqueId(), operation), false);
        } catch (RejectedExecutionException ex) {
            finishDenied(pending, "ConsentGate is busy. Please try again shortly.");
        }
    }

    private void submitDatabase(Runnable operation, boolean maintenance) {
        synchronized (sessions) {
            if (stopping || (reloading && !maintenance) || databaseExecutor == null || databaseExecutor.isShutdown()) {
                throw new RejectedExecutionException();
            }
            databaseWork++;
            try {
                databaseExecutor.execute(() -> {
                    try { operation.run(); }
                    finally { synchronized (sessions) { databaseWork--; } }
                });
            } catch (RejectedExecutionException ex) {
                databaseWork--;
                throw ex;
            }
        }
    }

    private void configure(boolean apply, java.util.function.Consumer<String> reply) {
        synchronized (sessions) {
            if (stopping || startupFailure != null || runtime == null) {
                reply.accept("ConsentGate did not start successfully. Fix the startup error and restart.");
                return;
            }
            if (reloading || (apply && (!sessions.isEmpty() || databaseWork != 0 || !resetting.isEmpty()))) {
                reply.accept("Reload is busy. Wait for consent sessions and database work to finish, then retry.");
                return;
            }
            if (apply) reloading = true;
            try {
                submitDatabase(() -> {
                    boolean activating = !runtime.enabled();
                    ConsentGateRuntime nextRuntime = null;
                    boolean installed = false;
                    try {
                        var prepared = new RuntimeLoader().prepare(dataDirectory);
                        var nextMessages = new InterfaceMessages(dataDirectory.resolve("messages"));
                        PresentationValidator.validate(prepared.config(), prepared.catalog(), nextMessages);
                        var nextFormatter = new SafeTextFormatter(prepared.config().appearance());
                        nextRuntime = activating && !apply ? runtime : runtime.reconfigured(prepared);
                        synchronized (sessions) {
                            if (stopping) throw new IllegalStateException("ConsentGate is stopping");
                            if (apply) {
                                previews.clear();
                                formatter = nextFormatter;
                                messages = nextMessages;
                                runtime = nextRuntime;
                                installed = true;
                            }
                        }
                        reply.accept(apply ? "ConsentGate reloaded. Changes apply to new connections; existing players are not kicked."
                                : activating ? "Validation passed. Configuration, documents, and messages are valid. Reload to enable; storage will be checked then."
                                : "Validation passed. Configuration, documents, messages, and saved revisions are compatible. Nothing was applied.");
                    } catch (Exception ex) {
                        logger.warn("ConsentGate configuration check failed", ex);
                        reply.accept("Configuration check failed: " + ex.getMessage() + ". The running configuration was kept.");
                    } finally {
                        if (activating && nextRuntime != null && !installed && apply) {
                            try { nextRuntime.close(); } catch (Exception ex) { logger.warn("Could not close unused runtime", ex); }
                        }
                        if (apply) synchronized (sessions) { reloading = false; }
                    }
                }, apply);
            } catch (RejectedExecutionException ex) {
                if (apply) reloading = false;
                reply.accept("ConsentGate is busy. Please try again shortly.");
            }
        }
    }

    private void adminOperation(UUID playerId, boolean reset, java.util.function.Consumer<String> reply) {
        if (stopping || startupFailure != null || runtime == null || !runtime.enabled()) {
            reply.accept("ConsentGate must be enabled and running before using this command.");
            return;
        }
        if (reset) {
            synchronized (sessions) {
                if (proxy.getPlayer(playerId).isPresent()
                        || sessions.keySet().stream().anyMatch(player -> player.getUniqueId().equals(playerId))) {
                    reply.accept("Disconnect the player first, then reset using their UUID: " + playerId);
                    return;
                }
                if (!resetting.add(playerId)) {
                    reply.accept("A reset is already pending for " + playerId + ".");
                    return;
                }
            }
        }
        try {
            submitDatabase(() -> playerOperations.run(playerId, () -> {
                try {
                    if (stopping) {
                        reply.accept("ConsentGate is stopping. The command was not applied.");
                        return;
                    }
                    AdmissionService service = runtime.admissionService().orElseThrow();
                    if (reset) {
                        service.reset(playerId, Instant.now());
                        reply.accept("Consent reset for " + playerId + " in scope " + runtime.config().scope()
                                + ". History was kept. Acceptance is required on the next connection.");
                    } else {
                        reply.accept("Consent status for " + playerId + " in scope " + runtime.config().scope() + ":");
                        for (var status : service.status(playerId)) {
                            reply.accept(status.id() + " (" + status.version() + "): "
                                    + (status.accepted() ? "accepted" : "acceptance required"));
                        }
                    }
                } catch (Exception ex) {
                    logger.error("Consent admin operation failed for {}", playerId, ex);
                    reply.accept("Consent records could not be processed. Check the server log before retrying.");
                } finally {
                    if (reset) resetting.remove(playerId);
                }
            }), false);
        } catch (RejectedExecutionException ex) {
            if (reset) resetting.remove(playerId);
            reply.accept("ConsentGate is busy. Please try again shortly.");
        }
    }

    private void previewCommand(UUID id, String locale, java.util.function.Consumer<String> reply) {
        synchronized (sessions) {
            if (stopping || startupFailure != null || runtime == null || !runtime.enabled()) {
                reply.accept("ConsentGate must be enabled and running before using this command.");
                return;
            }
            if (reloading || resetting.contains(id)) { reply.accept("ConsentGate is busy. Please try again shortly."); return; }
            if (!"cancel".equalsIgnoreCase(locale) && (proxy.getPlayer(id).isPresent()
                    || sessions.keySet().stream().anyMatch(player -> player.getUniqueId().equals(id)))) {
                reply.accept("Disconnect the player first, then queue a preview using their UUID: " + id);
                return;
            }
            try { reply.accept(previews.schedule(id, locale, runtime.admissionService().orElseThrow().catalog())); }
            catch (IllegalArgumentException ex) { reply.accept(ex.getMessage()); }
        }
    }

    private void finishDenied(Pending pending, String message) {
        if (!markFinished(pending)) return;
        admitted.remove(pending.player);
        DenialCompletion.releaseThenDisconnect(() -> release(pending),
                () -> {
                    try { clearPresentation(pending); }
                    finally { pending.player.disconnect(Component.text(message)); }
                },
                ex -> logger.warn("Could not disconnect a denied connection", ex));
    }

    private void endOrFinish(Pending pending, AdmissionSession.Decision decision, String message) {
        AdmissionSession session = pending.session;
        if (session == null || !session.end(decision)) finishDenied(pending, message);
    }

    private boolean finish(Pending pending) {
        if (!markFinished(pending)) return false;
        release(pending);
        return true;
    }

    private boolean markFinished(Pending pending) {
        synchronized (pending) {
            if (!pending.finished.compareAndSet(false, true)) return false;
            if (pending.heartbeat != null) lateHeartbeats.put(pending.user, pending.heartbeat);
        }
        return true;
    }

    private void release(Pending pending) {
        sessions.remove(pending.player, pending);
        sessionsByUser.remove(pending.user, pending);
        pending.held.resume();
    }

    private void clearPresentation(Pending pending) {
        if (pending.bedrock != null) pending.bedrock.close();
        else pending.user.sendPacket(new WrapperConfigServerClearDialog());
    }

    private void deny(Player player, String message, HeldConnection held) {
        admitted.remove(player);
        try { player.disconnect(Component.text(message)); }
        catch (RuntimeException ex) { logger.warn("Could not disconnect a denied connection", ex); }
        finally { held.resume(); }
    }

    private void installDefaults() throws IOException {
        Files.createDirectories(dataDirectory);
        if (Files.isSymbolicLink(dataDirectory)) throw new IOException("Plugin data directory cannot be a symbolic link");
        copyDefault("config.yml", dataDirectory.resolve("config.yml"));
        Path messageDirectory = dataDirectory.resolve("messages");
        if (Files.isSymbolicLink(messageDirectory)) throw new IOException("Message directory cannot be a symbolic link");
        Files.createDirectories(messageDirectory);
        copyDefault("messages/en-US.properties", messageDirectory.resolve("en-US.properties"));
        copyDefault("messages/id-ID.properties", messageDirectory.resolve("id-ID.properties"));
        Path documents = dataDirectory.resolve("documents");
        if (Files.isSymbolicLink(documents)) throw new IOException("Document directory cannot be a symbolic link");
        Files.createDirectories(documents);
        copyDefault("example-terms.yml", documents.resolve("terms.yml.example"));
        copyDefault("example-privacy.yml", documents.resolve("privacy.yml.example"));
    }

    private void copyDefault(String resource, Path target) throws IOException {
        if (Files.isSymbolicLink(target)) throw new IOException("Default target cannot be a symbolic link: " + target.getFileName());
        if (Files.exists(target)) return;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing bundled resource: " + resource);
            Files.copy(input, target);
        }
    }

    private static ThreadFactory threadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "consentgate-database-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Subscribe public void disconnect(DisconnectEvent event) {
        admitted.remove(event.getPlayer());
        var pending = sessions.get(event.getPlayer());
        if (pending != null) endOrFinish(pending, AdmissionSession.Decision.DISCONNECTED, "Connection ended.");
    }

    @Subscribe public void shutdown(ProxyShutdownEvent event) {
        List<Pending> ending;
        synchronized (sessions) {
            stopping = true;
            ending = List.copyOf(sessions.values());
        }
        ending.forEach(pending -> endOrFinish(pending, AdmissionSession.Decision.SHUTDOWN, "ConsentGate is stopping."));
        if (timer != null) timer.cancel();
        PacketEvents.getAPI().getEventManager().unregisterListener(packets);
        if (databaseExecutor != null) {
            databaseExecutor.shutdownNow();
            try {
                if (!databaseExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warn("ConsentGate database workers did not stop within five seconds.");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                logger.warn("Interrupted while stopping ConsentGate database workers.");
            }
        }
        if (runtime != null) {
            try { runtime.close(); }
            catch (Exception ex) { logger.warn("ConsentGate storage did not close cleanly", ex); }
        }
        lateHeartbeats.clear();
        admitted.clear();
        previews.clear();
    }

    private record Heartbeat(long id, long sent) { }

    private static final class HeldConnection {
        private final com.velocitypowered.api.event.Continuation continuation;
        private final AtomicBoolean resumed = new AtomicBoolean();
        HeldConnection(com.velocitypowered.api.event.Continuation continuation) { this.continuation = continuation; }
        void resume() { if (resumed.compareAndSet(false, true)) continuation.resume(); }
    }

    private static final class Pending {
        final Player player;
        final User user;
        final HeldConnection held;
        volatile AdmissionSession session;
        volatile BedrockView bedrock;
        PreviewQueue.Choice preview;
        volatile String selectorToken;
        volatile String selectedLocale;
        final AtomicBoolean languageSelection = new AtomicBoolean();
        final AtomicBoolean redisplayQueued = new AtomicBoolean();
        final AtomicBoolean finished = new AtomicBoolean();
        final long started = System.nanoTime();
        volatile long lastDisplay;
        long lastHeartbeat = started;
        Heartbeat heartbeat;
        Pending(Player player, User user, HeldConnection held) {
            this.player = player;
            this.user = user;
            this.held = held;
        }
    }
}
