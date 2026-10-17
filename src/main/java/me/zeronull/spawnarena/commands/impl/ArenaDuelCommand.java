package me.zeronull.spawnarena.commands.impl;

import me.zeronull.spawnarena.DuelManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

/**
 * /arenaduel accept <player> | deny <player> | toggle
 * Requests are sent by shift-right-clicking a player near an arena.
 */
public final class ArenaDuelCommand implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("You must be a player to run this command!");
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("toggle")) {
            final boolean on = DuelManager.toggleRequests(player);
            player.sendMessage(on ? "§aYou'll now receive duel requests." : "§eYou won't receive duel requests anymore.");
            return true;
        }

        if (args.length < 2 || !(args[0].equalsIgnoreCase("accept") || args[0].equalsIgnoreCase("deny") || args[0].equalsIgnoreCase("cancel"))) {
            player.sendMessage("§cUsage: /arenaduel accept|deny|cancel <player>  §7or  §c/arenaduel toggle");
            player.sendMessage("§7Shift-right-click a player near an arena to challenge them.");
            return true;
        }

        final Player requester = Bukkit.getPlayerExact(args[1]);
        if (requester == null) {
            player.sendMessage("§c" + args[1] + " isn't online.");
            return true;
        }

        if (args[0].equalsIgnoreCase("cancel"))
            DuelManager.cancel(player, requester); // here "requester" is who they challenged
        else if (args[0].equalsIgnoreCase("accept"))
            DuelManager.accept(player, requester);
        else
            DuelManager.deny(player, requester);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String label, String[] args) {
        if (args.length == 1)
            return List.of("accept", "deny", "cancel", "toggle").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        if (args.length == 2 && !args[0].equalsIgnoreCase("toggle"))
            return null; // online player names
        return Collections.emptyList();
    }
}
