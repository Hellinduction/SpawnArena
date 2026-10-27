package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.*;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

public class TeleportKickOut implements Listener {

    /**
     * Whether this teleport lands in the arenas' world within queueMaxDistance of this arena (where /joinarenaqueue would
     * have let them queue): then it doesn't take them out of its queue or countdown. Whatever moved them: a pearl, chorus
     * fruit, /tp, /tpa, a launch pad, or another plugin re-teleporting a pearl under its own cause.
     */
    private static boolean staysInReach(final PlayerTeleportEvent event, final Arena arena) {
        final org.bukkit.Location to = event.getTo();
        if (to == null || to.getWorld() == null || !to.getWorld().getName().equalsIgnoreCase(Arena.SPAWN_WORLD))
            return false;
        final double max = SpawnArena.settings == null ? 64 : SpawnArena.settings.queueMaxDistance;
        return max <= 0 || arena.distanceFrom(to) <= max;
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();

        for(Arena arena : SpawnArena.arenas) {
            // A pearl or chorus fruit that lands where they could have queued from anyway doesn't count
            if (staysInReach(event, arena))
                continue;
            ArenaQueue queue = arena.queue;
            if(arena.queue.playerInQueue(player)) {
                arena.queue.removePlayer(player);
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cYou were kicked out of the queue as you teleported!"));
            }

            // Copy: cancelling a fight removes it from the arena's list
            for (Fight fight : new java.util.ArrayList<>(arena.allFights())) {
                if (fight.isFighter(player) && fight.getState() == FightState.COUNTDOWN) {
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cYou were kicked out of the queue as you teleported!"));
                    fight.cancelBecause(player, player.getName() + " teleported away, so the fight was cancelled.");
                }
            }
        }
    }
}
