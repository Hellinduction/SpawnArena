package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.Fight;
import me.zeronull.spawnarena.FightState;
import me.zeronull.spawnarena.SpawnArena;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.*;

/**
 * Fighters' inventories are snapshotted before a fight and restored after it, so any trident/arrow
 * thrown during the fight that survives the fight is a duplicate (loyalty tridents fly back to the
 * owner, stuck ones can be picked up). Every projectile a fighter launches is tracked and deleted
 * when their fight ends.
 */
public final class ArenaProjectileDupeFix implements Listener {
    private static final Map<UUID, Set<Projectile>> TRACKED = new java.util.concurrent.ConcurrentHashMap<>();

    public static void discardProjectiles(final Player fighter) {
        if (fighter == null)
            return;

        final Set<Projectile> projectiles = TRACKED.remove(fighter.getUniqueId());
        if (projectiles == null)
            return;

        // Each on its own thread (on Folia it may have flown somewhere else)
        for (final Projectile projectile : projectiles)
            me.zeronull.spawnarena.Threads.entity(projectile, () -> {
                if (projectile.isValid())
                    projectile.remove();
            });
    }

    private static boolean isTracked(final Projectile projectile) {
        for (final Set<Projectile> set : TRACKED.values()) {
            if (set.contains(projectile))
                return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunch(final ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof AbstractArrow projectile)) // arrows, spectral arrows and tridents
            return;

        final ProjectileSource source = projectile.getShooter();
        if (!(source instanceof Player shooter))
            return;

        if (!SpawnArena.arenas.hasActiveFight() || !SpawnArena.arenas.hasFighter(shooter))
            return;

        TRACKED.computeIfAbsent(shooter.getUniqueId(), k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(projectile);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onProjectileLaunchBeforeFight(final ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof AbstractArrow) && !(e.getEntity() instanceof org.bukkit.entity.EnderPearl))
            return;
        if (!(e.getEntity().getShooter() instanceof Player shooter))
            return;

        final Arena arena = SpawnArena.arenas.of(shooter);
        if (arena != null && arena.getFight(shooter) != null && arena.getFight(shooter).getState() != FightState.IN_FIGHT)
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerPickupArrow(final PlayerPickupArrowEvent e) {
        final AbstractArrow arrow = e.getArrow();

        if (!isTracked(arrow))
            return;

        // Only the two fighters of a running fight may pick up projectiles from that fight
        if (arrow.getShooter() instanceof Player shooter) {
            final Arena arena = SpawnArena.arenas.of(shooter);
            final Fight fight = arena == null ? null : arena.getFight(shooter);

            if (fight != null && fight.getState() == FightState.IN_FIGHT && fight.isFighter(e.getPlayer()))
                return;
        }

        e.setCancelled(true);
        arrow.remove();
    }
}
