package io.github.consentgate.probe;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Test-only plugin for run_spigot_probe.py. Never install it on a real server. */
public final class ProbeHelper extends JavaPlugin implements Listener {
    private final Map<UUID, Long> joined = new ConcurrentHashMap<>();

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

    @EventHandler(priority = EventPriority.LOWEST)
    public void join(PlayerJoinEvent event) { joined.put(event.getPlayer().getUniqueId(), System.nanoTime()); }

    /** Stands in for a plugin that keeps riders mounted: Keep* players, during their first five seconds online. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void dismount(EntityDismountEvent event) { if (keep(event.getEntity())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void exit(VehicleExitEvent event) { if (keep(event.getExited())) event.setCancelled(true); }

    private boolean keep(Entity entity) {
        Long since = joined.get(entity.getUniqueId());
        return entity instanceof Player player && player.getName().startsWith("Keep") && since != null
                && System.nanoTime() - since < 5_000_000_000L;
    }
}
