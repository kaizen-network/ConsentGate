package io.github.consentgate.paper;

import io.github.consentgate.core.GateSession;
import io.github.consentgate.core.PrototypeText;
import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class ConsentGatePaper extends JavaPlugin implements Listener {
    private final Map<PlayerConfigurationConnection, GateSession> sessions = new ConcurrentHashMap<>();
    private final Map<PlayerConfigurationConnection, Long> lastDisplay = new ConcurrentHashMap<>();
    private volatile boolean stopping;

    @Override public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().warning("Connection prototype only. Do not install on a production server.");
    }

    @EventHandler public void configure(AsyncPlayerConnectionConfigureEvent event) {
        var connection = event.getConnection();
        var session = new GateSession();
        synchronized (sessions) {
            if (stopping || sessions.size() >= 128 || sessions.putIfAbsent(connection, session) != null) {
                connection.disconnect(Component.text("ConsentGate is unavailable."));
                return;
            }
        }
        try {
            if (stopping) session.end(GateSession.Decision.SHUTDOWN);
            else show(connection, session, false);
            var decision = session.result().toCompletableFuture().get(300, TimeUnit.SECONDS);
            if (decision != GateSession.Decision.ACCEPTED || stopping) {
                connection.disconnect(Component.text("Connection test ended: " + decision.name().toLowerCase()));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            session.end(GateSession.Decision.SHUTDOWN);
            connection.disconnect(Component.text("Connection test interrupted."));
        } catch (Exception ex) {
            session.end(GateSession.Decision.FAILED);
            connection.disconnect(Component.text("Connection test expired or failed."));
            getLogger().warning("Connection test ended: " + ex.getClass().getSimpleName());
        } finally {
            sessions.remove(connection, session);
            lastDisplay.remove(connection);
        }
    }

    @EventHandler public void click(PlayerCustomClickEvent event) {
        if (!(event.getCommonConnection() instanceof PlayerConfigurationConnection connection)) return;
        var session = sessions.get(connection);
        if (session == null || !session.pending()) return;
        var key = event.getIdentifier();
        if (!key.namespace().equals("consentgate")) return;
        if (key.value().equals("leave/" + session.token())) session.decline(session.token());
        else if (key.value().equals("accept/" + session.token())) {
            var view = event.getDialogResponseView();
            if (view != null && Boolean.TRUE.equals(view.getBoolean("agree"))) session.accept(session.token(), true);
            else if (System.nanoTime() - lastDisplay.getOrDefault(connection, 0L) > TimeUnit.MILLISECONDS.toNanos(250)) {
                show(connection, session, true);
            }
        }
    }

    private void show(PlayerConfigurationConnection connection, GateSession session, boolean error) {
        lastDisplay.put(connection, System.nanoTime());
        var body = PrototypeText.BODY + (error ? "\n\n" + PrototypeText.REQUIRED : "");
        var dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text(PrototypeText.TITLE))
                        .canCloseWithEscape(false)
                        .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                        .body(List.of(DialogBody.plainMessage(Component.text(body))))
                        .inputs(List.of(DialogInput.bool("agree", Component.text(PrototypeText.CHECKBOX)).build()))
                        .build())
                .type(DialogType.multiAction(List.of(button("Continue", "accept", session), button("Leave", "leave", session))).build()));
        connection.getAudience().showDialog(dialog);
    }

    private ActionButton button(String label, String action, GateSession session) {
        return ActionButton.builder(Component.text(label))
                .action(DialogAction.customClick(Key.key("consentgate", action + "/" + session.token()), null)).build();
    }

    @EventHandler public void disconnected(PlayerConnectionCloseEvent event) {
        sessions.forEach((connection, session) -> {
            if (event.getPlayerUniqueId().equals(connection.getProfile().getId())) {
                session.end(GateSession.Decision.DISCONNECTED);
            }
        });
    }

    @Override public void onDisable() {
        synchronized (sessions) {
            stopping = true;
            sessions.forEach((connection, session) -> {
                try { connection.disconnect(Component.text("ConsentGate is stopping.")); }
                finally { session.end(GateSession.Decision.SHUTDOWN); }
            });
        }
    }
}
