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
            queue.removePlayer(player);
            player.sendMessage(ChatColor.RED + "You've been removed from the arena queue for going too far from the arena.");
            return;
        }

        if (!SpawnArena.arenas.hasFighter(player))
            return;

        final Arena arena = SpawnArena.arenas.of(player);
        final Fight fight = arena == null ? null : arena.getFight(player);
        if (fight == null || fight.getState() != FightState.COUNTDOWN || !tooFar(arena, to))
            return;

        final Player other = fight.getOtherFighter(player);
        player.sendMessage(ChatColor.RED + "You were removed from the fight for going too far from the arena.");
        if (other != null)
            other.sendMessage(ChatColor.RED + "Your opponent went too far from the arena, so the fight was cancelled.");
        fight.cancelFight();
        arena.queue.tryStartFight();
    }

    private boolean tooFar(final Arena arena, final Location loc) {
        return arena != null && arena.distanceFrom(loc) > SpawnArena.settings.queueMaxDistance;
    }
}
