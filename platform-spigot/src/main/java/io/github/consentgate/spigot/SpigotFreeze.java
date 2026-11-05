package io.github.consentgate.spigot;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityAirChangeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryInteractEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.plugin.Plugin;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Cancels what a held player does and what happens to them. Bukkit calls a listener only for the class that owns the
 * handler list, so subclasses with their own list are named here; the others reach these handlers through their parent.
 */
final class SpigotFreeze implements Listener {
    static final List<Class<? extends Event>> EVENTS = List.of(
            PlayerMoveEvent.class, PlayerTeleportEvent.class, PlayerPortalEvent.class, PlayerToggleFlightEvent.class,
            EntityMountEvent.class, VehicleEnterEvent.class,
            AsyncPlayerChatEvent.class, PlayerCommandPreprocessEvent.class, TabCompleteEvent.class,
            PlayerInteractEvent.class, PlayerInteractEntityEvent.class, PlayerInteractAtEntityEvent.class,
            PlayerArmorStandManipulateEvent.class, PlayerItemConsumeEvent.class, PlayerSwapHandItemsEvent.class,
            PlayerItemHeldEvent.class, PlayerBucketEmptyEvent.class, PlayerBucketFillEvent.class, PlayerBucketEntityEvent.class,
            PlayerFishEvent.class, PlayerEditBookEvent.class, PlayerBedEnterEvent.class, PlayerShearEntityEvent.class,
            PlayerLeashEntityEvent.class, PlayerUnleashEntityEvent.class, PlayerHarvestBlockEvent.class,
            PlayerTakeLecternBookEvent.class, ProjectileLaunchEvent.class, EntityShootBowEvent.class,
            BlockBreakEvent.class, BlockPlaceEvent.class, BlockDamageEvent.class, SignChangeEvent.class,
            InventoryOpenEvent.class, InventoryClickEvent.class, InventoryDragEvent.class, PlayerDropItemEvent.class,
            EntityPickupItemEvent.class, PlayerPickupArrowEvent.class,
            EntityDamageEvent.class, VehicleDamageEvent.class, HangingBreakByEntityEvent.class,
            FoodLevelChangeEvent.class, EntityAirChangeEvent.class, EntityTargetEvent.class);
    // Plugin, unknown, dismount, and bed teleports stay allowed, so spawn plugins still work.
    private static final Set<String> BLOCKED_TELEPORTS = Set.of("ENDER_PEARL", "CHORUS_FRUIT", "CONSUMABLE_EFFECT",
            "NETHER_PORTAL", "END_PORTAL", "END_GATEWAY", "SPECTATE", "COMMAND");

    private final Predicate<Player> held;
    private final Consumer<Player> blocked;

    /** {@code blocked} hears about actions a vanilla client can only take when no screen is open. */
    SpigotFreeze(Predicate<Player> held, Consumer<Player> blocked) {
        this.held = held;
        this.blocked = blocked;
    }

    void register(Plugin plugin) {
        for (var type : EVENTS) {
            // LOWEST so plugins that skip cancelled events skip these too; HIGHEST to undo a later un-cancel.
            for (var priority : List.of(EventPriority.LOWEST, EventPriority.HIGHEST)) {
                plugin.getServer().getPluginManager().registerEvent(type, this, priority, (listener, event) -> handle(event), plugin, false);
            }
        }
    }

    void handle(Event event) {
        if (event instanceof PlayerMoveEvent move && !(event instanceof PlayerTeleportEvent)) {
            if (!held.test(move.getPlayer()) || move.getTo() == null) return;
            Location from = move.getFrom(), to = move.getTo();
            if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return;
            if (from.getX() != to.getX() || from.getZ() != to.getZ()) blocked.accept(move.getPlayer());
            Location back = from.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            move.setTo(back);
            return;
        }
        if (event instanceof Cancellable cancellable && involvesHeld(event)) {
            cancellable.setCancelled(true);
            if (event instanceof AsyncPlayerChatEvent || event instanceof PlayerCommandPreprocessEvent
                    || event instanceof PlayerInteractEvent) blocked.accept(((PlayerEvent) event).getPlayer());
        }
    }

    boolean involvesHeld(Event event) {
        return switch (event) {
            case PlayerPortalEvent portal -> held(portal.getPlayer());
            case PlayerTeleportEvent teleport -> BLOCKED_TELEPORTS.contains(teleport.getCause().name()) && held(teleport.getPlayer());
            case PlayerEvent player -> held(player.getPlayer());
            case PlayerLeashEntityEvent leash -> held(leash.getPlayer());
            case PlayerUnleashEntityEvent unleash -> held(unleash.getPlayer());
            case TabCompleteEvent tab -> held(tab.getSender());
            case BlockBreakEvent block -> held(block.getPlayer());
            case BlockPlaceEvent block -> held(block.getPlayer());
            case BlockDamageEvent block -> held(block.getPlayer());
            case SignChangeEvent sign -> held(sign.getPlayer());
            case InventoryOpenEvent open -> held(open.getPlayer());
            case InventoryInteractEvent click -> held(click.getWhoClicked());
            case EntityDamageByEntityEvent damage -> held(damage.getEntity()) || held(damage.getDamager())
                    || damage.getDamager() instanceof Projectile projectile && held(projectile.getShooter());
            case EntityDamageEvent damage -> held(damage.getEntity());
            case VehicleDamageEvent damage -> held(damage.getAttacker());
            case HangingBreakByEntityEvent hanging -> held(hanging.getRemover());
            case VehicleEnterEvent enter -> held(enter.getEntered());
            case EntityMountEvent mount -> held(mount.getEntity());
            case ProjectileLaunchEvent launch -> held(launch.getEntity().getShooter());
            case EntityShootBowEvent bow -> held(bow.getEntity());
            case EntityPickupItemEvent pickup -> held(pickup.getEntity());
            case FoodLevelChangeEvent food -> held(food.getEntity());
            case EntityAirChangeEvent air -> held(air.getEntity());
            case EntityTargetEvent target -> held(target.getTarget());
            default -> false;
        };
    }

    private boolean held(Object subject) {
        return subject instanceof Player player && held.test(player);
    }
}
