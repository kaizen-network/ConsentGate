package io.github.consentgate.velocity;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.dialog.*;
import com.github.retrooper.packetevents.protocol.dialog.action.DynamicCustomAction;
import com.github.retrooper.packetevents.protocol.dialog.body.*;
import com.github.retrooper.packetevents.protocol.dialog.button.*;
import com.github.retrooper.packetevents.protocol.dialog.input.*;
import com.github.retrooper.packetevents.protocol.nbt.NBTByte;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.configuration.client.*;
import com.github.retrooper.packetevents.wrapper.configuration.server.*;
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
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import io.github.consentgate.core.GateSession;
import io.github.consentgate.core.PrototypeText;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Plugin(id = "consentgate", name = "ConsentGate", version = "0.1.0-prototype",
        description = "Experimental pre-admission dialog prototype",
        dependencies = @Dependency(id = "packetevents"))
public final class ConsentGateVelocity {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Map<Player, Pending> sessions = new ConcurrentHashMap<>();
    private final Set<Player> admitted = ConcurrentHashMap.newKeySet();
    private final Map<User, Heartbeat> lateHeartbeats = new ConcurrentHashMap<>();
    private final PacketListenerAbstract packets = new PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
        @Override public void onPacketReceive(PacketReceiveEvent event) { receive(event); }
    };
    private ScheduledTask timer;
    private volatile boolean stopping;

    @Inject public ConsentGateVelocity(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe(priority = Short.MIN_VALUE) public void initialize(ProxyInitializeEvent event) {
        PacketEvents.getAPI().getEventManager().registerListener(packets);
        timer = proxy.getScheduler().buildTask(this, this::tick).repeat(Duration.ofSeconds(1)).schedule();
        logger.warn("Connection prototype only. Do not install on a production proxy.");
    }

    @Subscribe public EventTask choose(PlayerChooseInitialServerEvent event) {
        return EventTask.withContinuation(continuation -> {
            Player player = event.getPlayer();
            User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
            if (stopping || player.getProtocolVersion().getProtocol() < 771 || user == null) {
                player.disconnect(Component.text("This prototype requires a supported Java 1.21.6+ connection."));
                continuation.resume();
                return;
            }
            var pending = new Pending(player, user);
            synchronized (sessions) {
                if (stopping || sessions.size() >= 128 || sessions.putIfAbsent(player, pending) != null) {
                    player.disconnect(Component.text("ConsentGate is unavailable."));
                    continuation.resume();
                    return;
                }
            }
            pending.session.result().whenComplete((decision, failure) -> {
                try {
                    synchronized (pending) {
                        if (pending.heartbeat != null) lateHeartbeats.put(user, pending.heartbeat);
                    }
                    if (failure == null && decision == GateSession.Decision.ACCEPTED && !stopping && player.isActive()) {
                        user.sendPacket(new WrapperConfigServerClearDialog());
                        admitted.add(player);
                    } else {
                        player.disconnect(Component.text("Connection test ended."));
                    }
                } catch (RuntimeException ex) {
                    admitted.remove(player);
                    player.disconnect(Component.text("Connection test failed."));
                } finally {
                    sessions.remove(player, pending);
                    continuation.resume();
                }
            });
            try { show(pending, false); }
            catch (RuntimeException ex) {
                logger.error("Unable to show connection test", ex);
                pending.session.end(GateSession.Decision.FAILED);
            }
        });
    }

    @Subscribe(priority = Short.MIN_VALUE) public void connecting(ServerPreConnectEvent event) {
        if (stopping || !admitted.contains(event.getPlayer())) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
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
        Pending pending = sessions.values().stream().filter(p -> p.user == event.getUser()).findFirst().orElse(null);
        if (pending == null) return;
        var type = event.getPacketType();
        if (type == PacketType.Configuration.Client.KEEP_ALIVE) {
            event.setCancelled(true);
            synchronized (pending) {
                long id = new WrapperConfigClientKeepAlive(event).getId();
                if (pending.heartbeat != null && pending.heartbeat.id() == id) pending.heartbeat = null;
            }
        } else if (type == PacketType.Configuration.Client.CUSTOM_CLICK_ACTION) {
            event.setCancelled(true);
            var click = new WrapperConfigClientCustomClickAction(event);
            String id = click.getId().toString();
            if (id.equals("consentgate:leave/" + pending.session.token())) pending.session.decline(pending.session.token());
            else if (id.equals("consentgate:accept/" + pending.session.token()) && pending.session.pending()) {
                boolean checked = click.getPayload() instanceof NBTCompound payload
                        && payload.getTagOrNull("agree") instanceof NBTByte value && value.getAsByte() == 1;
                if (checked) pending.session.accept(pending.session.token(), true);
                else if (System.nanoTime() - pending.lastDisplay > Duration.ofMillis(250).toNanos()) show(pending, true);
            }
        } else if (type == PacketType.Configuration.Client.CONFIGURATION_END_ACK) {
            event.setCancelled(true);
            pending.session.end(GateSession.Decision.FAILED);
        }
    }

    private void show(Pending pending, boolean error) {
        pending.lastDisplay = System.nanoTime();
        String body = PrototypeText.BODY + (error ? "\n\n" + PrototypeText.REQUIRED : "");
        var common = new CommonDialogData(Component.text(PrototypeText.TITLE), null, false, false,
                DialogAction.WAIT_FOR_RESPONSE,
                List.of(new PlainMessageDialogBody(new PlainMessage(Component.text(body), 350))),
                List.of(new Input("agree", new BooleanInputControl(Component.text(PrototypeText.CHECKBOX), false, "true", "false"))));
        pending.user.sendPacket(new WrapperConfigServerShowDialog(new MultiActionDialog(common,
                List.of(button("Continue", "accept", pending), button("Leave", "leave", pending)), null, 1)));
    }

    private ActionButton button(String label, String action, Pending pending) {
        return new ActionButton(new CommonButtonData(Component.text(label), null, 200),
                new DynamicCustomAction(new ResourceLocation("consentgate", action + "/" + pending.session.token()), new NBTCompound()));
    }

    private void tick() {
        long now = System.nanoTime();
        lateHeartbeats.entrySet().removeIf(entry -> now - entry.getValue().sent() > Duration.ofSeconds(30).toNanos());
        for (var pending : sessions.values()) {
            try {
                if (!pending.player.isActive()) pending.session.end(GateSession.Decision.DISCONNECTED);
                else if (now - pending.started > Duration.ofMinutes(5).toNanos()) pending.session.end(GateSession.Decision.TIMED_OUT);
                else synchronized (pending) {
                    if (!pending.session.pending()) continue;
                    if (pending.heartbeat != null && now - pending.heartbeat.sent() > Duration.ofSeconds(25).toNanos()) {
                        pending.session.end(GateSession.Decision.TIMED_OUT);
                    } else if (pending.heartbeat == null && now - pending.lastHeartbeat > Duration.ofSeconds(10).toNanos()) {
                        pending.heartbeat = new Heartbeat(ThreadLocalRandom.current().nextLong(), now);
                        pending.lastHeartbeat = now;
                        pending.user.sendPacket(new WrapperConfigServerKeepAlive(pending.heartbeat.id()));
                    }
                }
            } catch (RuntimeException ex) {
                logger.warn("Connection heartbeat failed", ex);
                pending.session.end(GateSession.Decision.FAILED);
            }
        }
    }

    @Subscribe public void disconnect(DisconnectEvent event) {
        admitted.remove(event.getPlayer());
        var pending = sessions.get(event.getPlayer());
        if (pending != null) pending.session.end(GateSession.Decision.DISCONNECTED);
    }

    @Subscribe public void shutdown(ProxyShutdownEvent event) {
        synchronized (sessions) {
            stopping = true;
            sessions.values().forEach(p -> p.session.end(GateSession.Decision.SHUTDOWN));
        }
        if (timer != null) timer.cancel();
        PacketEvents.getAPI().getEventManager().unregisterListener(packets);
        lateHeartbeats.clear();
        admitted.clear();
    }

    private record Heartbeat(long id, long sent) { }

    private static final class Pending {
        final Player player;
        final User user;
        final GateSession session = new GateSession();
        final long started = System.nanoTime();
        volatile long lastDisplay;
        long lastHeartbeat = started;
        Heartbeat heartbeat;
        Pending(Player player, User user) { this.player = player; this.user = user; }
    }
}
