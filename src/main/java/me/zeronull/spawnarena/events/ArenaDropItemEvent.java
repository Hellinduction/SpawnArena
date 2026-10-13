package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.SpawnArena;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;

public class ArenaDropItemEvent implements Listener {
    @EventHandler
    public void onCommandExecution(PlayerDropItemEvent event) {
        Player player = event.getPlayer();

        // Fight just ended but their inventory isn't back yet (a tick on Folia): still their fight inventory
        final boolean awaitingRestore = me.zeronull.spawnarena.Fight.AWAITING_RESTORE.contains(player.getUniqueId());

        if (!awaitingRestore && !SpawnArena.arenas.hasActiveFight())
            return;

        if (!awaitingRestore && !SpawnArena.arenas.hasFighter(player)) {
            return;
        }

        if (player.hasPermission("spawnarena.allowdropinfight")) {
            return;
        }

        event.setCancelled(true);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cYou can't drop items while in the arena!"));
    }
}
