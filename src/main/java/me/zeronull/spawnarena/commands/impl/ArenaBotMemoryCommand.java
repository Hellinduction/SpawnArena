package me.zeronull.spawnarena.commands.impl;

import me.zeronull.spawnarena.SumoBot;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** /arenabotmemory &lt;player&gt;: what sumo bots (Master) have learnt of an online player. */
public final class ArenaBotMemoryCommand implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String[] args) {
        if (args.length != 1) {
            sender.sendMessage("§cUsage: /arenabotmemory <player>");
            return true;
        }
        final Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage("§c" + args[0] + " isn't online (what bots remember is kept with them, so they need to be on).");
            return true;
        }
        SumoBot.describeMemory(target, sender);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String[] args) {
        if (args.length != 1)
            return List.of();
        final String typed = args[0].toLowerCase();
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase().startsWith(typed)).toList();
    }
}
