package io.github.consentgate.paper;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import java.util.List;

/** Finds missing Paper APIs, and refuses every login when any are missing. Uses only Bukkit types, so it loads on Spigot. */
final class UnsupportedServer {
    static final String MESSAGE = "ConsentGate needs Paper 1.21.7 or newer. Ask the server owner to switch to Paper.";
    static final List<String> PAPER_APIS = List.of(
            "io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent",
            "io.papermc.paper.event.player.PlayerCustomClickEvent",
            "io.papermc.paper.dialog.Dialog",
            "com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent",
            "net.kyori.adventure.text.Component");

    private UnsupportedServer() { }

    static List<String> missing(ClassLoader loader) {
        return PAPER_APIS.stream().filter(name -> {
            try {
                Class.forName(name, false, loader);
                return false;
            } catch (ClassNotFoundException | LinkageError ex) {
                return true;
            }
        }).toList();
    }

    static final class Refusal implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST)
        public void refuse(AsyncPlayerPreLoginEvent event) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, MESSAGE);
        }
    }
}
