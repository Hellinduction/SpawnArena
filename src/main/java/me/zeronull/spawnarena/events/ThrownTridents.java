package me.zeronull.spawnarena.events;

import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers the tridents each player has thrown that are still out in the world, so they can be
 * recalled into the inventory before a fight's inventory snapshot (Fight#startFight). Searching every
 * world for them isn't allowed on Folia, so they're tracked as they're thrown instead.
 */
public final class ThrownTridents implements Listener {
    private static final Map<UUID, Set<Trident>> THROWN = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onThrow(final ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof Trident trident) || !(trident.getShooter() instanceof Player player))
            return;
        final Set<Trident> set = THROWN.computeIfAbsent(player.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        set.removeIf(t -> !t.isValid()); // ones that came back, were picked up or despawned
        set.add(trident);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent e) {
        final Set<Trident> set = THROWN.get(e.getPlayer().getUniqueId());
        if (set != null) {
            set.removeIf(t -> !t.isValid());
            if (set.isEmpty())
                THROWN.remove(e.getPlayer().getUniqueId());
        }
    }

    /** Takes this player's thrown tridents that are still out in the world. */
    public static List<Trident> take(final Player player) {
        final Set<Trident> set = THROWN.remove(player.getUniqueId());
        if (set == null)
            return List.of();
        return set.stream().filter(Trident::isValid).toList();
    }
}
