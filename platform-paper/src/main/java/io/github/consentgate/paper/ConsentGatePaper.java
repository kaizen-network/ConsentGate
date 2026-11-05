package io.github.consentgate.paper;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin entry point. It uses only Bukkit types, so it still loads on Spigot, where it refuses every login
 * instead of letting players in without the gate. All Paper code lives in {@link PaperGate}.
 */
public final class ConsentGatePaper extends JavaPlugin {
    private PaperGate gate;

    @Override public void onEnable() {
        var missing = UnsupportedServer.missing(getClassLoader());
        if (!missing.isEmpty()) {
            getLogger().severe("ConsentGate needs Paper 1.21.7 or newer. This server lacks " + String.join(", ", missing)
                    + ". Every login will be refused until the server runs Paper.");
            getServer().getPluginManager().registerEvents(new UnsupportedServer.Refusal(), this);
            return;
        }
        gate = new PaperGate(this);
        gate.enable();
    }

    @Override public void onDisable() {
        if (gate != null) gate.disable();
    }
}
