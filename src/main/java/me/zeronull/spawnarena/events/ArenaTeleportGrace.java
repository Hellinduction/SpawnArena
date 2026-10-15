package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.*;
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

    /**
     * A plugin teleport mid-fight (almost always an anticheat setback) that would put a fighter in lava, or over
     * it, or in water where touching it loses: they're sent back to where they were instead, so they never touch
     * it. The teleport still happens (just to there), which anticheats cope with better than a cancelled one.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTeleportIntoDanger(final PlayerTeleportEvent e) {
        if (e.getCause() != TeleportCause.PLUGIN && e.getCause() != TeleportCause.UNKNOWN)
            return;
        final Player player = e.getPlayer();
        if (RESCUING.contains(player.getUniqueId()))
            return;
        final Fight fight = runningFight(player);
        final Location to = e.getTo();
        final Location from = e.getFrom();
        if (fight == null || to == null || from == null || to.getWorld() != from.getWorld() || fight.isSpawnTeleport(player, to))
            return;
        // Only blocks this thread may read (on Folia the destination can belong to another region)
        if (!to.isChunkLoaded() || !org.bukkit.Bukkit.isOwnedByCurrentRegion(to))
            return;
        final Arena arena = fight.getArena();
        if (!dangerAtOrBelow(arena, to) || dangerAtOrBelow(arena, from))
            return; // fine where it's going, or where they were is no better (the rescue below handles that)
        e.setTo(from.clone());
        player.setFallDistance(0);
    }

    /** Lava, or (in an arena where touching liquid loses) water, at that spot or in the drop straight below it. */
    private static boolean dangerAtOrBelow(final Arena arena, final Location loc) {
        org.bukkit.block.Block block = loc.getBlock();
        final int bottom = loc.getWorld().getMinHeight();
        for (int i = 0; i < 64 && block.getY() >= bottom; i++, block = block.getRelative(org.bukkit.block.BlockFace.DOWN)) {
            if (block.getType() == org.bukkit.Material.LAVA || (arena.isDeathOnTouchLiquid() && LiquidListener.inLiquid(block)))
                return true;
            if (!block.isPassable())
                return false; // they'd land on this
        }
        return false;
    }

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
        // On Folia the teleport (and its event) happens later, so the marker is cleared once it's done
        FoliaUtils.teleport(player, grace.safe()).whenComplete((ok, error) -> RESCUING.remove(player.getUniqueId()));
        return true;
    }

    public static void clear(final Player player) {
        GRACE.remove(player.getUniqueId());
    }

    /** Where they were before the teleport; none (so no rescue) if that's in liquid too or outside the arena. */
    private static Location safeSpot(final Fight fight, final Player player, final Location from) {
        if (from != null && !LiquidListener.inLiquid(from.getBlock()) && fight.getArena().equals(SpawnArena.arenas.of(from)))
            return from.clone();
        return null;
    }

    private static Fight runningFight(final Player player) {
        if (SpawnArena.arenas == null || !SpawnArena.arenas.hasActiveFight() || !SpawnArena.arenas.hasFighter(player))
            return null;
        final Arena arena = SpawnArena.arenas.of(player);
        final Fight fight = arena == null ? null : arena.getFight(player);
        return fight != null && fight.getState() == FightState.IN_FIGHT ? fight : null;
    }
}
