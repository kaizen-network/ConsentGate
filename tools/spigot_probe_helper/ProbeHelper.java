package io.github.consentgate.probe;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Test-only plugin for run_spigot_probe.py. Never install it on a real server. */
public final class ProbeHelper extends JavaPlugin implements Listener {
    @Override public void onEnable() { getServer().getPluginManager().registerEvents(this, this); }

    /** Stands in for a plugin that cancels kicks, for test players whose name starts with "Keep". */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void kick(PlayerKickEvent event) {
        if (!event.getPlayer().getName().startsWith("Keep")) return;
        event.setCancelled(true);
        getLogger().info("Cancelled kick for " + event.getPlayer().getName());
    }

    /** Shows that a pressure plate really fired PHYSICAL interactions while ConsentGate kept the screen as it was. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void physical(PlayerInteractEvent event) {
        if (event.getAction() == Action.PHYSICAL) getLogger().info("Physical interaction by " + event.getPlayer().getName());
    }
}
