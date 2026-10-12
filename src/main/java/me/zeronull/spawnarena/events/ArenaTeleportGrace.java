package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.Fight;
import me.zeronull.spawnarena.FightState;
import me.zeronull.spawnarena.FoliaUtils;
import me.zeronull.spawnarena.SpawnArena;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Protects fighters from losing because something other than their own actions teleported them
 * (typically an anticheat setback dropping them into lava/water or out of a sumo ring).
 * For a short window after such a teleport, a liquid touch or ring-out puts them back where they
 * were instead of ending the fight.
 */
public final class ArenaTeleportGrace implements Listener {
    private static final long GRACE_MILLIS = 1_500;

    private record Grace(long until, Location safe) {}
    private static final Map<UUID, Grace> GRACE = new ConcurrentHashMap<>();
    private static final java.util.Set<UUID> RESCUING = ConcurrentHashMap.newKeySet();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(final PlayerTeleportEvent e) {
        final TeleportCause cause = e.getCause();
        if (cause != TeleportCause.PLUGIN && cause != TeleportCause.UNKNOWN)
            return; // pearls, chorus fruit, commands: the player's own doing

        final Player player = e.getPlayer();
        if (RESCUING.remove(player.getUniqueId()))
            return; // our own rescue teleport must not grant another window

        final Fight fight = runningFight(player);
        if (fight == null || fight.isSpawnTeleport(player, e.getTo()))
            return;

        GRACE.put(player.getUniqueId(), new Grace(System.currentTimeMillis() + GRACE_MILLIS, safeSpot(fight, player, e.getFrom())));
    }

    /**
     * If the fighter is inside a grace window, puts them back on their safe spot and returns true —
     * the caller should then skip the loss. Each window can only be used once.
     */
    public static boolean rescue(final Player player) {
        final Grace grace = GRACE.remove(player.getUniqueId());
        if (grace == null || System.currentTimeMillis() > grace.until() || grace.safe() == null)
            return false;

        player.setFireTicks(0);
        player.setFallDistance(0);
        RESCUING.add(player.getUniqueId());
        FoliaUtils.teleport(player, grace.safe());
        RESCUING.remove(player.getUniqueId());
        return true;
    }

    public static void clear(final Player player) {
        GRACE.remove(player.getUniqueId());
    }

    /** Where they were before the teleport, unless that's also in liquid or outside the arena — then their spawn. */
    private static Location safeSpot(final Fight fight, final Player player, final Location from) {
        final Arena arena = fight.getArena();
        if (from != null && !from.getBlock().isLiquid() && arena.equals(SpawnArena.arenas.of(from)))
            return from.clone();
        final Location spawn = fight.getSpawnOf(player);
        return spawn == null ? null : spawn.clone();
    }

    private static Fight runningFight(final Player player) {
        if (SpawnArena.arenas == null || !SpawnArena.arenas.hasActiveFight() || !SpawnArena.arenas.hasFighter(player))
            return null;
        final Arena arena = SpawnArena.arenas.of(player);
        final Fight fight = arena == null ? null : arena.getFight(player);
        return fight != null && fight.getState() == FightState.IN_FIGHT ? fight : null;
    }
}
