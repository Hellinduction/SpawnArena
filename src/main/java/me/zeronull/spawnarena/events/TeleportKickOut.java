package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.*;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

public class TeleportKickOut implements Listener {

    /**
     * Whether this is their own pearl or chorus fruit, landing in the arenas' world within queueMaxDistance of this arena
     * (where /joinarenaqueue would have let them queue): then it doesn't take them out of its queue or countdown.
     */
    private static boolean staysInReach(final PlayerTeleportEvent event, final Arena arena) {
        final String cause = event.getCause().name();
        if (!cause.equals("ENDER_PEARL") && !cause.equals("CHORUS_FRUIT") && !cause.equals("CONSUMABLE_EFFECT"))
            return false;
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
            for (Fight fight : new java.util.ArrayList<>(arena.getFights())) {
                if (fight.isFighter(player) && fight.getState() == FightState.COUNTDOWN) {
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cYou were kicked out of the queue as you teleported!"));
                    fight.cancelBecause(player, player.getName() + " teleported away, so the fight was cancelled.");
                }
            }
        }
    }
}
