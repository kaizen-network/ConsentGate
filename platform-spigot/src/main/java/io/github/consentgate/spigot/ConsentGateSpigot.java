package io.github.consentgate.spigot;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin entry point. It uses only Bukkit types, so it still loads where the Spigot dialog API is missing,
 * and refuses every login there instead of letting players in without the gate. All hold code lives in {@link SpigotGate}.
 */
public final class ConsentGateSpigot extends JavaPlugin {
    private SpigotGate gate;

    @Override public void onEnable() {
        String refusal = SpigotSupport.refusal(getClassLoader());
        if (refusal != null) {
            getLogger().severe(refusal);
            getServer().getPluginManager().registerEvents(new SpigotSupport.Refusal(), this);
            return;
        }
        getLogger().warning(SpigotSupport.HOLD_MODE);
        gate = new SpigotGate(this);
        gate.enable();
    }

    @Override public void onDisable() {
        if (gate != null) gate.disable();
    }
}
