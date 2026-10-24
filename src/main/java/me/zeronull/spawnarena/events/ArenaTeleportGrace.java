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
    /**
     * When each fighter's last step came in; whether that step had them on solid ground (not in liquid); and when
     * they were last on it (the step they left it). Standing still sends no steps, so it counts as still on the ground.
     */
    private static final Map<UUID, Long> LAST_STEP_AT = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> ON_GROUND = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_GROUNDED = new ConcurrentHashMap<>();
    /**
     * A save only if they stood on the ground this recently: someone already well on their way down (knocked off, or
     * holding their game frozen while falling) isn't saved however their position then jumps.
     */
    private static final long GROUNDED_MILLIS = 1_200;
    /** A jump between steps after the game sent nothing this long is a frozen or lagging game catching up, not a setback. */
    private static final long FROZEN_GAP_MILLIS = 500;

    /**
     * For a lag-out where touching the liquid loses: whether, when their game went quiet ({@code at}), they were on the
     * platform: on the ground at their last step, or just hopping (off it under HOP_MILLIS, no lower than the spawns'
     * feet). Someone knocked off and on their way down when it went quiet wasn't.
     */
    public static boolean onPlatformAt(final Player player, final Arena arena, final long at) {
        final UUID id = player.getUniqueId();
        final Boolean grounded = ON_GROUND.get(id);
        if (grounded == null || grounded)
            return true; // on the ground at their last step (or no record: they haven't moved since they spawned)
        final Long left = LAST_GROUNDED.get(id);
        final Location last = LAST_STEP.get(id);
        final Integer feet = arena.lowestSpawnY();
        return left != null && at - left <= HOP_MILLIS && last != null && (feet == null || last.getY() >= feet - 0.5)
                && groundBelow(last);
    }

    /** Solid ground (not liquid) a few blocks under this spot: above the platform, not over the water or lava. */
    private static boolean groundBelow(final Location at) {
        if (at.getWorld() == null || !at.isChunkLoaded() || !org.bukkit.Bukkit.isOwnedByCurrentRegion(at))
            return true; // can't tell from here
        for (int dy = 0; dy <= 4; dy++) {
            final org.bukkit.block.Block block = at.getWorld().getBlockAt(at.getBlockX(), at.getBlockY() - dy, at.getBlockZ());
            if (LiquidListener.inLiquid(block))
                return false;
            if (!block.isPassable())
                return true;
        }
        return false;
    }

    private static final long HOP_MILLIS = 600;

    /** Forgets their steps: they're out of the fight, or left or joined the server. */
    public static void forgetSteps(final UUID id) {
        LAST_STEP.remove(id);
        LAST_STEP_AT.remove(id);
        ON_GROUND.remove(id);
        LAST_GROUNDED.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void forgetOnQuit(final org.bukkit.event.player.PlayerQuitEvent e) {
        forgetSteps(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void forgetOnJoin(final org.bukkit.event.player.PlayerJoinEvent e) {
        forgetSteps(e.getPlayer().getUniqueId());
    }

    /** Whether they stood on the ground within GROUNDED_MILLIS before {@code at} (true if we've no record to go by). */
    public static boolean groundedShortlyBefore(final Player player, final long at) {
        if (!Boolean.FALSE.equals(ON_GROUND.get(player.getUniqueId())))
            return true; // on the ground at their last step (or no record)
        final Long grounded = LAST_GROUNDED.get(player.getUniqueId());
        return grounded == null || at - grounded <= GROUNDED_MILLIS;
    }
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
        else
            forgetSteps(e.getPlayer().getUniqueId()); // sent out of a fight (or never in one): a new one starts afresh

        final TeleportCause cause = e.getCause();
        if (cause != TeleportCause.PLUGIN && cause != TeleportCause.UNKNOWN)
            return; // pearls, chorus fruit, commands: the player's own doing

        final Player player = e.getPlayer();
        if (RESCUING.remove(player.getUniqueId()))
            return; // our own rescue teleport must not grant another window

        final Fight fight = runningFight(player);
        if (fight == null || fight.isSpawnTeleport(player, e.getTo()))
            return;
        // Only a teleport that put them in danger (or where we can't tell): one that left them somewhere safe isn't to
        // blame if they're knocked in afterwards
        if (!intoDanger(fight, e.getTo()) || !groundedShortlyBefore(player, System.currentTimeMillis()))
            return;

        GRACE.put(player.getUniqueId(), new Grace(System.currentTimeMillis() + GRACE_MILLIS, safeSpot(fight, player, e.getFrom())));
    }

    // ── Backup: teleports seen from the steps ───────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStep(final org.bukkit.event.player.PlayerMoveEvent e) {
        final Player player = e.getPlayer();
        final Fight fight = runningFight(player);
        if (fight == null) {
            forgetSteps(player.getUniqueId());
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
        final long now = System.currentTimeMillis();
        final Long previous = LAST_STEP_AT.put(player.getUniqueId(), now);
        if (previous == null || now - previous <= FROZEN_GAP_MILLIS)
            noteJump(player, fight, e.getFrom());
        LAST_STEP.put(player.getUniqueId(), e.getTo().clone());
        final boolean grounded = player.isOnGround() && !LiquidListener.inLiquid(e.getTo().getBlock());
        final Boolean was = ON_GROUND.put(player.getUniqueId(), grounded);
        if (grounded || !Boolean.FALSE.equals(was))
            LAST_GROUNDED.put(player.getUniqueId(), now); // still on it, or this is the step they left it
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
        if (!intoDanger(fight, now))
            return; // moved somewhere safe: going in later is their own doing
        if (!groundedShortlyBefore(player, System.currentTimeMillis()))
            return; // already falling: on their way in anyway
        final Location safe = safeSpot(fight, player, last);
        if (safe != null)
            GRACE.put(id, new Grace(System.currentTimeMillis() + GRACE_MILLIS, safe));
    }

    /** Whether being moved to {@code to} put them in or over lava (or losing water), or we can't read it to tell. */
    private static boolean intoDanger(final Fight fight, final Location to) {
        if (to == null || to.getWorld() == null)
            return false;
        if (!to.isChunkLoaded() || !org.bukkit.Bukkit.isOwnedByCurrentRegion(to))
            return true;
        return dangerAtOrBelow(fight.getArena(), to) || LiquidListener.inLiquid(to.getBlock());
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
        // Only somewhere with real ground under it: someone already knocked off (in the air over the water or lava) isn't
        // put back, they were going in anyway
        if (from != null && !LiquidListener.inLiquid(from.getBlock()) && fight.getArena().equals(SpawnArena.arenas.of(from))
                && (!from.isChunkLoaded() || !org.bukkit.Bukkit.isOwnedByCurrentRegion(from) || !dangerAtOrBelow(fight.getArena(), from)))
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
