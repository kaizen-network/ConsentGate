package io.github.consentgate.spigot;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Zombie;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class SpigotFreezeTest {
    private final Player held = proxy(Player.class, Map.of("getUniqueId", UUID.randomUUID()));
    private final Player free = proxy(Player.class, Map.of("getUniqueId", UUID.randomUUID()));
    private final List<Player> blocked = new ArrayList<>();
    private final SpigotFreeze freeze = new SpigotFreeze(player -> player == held, blocked::add);

    @Test void cancelsActionsOfHeldPlayersOnly() {
        List<Function<Player, Event>> events = List.of(
                player -> new BlockBreakEvent(proxy(Block.class, Map.of()), player),
                player -> new PlayerDropItemEvent(player, proxy(Item.class, Map.of())),
                player -> new AsyncPlayerChatEvent(true, player, "hello", Set.of()),
                player -> new PlayerCommandPreprocessEvent(player, "/help", Set.of()),
                player -> new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null, proxy(Block.class, Map.of()), BlockFace.UP),
                player -> new TabCompleteEvent(player, "/he", List.of()),
                player -> new InventoryOpenEvent(proxy(InventoryView.class, Map.of("getPlayer", player))),
                player -> new FoodLevelChangeEvent(player, 5),
                player -> new EntityDamageEvent(player, EntityDamageEvent.DamageCause.LAVA, proxy(DamageSource.class, Map.of()), 4),
                player -> new EntityDamageByEntityEvent(player, proxy(Zombie.class, Map.of()), EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                        proxy(DamageSource.class, Map.of()), 4),
                player -> new EntityDamageByEntityEvent(proxy(Projectile.class, Map.of("getShooter", player)), proxy(Zombie.class, Map.of()),
                        EntityDamageEvent.DamageCause.PROJECTILE, proxy(DamageSource.class, Map.of()), 4),
                player -> new ProjectileLaunchEvent(proxy(Projectile.class, Map.of("getShooter", player))),
                player -> new EntityTargetEvent(proxy(Zombie.class, Map.of()), player, EntityTargetEvent.TargetReason.CLOSEST_PLAYER),
                player -> new PlayerTeleportEvent(player, at(0, 64, 0), at(30, 64, 0), PlayerTeleportEvent.TeleportCause.ENDER_PEARL),
                player -> new PlayerTeleportEvent(player, at(0, 64, 0), at(30, 64, 0), PlayerTeleportEvent.TeleportCause.COMMAND));
        for (var factory : events) {
            Event forHeld = factory.apply(held), forFree = factory.apply(free);
            freeze.handle(forHeld);
            freeze.handle(forFree);
            assertTrue(((Cancellable) forHeld).isCancelled(), forHeld.getEventName());
            assertFalse(((Cancellable) forFree).isCancelled(), forFree.getEventName());
        }
        assertEquals(List.of(held, held, held), blocked, "Chat, commands, and interaction ask for the screen again");
    }

    @Test void pluginTeleportsStillMoveHeldPlayers() {
        for (var cause : List.of(PlayerTeleportEvent.TeleportCause.PLUGIN, PlayerTeleportEvent.TeleportCause.UNKNOWN,
                PlayerTeleportEvent.TeleportCause.DISMOUNT, PlayerTeleportEvent.TeleportCause.EXIT_BED)) {
            var event = new PlayerTeleportEvent(held, at(0, 64, 0), at(100, 70, 100), cause);
            freeze.handle(event);
            assertFalse(event.isCancelled(), cause.name());
        }
    }

    @Test void movementGoesBackButTheHeadStillTurns() {
        var walk = new PlayerMoveEvent(held, at(0, 64, 0), look(at(1, 64, 0), 90, 10));
        freeze.handle(walk);
        assertEquals(List.of(0.0, 64.0, 0.0, 90f, 10f), List.of(walk.getTo().getX(), walk.getTo().getY(), walk.getTo().getZ(),
                walk.getTo().getYaw(), walk.getTo().getPitch()));
        assertEquals(List.of(held), blocked, "Walking sideways means no screen is open");
        var fall = new PlayerMoveEvent(held, at(0, 64, 0), at(0, 63, 0));
        freeze.handle(fall);
        assertEquals(64.0, fall.getTo().getY());
        assertEquals(1, blocked.size(), "Falling is not a sign of a closed screen");
        var turn = new PlayerMoveEvent(held, at(0, 64, 0), look(at(0, 64, 0), 45, 0));
        freeze.handle(turn);
        assertEquals(45f, turn.getTo().getYaw());
        var other = new PlayerMoveEvent(free, at(0, 64, 0), at(5, 64, 0));
        freeze.handle(other);
        assertEquals(5.0, other.getTo().getX());
    }

    // Bukkit calls a listener only for the class that owns the handler list. Two entries sharing one list would run twice.
    @Test void everyListedEventHasItsOwnHandlerList() throws Exception {
        var lists = new HashMap<HandlerList, Class<?>>();
        for (var type : SpigotFreeze.EVENTS) {
            Class<?> owner = type;
            while (true) {
                try { owner.getDeclaredMethod("getHandlerList"); break; }
                catch (NoSuchMethodException ex) { owner = owner.getSuperclass(); }
            }
            var method = owner.getDeclaredMethod("getHandlerList");
            method.setAccessible(true);
            var previous = lists.put((HandlerList) method.invoke(null), type);
            assertNull(previous, type + " shares a handler list with " + previous);
        }
    }

    private static final World WORLD = proxy(World.class, Map.of());
    private static Location at(double x, double y, double z) { return new Location(WORLD, x, y, z); }
    private static Location look(Location location, float yaw, float pitch) {
        location.setYaw(yaw);
        location.setPitch(pitch);
        return location;
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, Map<String, Object> answers) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> switch (method.getName()) {
            case "equals" -> self == args[0];
            case "hashCode" -> System.identityHashCode(self);
            case "toString" -> type.getSimpleName();
            default -> {
                if (answers.containsKey(method.getName())) yield answers.get(method.getName());
                Class<?> result = method.getReturnType();
                if (!result.isPrimitive() || result == void.class) yield null;
                yield java.lang.reflect.Array.get(java.lang.reflect.Array.newInstance(result, 1), 0);
            }
        });
    }
}
