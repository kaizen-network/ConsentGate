package io.github.consentgate.probe;

import io.papermc.paper.event.connection.configuration.PlayerConnectionReconfigureEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/** Disposable local test helper, never included in the ConsentGate distribution JARs. */
public final class StageProbe extends JavaPlugin implements Listener {
    @Override public void onEnable() { getServer().getPluginManager().registerEvents(this, this); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) || args.length != 1) return false;
        var player = getServer().getPlayerExact(args[0]);
        if (player == null) return false;
        player.setInvulnerable(true);
        player.getConnection().reenterConfiguration();
        getLogger().info("Requested test reconfiguration.");
        return true;
    }

    @EventHandler public void reconfigured(PlayerConnectionReconfigureEvent event) {
        event.getConnection().completeReconfiguration();
    }
}
