package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.SpawnArena;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

public class ArenaCommandEvent implements Listener {

    @EventHandler
    public void onCommandExecution(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();

        if (!SpawnArena.arenas.hasActiveFight()) {
            return;
        }

        if (!SpawnArena.arenas.hasFighter(player)) {
            return;
        }

        if (player.hasPermission("spawnarena.allowcommandsinfight")) {
            return;
        }

        // During the countdown, /joinarenaqueue is allowed so players can still back out
        final String label = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase();
        if (label.equals("joinarenaqueue") || label.equals("spawnarena:joinarenaqueue")) {
            final me.zeronull.spawnarena.Arena arena = SpawnArena.arenas.of(player);
            final me.zeronull.spawnarena.Fight fight = arena == null ? null : arena.getFight(player);
            if (fight != null && fight.getState() == me.zeronull.spawnarena.FightState.COUNTDOWN)
                return;
        }

        // A free-for-all is left with /leavearena (/joinarenaqueue there just says so)
        final me.zeronull.spawnarena.FfaFight ffa = me.zeronull.spawnarena.FfaFight.of(player);
        if (ffa != null) {
            if (label.equals("leavearena") || label.equals("spawnarena:leavearena")
                    || label.equals("joinarenaqueue") || label.equals("spawnarena:joinarenaqueue"))
                return;
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "You can't use commands in a free-for-all. Leave with /leavearena (out of combat).");
            return;
        }

        event.setCancelled(true);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cYou can't execute commands while in the arena!"));
    }

}
