package me.zeronull.spawnarena.commands.impl;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.SavableArena;
import me.zeronull.spawnarena.SpawnArena;
import me.zeronull.spawnarena.config.ConfigHandler;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;

/** /arenareload — re-reads settings.yml and every arena's file. database.yml needs a restart. */
public final class ArenaReloadCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, String[] args) {
        try {
            SpawnArena.settings = ConfigHandler.getInstance().getSettingsConfig();
            Arena.SPAWN_WORLD = SpawnArena.settings.spawnWorld;
        } catch (final Exception e) {
            SpawnArena.INSTANCE.getLogger().log(Level.SEVERE, "Failed to reload settings.yml", e);
            sender.sendMessage("§csettings.yml has an error — kept the old settings. Check the console.");
            return true;
        }

        int reloaded = 0;
        int failed = 0;
        for (final Arena arena : SpawnArena.arenas) {
            if (!(arena instanceof SavableArena savable))
                continue;
            try {
                savable.reloadFromDisk();
                reloaded++;
            } catch (final Exception e) {
                failed++;
                SpawnArena.INSTANCE.getLogger().log(Level.SEVERE, "Failed to reload arena " + arena.getArenaName(), e);
            }
        }

        SpawnArena.statsManager.invalidateLeaderboards();

        sender.sendMessage("§aReloaded settings.yml and " + me.zeronull.spawnarena.Grammar.count(reloaded, "arena") + ".");
        if (failed > 0)
            sender.sendMessage("§c" + me.zeronull.spawnarena.Grammar.count(failed, "arena file")
                    + " had errors and kept the old values. Check the console.");
        sender.sendMessage("§7database.yml changes and newly added/removed arena files still need a restart.");
        return true;
    }
}
