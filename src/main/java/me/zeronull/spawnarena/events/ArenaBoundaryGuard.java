package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.*;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Keeps fighters inside their arena's region during a fight, on foot or riding something.
 * Sumo-style arenas (deathOnTouchLiquid) treat leaving the region as a ring-out loss instead,
 * since pushing fighters back in would make them impossible to knock out.
 */
public final class ArenaBoundaryGuard implements Listener {
    private static final long MESSAGE_COOLDOWN_MILLIS = 3_000;
    private final Map<UUID, Long> lastMessage = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Arena, Boolean> warnedSpawnOutside = new java.util.concurrent.ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent e) {
        if (sameBlock(e.getFrom(), e.getTo()))
            return;

        final Player player = e.getPlayer();
        final Fight fight = runningFight(player);
        if (fight == null || fight.getArena().equals(SpawnArena.arenas.of(e.getTo())))
            return;

        final Location to = e.getTo();
        handleLeaving(player, fight, e.getFrom(), back -> {
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            e.setTo(back);
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onVehicleMove(final VehicleMoveEvent e) {
        if (sameBlock(e.getFrom(), e.getTo()))
            return;

        for (final Entity passenger : new ArrayList<>(e.getVehicle().getPassengers())) {
            if (!(passenger instanceof Player player))
                continue;

            final Fight fight = runningFight(player);
            if (fight == null || fight.getArena().equals(SpawnArena.arenas.of(e.getTo())))
                continue;

            player.leaveVehicle();
            handleLeaving(player, fight, e.getFrom(), back -> FoliaUtils.teleport(player, back));
        }
    }

    private void handleLeaving(final Player player, final Fight fight, final Location from, final Consumer<Location> moveBack) {
        final Arena arena = fight.getArena();

        if (arena.isDeathOnTouchLiquid()) {
            // Knocked out of the ring by a teleport they didn't cause: put them back instead
            if (ArenaTeleportGrace.rescue(player))
                return;
            fight.eliminate(player);
            return;
        }

        if (arena.equals(SpawnArena.arenas.of(from))) {
            moveBack.accept(from.clone());
            notify(player);
            return;
        }

        // Already outside somehow — put them back on their spawn, if that's actually inside the region
        final Location spawn = fight.getSpawnOf(player);
        if (spawn != null && arena.equals(SpawnArena.arenas.of(spawn))) {
            FoliaUtils.teleport(player, spawn);
            notify(player);
        } else if (warnedSpawnOutside.put(arena, true) == null) {
            SpawnArena.INSTANCE.getLogger().warning("A spawn point of arena " + arena.getArenaName()
                    + " is outside its WorldGuard region, so fighters who leave the region can't be put back.");
        }
    }

    private static Fight runningFight(final Player player) {
        if (!SpawnArena.arenas.hasActiveFight() || !SpawnArena.arenas.hasFighter(player))
            return null;
        final Arena arena = SpawnArena.arenas.of(player);
        final Fight fight = arena == null ? null : arena.getFight(player);
        return fight != null && fight.getState() == FightState.IN_FIGHT ? fight : null;
    }

    private static boolean sameBlock(final Location a, final Location b) {
        return a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ()
                && a.getWorld() == b.getWorld();
    }

    private void notify(final Player player) {
        final long now = System.currentTimeMillis();
        final Long last = lastMessage.get(player.getUniqueId());
        if (last != null && now - last < MESSAGE_COOLDOWN_MILLIS)
            return;
        lastMessage.put(player.getUniqueId(), now);
        player.sendMessage(ChatColor.RED + "You can't leave the arena during a fight!");
    }
}
