package io.github.consentgate.spigot;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Decides whether this server can run the hold, and refuses every login when it cannot. Uses only Bukkit types. */
final class SpigotSupport {
    static final String HOLD_MODE = "ConsentGate is running in Spigot hold mode. Players enter the world first and stay frozen"
            + " and hidden until they accept. This is weaker than Paper, where players accept before entering the world."
            + " Other plugins still see each join. Use Paper for the full guarantee.";
    static final String ON_PAPER = "ConsentGate-Spigot is for Spigot servers. This server runs Paper, so use ConsentGate-Paper"
            + " for the full gate. Every login will be refused until then.";
    static final String NO_DIALOGS = "ConsentGate-Spigot needs Spigot 1.21.6 or newer with dialogs. Every login will be refused.";
    static final String KICK = "ConsentGate is not set up for this server. Ask the server owner to install the right ConsentGate file.";
    // Present on every Paper build ConsentGate could meet, and on its forks. Spigot has neither.
    static final List<String> PAPER = List.of("io.papermc.paper.event.player.AsyncChatEvent",
            "com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent");
    static final String CLICK = "org.bukkit.event.player.PlayerCustomClickEvent";
    static final String DIALOG = "net.md_5.bungee.api.dialog.Dialog";

    private SpigotSupport() { }

    /** The log line that explains the refusal, or null when the hold can run. */
    static String refusal(ClassLoader loader) {
        return refusal(name -> load(name, loader) != null, () -> {
            try {
                Player.class.getMethod("showDialog", load(DIALOG, loader));
                return true;
            } catch (NoSuchMethodException | LinkageError ex) {
                return false;
            }
        });
    }

    static String refusal(Predicate<String> present, BooleanSupplier showDialog) {
        if (PAPER.stream().anyMatch(present)) return ON_PAPER;
        if (!present.test(CLICK) || !present.test(DIALOG) || !showDialog.getAsBoolean()) return NO_DIALOGS;
        return null;
    }

    private static Class<?> load(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError ex) {
            return null;
        }
    }

    static final class Refusal implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST)
        public void refuse(AsyncPlayerPreLoginEvent event) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, KICK);
        }
    }
}
