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
        // Messaging and the like (fightAllowedCommands) are always fine
        if (isAllowed(label))
            return;
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

    /**
     * Whether this command is in fightAllowedCommands: by what was typed (without a "plugin:" prefix), or by the command
     * it runs, its name or any of its aliases (so /etell is allowed if it's EssentialsX's msg).
     */
    private static boolean isAllowed(final String label) {
        final java.util.List<String> allowed = SpawnArena.settings == null ? java.util.List.of() : SpawnArena.settings.fightAllowedCommands;
        if (allowed.isEmpty())
            return false;
        final java.util.Set<String> names = new java.util.HashSet<>();
        for (final String name : allowed)
            names.add(name.toLowerCase(java.util.Locale.ROOT).replaceFirst("^/", ""));
        final String typed = label.contains(":") ? label.substring(label.indexOf(':') + 1) : label;
        if (names.contains(typed))
            return true;
        final org.bukkit.command.Command command = org.bukkit.Bukkit.getCommandMap().getCommand(label);
        if (command == null)
            return false;
        if (names.contains(command.getName().toLowerCase(java.util.Locale.ROOT)))
            return true;
        for (final String alias : command.getAliases())
            if (names.contains(alias.toLowerCase(java.util.Locale.ROOT)))
                return true;
        return false;
    }

}
