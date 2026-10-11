package me.zeronull.spawnarena.commands.impl;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.SavableArena;
import me.zeronull.spawnarena.SpawnArena;
import me.zeronull.spawnarena.commands.ArenaTabComplete;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /setarenaexit <arena> — where non-fighters are sent when they're in the arena during a fight. */
public final class SetArenaExitCommand extends ArenaTabComplete implements CommandExecutor {
    public SetArenaExitCommand() {
        super(0);
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("You must be a player to run this command!");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /setarenaexit <arena>");
            return true;
        }

        final Arena found = SpawnArena.arenas.of(args[0]);
        if (!(found instanceof SavableArena arena)) {
            sender.sendMessage(ChatColor.RED + "That arena does not exist!");
            return true;
        }

        if (arena.equals(SpawnArena.arenas.of(player.getLocation()))) {
            sender.sendMessage(ChatColor.RED + "Stand outside the arena's region — players sent here would just be sent out again.");
            return true;
        }

        arena.setExitLocation(player.getLocation());
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                String.format("&aSet the exit location of the arena '&6%s&a' to where you're standing.", arena.getArenaName())));
        return true;
    }
}
