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
     * Where each fighter's last step ended. Every step starts where the one before ended, so a step starting somewhere
     * else means they were moved in between: a teleport. That's how setbacks are caught where no teleport event is
     * fired (Folia), or when another plugin changes where one goes after we have.
     */
    private static final Map<UUID, Location> LAST_STEP = new ConcurrentHashMap<>();
    /** Moved further than this between two steps = teleported (knockback and falling move far less per step). */
    private static final double JUMP_SQUARED = 2.5 * 2.5;
    /** Until when a fighter's own ender pearl or chorus fruit may have moved them: those are their own doing. */
    private static final Map<UUID, Long> OWN_TELEPORT = new ConcurrentHashMap<>();
    private static final long OWN_TELEPORT_MILLIS = 1_000;
    /**
     * Rescues on their way: until a step starts where we're putting them, their steps are from before (on Folia one
     * can still arrive after the teleport is done), and touching the danger again doesn't count.
     */
    private record Arriving(Location to, long until, boolean landed) {}
    private static final Map<UUID, Arriving> ARRIVING = new ConcurrentHashMap<>();
    /** Longest a rescue teleport is waited for. */
    private static final long ARRIVING_MILLIS = 3_000;
    /** How long after it lands a step from before it can still turn up. */
    private static final long STRAGGLER_MILLIS = 500;

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
        // A teleport we're told about isn't a jump between steps (only its cause decides whether there's a window)
        if (e.getTo() != null && runningFight(e.getPlayer()) != null)
            LAST_STEP.put(e.getPlayer().getUniqueId(), e.getTo().clone());

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

    // ── Backup: teleports seen from the steps ───────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStep(final org.bukkit.event.player.PlayerMoveEvent e) {
        final Player player = e.getPlayer();
        final Fight fight = runningFight(player);
        if (fight == null) {
            LAST_STEP.remove(player.getUniqueId());
            return;
        }
        // Our rescue is on its way: steps until they're there are from before it
        final Arriving arriving = ARRIVING.get(player.getUniqueId());
        if (arriving != null) {
            final boolean there = e.getFrom().getWorld() == arriving.to().getWorld() && e.getFrom().distanceSquared(arriving.to()) < 2.25;
            if (!there && System.currentTimeMillis() <= arriving.until())
                return;
            ARRIVING.remove(player.getUniqueId());
        }
        noteJump(player, fight, e.getFrom());
        LAST_STEP.put(player.getUniqueId(), e.getTo().clone());
    }

    /**
     * If {@code now} isn't where their last step ended, something teleported them in between: a grace window back to
     * there opens, the same as for a teleport event (unless one already has, or it was their own pearl or our spawn).
     */
    private static void noteJump(final Player player, final Fight fight, final Location now) {
        final UUID id = player.getUniqueId();
        Location last = LAST_STEP.get(id);
        if (last == null)
            last = fight.getSpawnOf(player); // no step since the fight started: they're still where they spawned
        if (last == null || now == null || last.getWorld() != now.getWorld() || last.distanceSquared(now) <= JUMP_SQUARED)
            return;
        final Long own = OWN_TELEPORT.get(id);
        if ((own != null && System.currentTimeMillis() <= own) || fight.isSpawnTeleport(player, now))
            return;
        final Grace open = GRACE.get(id);
        if (open != null && System.currentTimeMillis() <= open.until())
            return; // the teleport event already opened one
        final Location safe = safeSpot(fight, player, last);
        if (safe != null)
            GRACE.put(id, new Grace(System.currentTimeMillis() + GRACE_MILLIS, safe));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOwnPearl(final org.bukkit.event.entity.ProjectileHitEvent e) {
        if (e.getEntity() instanceof org.bukkit.entity.EnderPearl pearl && pearl.getShooter() instanceof Player player)
            OWN_TELEPORT.put(player.getUniqueId(), System.currentTimeMillis() + OWN_TELEPORT_MILLIS);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChorus(final org.bukkit.event.player.PlayerItemConsumeEvent e) {
        if (e.getItem().getType() == org.bukkit.Material.CHORUS_FRUIT)
            OWN_TELEPORT.put(e.getPlayer().getUniqueId(), System.currentTimeMillis() + OWN_TELEPORT_MILLIS);
    }

    /** Lava doesn't knock anyone out, it burns them: set back into it, they're put back before it hurts. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLava(final org.bukkit.event.entity.EntityDamageEvent e) {
        if (e.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.LAVA && e.getEntity() instanceof Player player
                && runningFight(player) != null && rescue(player))
            e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(final org.bukkit.event.player.PlayerQuitEvent e) {
        clear(e.getPlayer());
    }

    /**
     * If the fighter is inside a grace window, puts them back on their safe spot and returns true —
     * the caller should then skip the loss. Each window can only be used once.
     */
    public static boolean rescue(final Player player) {
        // A teleport no event told us about (Folia, or another plugin): it shows as being away from their last step
        final Fight fight = runningFight(player);
        // Already being put back (Folia: it lands a moment later): still touching it meanwhile isn't a loss
        final Arriving arriving = ARRIVING.get(player.getUniqueId());
        if (arriving != null && !arriving.landed() && System.currentTimeMillis() <= arriving.until())
            return true;
        if (fight != null)
            noteJump(player, fight, player.getLocation());
        final Grace grace = GRACE.remove(player.getUniqueId());
        if (grace == null || System.currentTimeMillis() > grace.until() || grace.safe() == null)
            return false;

        player.setFireTicks(0);
        player.setFallDistance(0);
        RESCUING.add(player.getUniqueId());
        LAST_STEP.put(player.getUniqueId(), grace.safe().clone()); // our own teleport isn't a jump
        ARRIVING.put(player.getUniqueId(), new Arriving(grace.safe().clone(), System.currentTimeMillis() + ARRIVING_MILLIS, false));
        // On Folia the teleport (and its event) happens later, so the marker is cleared once it's done, and where they
        // landed is their last step (they may not move again before the next setback)
        FoliaUtils.teleport(player, grace.safe()).whenComplete((ok, error) -> {
            LAST_STEP.put(player.getUniqueId(), grace.safe().clone());
            // Landed: a step from before can only turn up for a moment longer
            ARRIVING.computeIfPresent(player.getUniqueId(), (id, a) ->
                    new Arriving(a.to(), Math.min(a.until(), System.currentTimeMillis() + STRAGGLER_MILLIS), true));
            RESCUING.remove(player.getUniqueId());
        });
        return true;
    }

    /**
     * Where they are now is where they're meant to be (a teleport that's nobody's setback): no window back from it, and
     * a step from before it that turns up late isn't taken for another jump. Call on their thread once they're there.
     */
    public static void accept(final Player player) {
        accept(player, player.getLocation());
    }

    /** Like {@link #accept(Player)}, said before teleporting them there, so not even the first touch is saved. */
    public static void accept(final Player player, final Location destination) {
        final Location here = destination.clone();
        GRACE.remove(player.getUniqueId());
        LAST_STEP.put(player.getUniqueId(), here);
        ARRIVING.put(player.getUniqueId(), new Arriving(here, System.currentTimeMillis() + STRAGGLER_MILLIS, true));
    }

    public static void clear(final Player player) {
        GRACE.remove(player.getUniqueId());
        LAST_STEP.remove(player.getUniqueId());
        OWN_TELEPORT.remove(player.getUniqueId());
        ARRIVING.remove(player.getUniqueId());
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
