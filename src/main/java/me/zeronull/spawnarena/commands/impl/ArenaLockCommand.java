package me.zeronull.spawnarena.commands.impl;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.Fight;
import me.zeronull.spawnarena.FightState;
import me.zeronull.spawnarena.SpawnArena;
import me.zeronull.spawnarena.config.ConfigHandler;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * /arenalock [on|off] — no argument flips the current state.
 * Locking empties every queue and cancels fights still counting down; running fights finish.
 */
public final class ArenaLockCommand implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, String[] args) {
        final boolean lock;
        if (args.length == 0) {
            lock = SpawnArena.settings.arenasOpen;
        } else if (args[0].equalsIgnoreCase("on")) {
            lock = true;
        } else if (args[0].equalsIgnoreCase("off")) {
            lock = false;
        } else {
            sender.sendMessage("§cUsage: /arenalock [on|off]");
            return true;
        }

        SpawnArena.settings.arenasOpen = !lock;
        ConfigHandler.getInstance().saveSettingsConfig(SpawnArena.settings);

        if (!lock) {
            sender.sendMessage("§aArenas are open again.");
            return true;
        }

        int dequeued = 0;
        int cancelled = 0;
        int running = 0;
        int ffaSentOut = 0;

        for (final Arena arena : SpawnArena.arenas) {
            for (final Player p : arena.queue.clear()) {
                p.sendMessage(ChatColor.RED + "The arenas have been closed, so you were removed from the queue.");
                dequeued++;
            }

            for (final Fight fight : new ArrayList<>(arena.getFights())) {
                if (fight instanceof me.zeronull.spawnarena.FfaFight ffa) {
                    ffaSentOut += ffa.closeAll("The arenas have been closed, so you were sent out of the free-for-all with your things.");
                    continue;
                }
                final List<Player> members = fight.getFighters();
                if (fight.cancelFight()) { // only fights still counting down
                    for (final Player p : members)
                        if (p != null && p.isOnline())
                            p.sendMessage(ChatColor.RED + "The arenas have been closed, so your fight was cancelled.");
                    cancelled++;
                } else if (fight.getState() != FightState.OVER) {
                    running++;
                }
            }
        }

        sender.sendMessage("§eArenas closed. Removed §6" + me.zeronull.spawnarena.Grammar.count(dequeued, "player")
                + "§e from queues, cancelled §6" + me.zeronull.spawnarena.Grammar.count(cancelled, "countdown") + "§e"
                + (ffaSentOut > 0 ? ", sent §6" + me.zeronull.spawnarena.Grammar.count(ffaSentOut, "player") + "§e out of free-for-alls" : "") + ".");
        sender.sendMessage(running == 0
                ? "§aNo fights are running — safe to reload."
                : "§6" + me.zeronull.spawnarena.Grammar.count(running, "fight") + "§e still running; "
                        + (running == 1 ? "it'll" : "they'll") + " finish normally.");
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
