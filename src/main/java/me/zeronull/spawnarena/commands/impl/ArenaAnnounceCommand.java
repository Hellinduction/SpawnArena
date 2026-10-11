package me.zeronull.spawnarena.commands.impl;

import me.zeronull.spawnarena.SpawnArena;
import me.zeronull.spawnarena.config.ConfigHandler;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

/** /arenaannounce [on|off] — no argument flips the current setting. */
public final class ArenaAnnounceCommand implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, String[] args) {
        final boolean enable;
        if (args.length == 0) {
            enable = !SpawnArena.settings.broadcastWins;
        } else if (args[0].equalsIgnoreCase("on")) {
            enable = true;
        } else if (args[0].equalsIgnoreCase("off")) {
            enable = false;
        } else {
            sender.sendMessage("§cUsage: /arenaannounce [on|off]");
            return true;
        }

        SpawnArena.settings.broadcastWins = enable;
        ConfigHandler.getInstance().saveSettingsConfig(SpawnArena.settings);

        sender.sendMessage(enable
                ? "§aFight results will be broadcast to the whole server."
                : "§eFight results will only be shown to the winner and loser.");
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String label, String[] args) {
        if (args.length == 1)
            return List.of("on", "off").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        return Collections.emptyList();
    }
}
