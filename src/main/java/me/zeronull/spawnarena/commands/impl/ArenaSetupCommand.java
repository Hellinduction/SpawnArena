package me.zeronull.spawnarena.commands.impl;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.ArenaWizard;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** /arenasetup <name> — the step-by-step arena setup wizard (see ArenaWizard). */
public final class ArenaSetupCommand implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("You must be a player to run this command!");
            return true;
        }
        if (!Arena.ArenaUtils.WORLD_EDIT_SUPPORT) {
            sender.sendMessage(ChatColor.RED + "The arena setup wizard needs WorldEdit (or FAWE) and WorldGuard.");
            return true;
        }
        ArenaWizard.handle(player, args);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String[] args) {
        return sender instanceof Player p ? ArenaWizard.tabComplete(p, args) : List.of();
    }
}
