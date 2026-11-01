package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.*;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/** Removes queued players (and cancels countdowns) when they wander too far from their arena. */
public final class QueueDistanceListener implements Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent e) {
        if (SpawnArena.settings == null || SpawnArena.settings.queueMaxDistance <= 0)
            return;

        final Location to = e.getTo();
        if (e.getFrom().getBlockX() == to.getBlockX() && e.getFrom().getBlockZ() == to.getBlockZ()
                && e.getFrom().getWorld() == to.getWorld())
            return;

        final Player player = e.getPlayer();

        final ArenaQueue queue = ArenaQueue.PLAYER_QUEUE_MAP.get(player);
        if (queue != null && queue.playerInQueue(player) && tooFar(queue.getArena(), to)) {
            queue.leave(player, "went too far from the arena");
            player.sendMessage(ChatColor.RED + "You've been removed from the arena queue for going too far from the arena.");
            return;
        }

        if (!SpawnArena.arenas.hasFighter(player))
            return;

        final Arena arena = SpawnArena.arenas.of(player);
        final Fight fight = arena == null ? null : arena.getFight(player);
        if (fight == null || fight.getState() != FightState.COUNTDOWN || !tooFar(arena, to))
            return;

        player.sendMessage(ChatColor.RED + "You were removed from the fight for going too far from the arena.");
        fight.cancelBecause(player, player.getName() + " went too far from the arena, so the fight was cancelled.");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpectator(final org.bukkit.event.player.PlayerGameModeChangeEvent e) {
        if (e.getNewGameMode() != org.bukkit.GameMode.SPECTATOR)
            return;
        final Player player = e.getPlayer();
        final ArenaQueue queue = ArenaQueue.PLAYER_QUEUE_MAP.get(player);
        if (queue != null && queue.playerInQueue(player)) {
            queue.leave(player, "went into spectator mode");
            player.sendMessage(ChatColor.RED + "You've been removed from the arena queue for going into spectator mode.");
            return;
        }
        if (SpawnArena.arenas == null || !SpawnArena.arenas.hasFighter(player))
            return;
        final Arena arena = SpawnArena.arenas.of(player);
        final Fight fight = arena == null ? null : arena.getFight(player);
        if (fight == null || fight.getState() != FightState.COUNTDOWN)
            return;
        player.sendMessage(ChatColor.RED + "You were removed from the fight for going into spectator mode.");
        fight.cancelBecause(player, player.getName() + " went into spectator mode, so the fight was cancelled.");
    }

    /**
     * Once a second, everyone waiting in a queue is checked where they are (on their own thread): out of spawn or too far
     * from the arena, they're taken out. Catches what the events don't, like a teleport on Folia, which fires none.
     */
    public static void startSweep() {
        Threads.globalTimer(task -> {
            for (final java.util.Map.Entry<Player, ArenaQueue> e : ArenaQueue.PLAYER_QUEUE_MAP.entrySet()) {
                final Player player = e.getKey();
                final ArenaQueue queue = e.getValue();
                if (player.isOnline())
                    Threads.entity(player, () -> sweep(player, queue));
            }
        }, 20L, 20L);
    }

    private static void sweep(final Player player, final ArenaQueue queue) {
        if (SpawnArena.settings == null || !queue.playerInQueue(player))
            return;
        if (!Arena.isSpawnWorld(player.getWorld())) {
            queue.leave(player, "left spawn");
            player.sendMessage(ChatColor.RED + "You've been removed from the arena queue. You must stay in spawn to stay in queue.");
        } else if (SpawnArena.settings.queueMaxDistance > 0 && queue.getArena().distanceFrom(player.getLocation()) > SpawnArena.settings.queueMaxDistance) {
            queue.leave(player, "went too far from the arena");
            player.sendMessage(ChatColor.RED + "You've been removed from the arena queue for going too far from the arena.");
        }
    }

    private boolean tooFar(final Arena arena, final Location loc) {
        return arena != null && arena.distanceFrom(loc) > SpawnArena.settings.queueMaxDistance;
    }
}
