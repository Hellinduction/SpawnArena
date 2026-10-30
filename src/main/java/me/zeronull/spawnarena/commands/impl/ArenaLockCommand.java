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
import java.util.List;

/**
 * /arenalock [arena] [on|off] — no on/off flips the current state; no arena means all of them.
 * Locking empties the queues and cancels fights still counting down; running fights finish.
 */
public final class ArenaLockCommand implements CommandExecutor, TabCompleter {

    private static final String USAGE = "§cUsage: /arenalock [arena] [on|off]";

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, String[] args) {
        if (args.length > 2) {
            sender.sendMessage(USAGE);
            return true;
        }
        final boolean all = args.length == 0 || isOnOff(args[0]);
        final Arena only = all ? null : SpawnArena.arenas.of(args[0]);
        if (!all && only == null) {
            sender.sendMessage("§cThat arena was not found.");
            sender.sendMessage(USAGE);
            return true;
        }
        final String state = all ? (args.length == 0 ? null : args[0]) : (args.length == 2 ? args[1] : null);
        if (state != null && !isOnOff(state) || all && args.length == 2) {
            sender.sendMessage(USAGE);
            return true;
        }

        final boolean lock = state != null ? state.equalsIgnoreCase("on") : all ? SpawnArena.settings.arenasOpen : !only.isLocked();
        if (all)
            SpawnArena.settings.arenasOpen = !lock;
        else
            setLocked(only, lock);
        ConfigHandler.getInstance().saveSettingsConfig(SpawnArena.settings);

        final String what = all ? "Arenas" : Arena.displayName(only.getArenaName()) + " arena";
        if (!lock) {
            sender.sendMessage("§a" + what + (all ? " are" : " is") + " open again.");
            if (all && !SpawnArena.settings.lockedArenas.isEmpty())
                sender.sendMessage("§6Still closed on their own: §e" + String.join(", ", SpawnArena.settings.lockedArenas));
            if (!all && !SpawnArena.settings.arenasOpen)
                sender.sendMessage("§6All arenas are still closed. Open them with /arenalock off.");
            return true;
        }

        final String closed = all ? "The arenas have" : "The " + what + " has";
        int dequeued = 0;
        int cancelled = 0;
        int running = 0;
        int ffaSentOut = 0;

        for (final Arena arena : SpawnArena.arenas) {
            if (!all && !arena.getArenaName().equals(only.getArenaName()))
                continue;
            for (final Player p : arena.queue.clear()) {
                p.sendMessage(ChatColor.RED + closed + " been closed, so you were removed from the queue.");
                dequeued++;
            }

            for (final Fight fight : new ArrayList<>(arena.allFights())) {
                if (fight instanceof me.zeronull.spawnarena.FfaFight ffa) {
                    ffaSentOut += ffa.closeAll(closed + " been closed, so you were sent out of the free-for-all with your things.");
                    continue;
                }
                final List<Player> members = fight.getFighters();
                if (fight.cancelFight()) { // only fights still counting down
                    for (final Player p : members)
                        if (p != null && p.isOnline())
                            p.sendMessage(ChatColor.RED + closed + " been closed, so your fight was cancelled.");
                    cancelled++;
                } else if (fight.getState() != FightState.OVER) {
                    running++;
                }
            }
        }

        sender.sendMessage("§e" + what + " closed. Removed §6" + me.zeronull.spawnarena.Grammar.count(dequeued, "player")
                + "§e from queues, cancelled §6" + me.zeronull.spawnarena.Grammar.count(cancelled, "countdown") + "§e"
                + (ffaSentOut > 0 ? ", sent §6" + me.zeronull.spawnarena.Grammar.count(ffaSentOut, "player") + "§e out of free-for-alls" : "") + ".");
        sender.sendMessage(running == 0
                ? (all ? "§aNo fights are running — safe to reload." : "§aNo fights are running there.")
                : "§6" + me.zeronull.spawnarena.Grammar.count(running, "fight") + "§e still running; "
                        + (running == 1 ? "it'll" : "they'll") + " finish normally.");
        return true;
    }

    private static boolean isOnOff(final String arg) {
        return arg.equalsIgnoreCase("on") || arg.equalsIgnoreCase("off");
    }

    /** The list is read from other threads, so it's replaced rather than changed. */
    private static void setLocked(final Arena arena, final boolean lock) {
        final List<String> locked = new ArrayList<>(SpawnArena.settings.lockedArenas);
        locked.remove(arena.getArenaName());
        if (lock)
            locked.add(arena.getArenaName());
        SpawnArena.settings.lockedArenas = locked;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String label, String[] args) {
        final List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(List.of("on", "off"));
            SpawnArena.arenas.toList().forEach(arena -> options.add(arena.getArenaName()));
        } else if (args.length == 2 && !isOnOff(args[0])) {
            options.addAll(List.of("on", "off"));
        }
        return options.stream().filter(s -> s.toLowerCase().startsWith(args[args.length - 1].toLowerCase())).toList();
    }
}
