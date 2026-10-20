package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.ArenaQueue;
import me.zeronull.spawnarena.FightState;
import me.zeronull.spawnarena.SpawnArena;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

public class TeleportKickOut implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();

        for(Arena arena : SpawnArena.arenas) {
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
