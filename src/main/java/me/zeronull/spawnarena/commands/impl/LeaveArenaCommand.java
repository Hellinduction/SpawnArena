package me.zeronull.spawnarena.commands.impl;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.FfaFight;
import me.zeronull.spawnarena.SpawnArena;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

/** /leavearena — leave a free-for-all arena (out of combat only). Does nothing anywhere else. */
public final class LeaveArenaCommand implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(@NotNull final CommandSender sender, @NotNull final Command command, @NotNull final String label, final String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("You must be a player to run this command!");
            return true;
        }

        final FfaFight session = FfaFight.of(player);
        if (session != null) {
            session.tryLeave(player);
            return true;
        }

        if (FfaFight.isJoining(player))
            player.sendMessage(ChatColor.RED + "You're still on your way in. Wait a moment, then use /leavearena.");
        else if (SpawnArena.arenas.hasFighter(player))
            player.sendMessage(ChatColor.RED + "/leavearena only works in free-for-all arenas. In a match, you can't leave until it's over.");
        else
            player.sendMessage(ChatColor.RED + "You're not in a free-for-all arena.");
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull final CommandSender sender, @NotNull final Command command, @NotNull final String label, final String[] args) {
        return Collections.emptyList();
    }
}
