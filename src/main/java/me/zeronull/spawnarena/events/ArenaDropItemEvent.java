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

        // Crystal fights: a fighter still in the fight can drop things. They become match items (CrystalArenaListener):
        // only that match's fighters can pick them up, and they're deleted when it ends, when everyone's own
        // inventory is put back anyway. Not while it counts down, ends, or their inventory is being given back
        if (!awaitingRestore) {
            final me.zeronull.spawnarena.Arena arena = SpawnArena.arenas.of(player);
            if (arena != null && arena.isCrystalPvp() && me.zeronull.spawnarena.CrystalPvp.isActiveFighter(arena, player))
                return;
        }

        // Staff with the permission, and only once they've switched it on with /arenadrop
        if (me.zeronull.spawnarena.commands.impl.ArenaDropCommand.mayDropInFight(player)) {
            return;
        }

        event.setCancelled(true);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cYou can't drop items while in the arena!"));
    }
}
