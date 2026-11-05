package me.zeronull.spawnarena.commands.impl;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.BotDifficulty;
import me.zeronull.spawnarena.BotFight;
import me.zeronull.spawnarena.BotShow;
import me.zeronull.spawnarena.SpawnArena;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * /arenabotfight <level> <level> [arena] — two sumo bots fight each other, to watch (see {@link BotShow}).
 * /arenabotfight stop [arena] — ends it. With no arena, the nearest sumo arena.
 */
public final class ArenaBotFightCommand implements CommandExecutor, TabCompleter {

    private static final String USAGE = "§cUsage: /arenabotfight <level> <level> [arena]  §7or  §c/arenabotfight stop [arena]"
            + "  §7(levels: easy, medium, hard, chair, combo)";
    /** Short names for the levels, as well as their own */
    private static final Map<String, BotDifficulty> ALIASES = Map.of(
            "chair", BotDifficulty.CHEATER, "sgc", BotDifficulty.CHEATER, "superior", BotDifficulty.CHEATER);

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("stop")) {
            final Arena arena = args.length >= 2 ? SpawnArena.arenas.of(args[1]) : null;
            if (args.length >= 2 && arena == null) {
                sender.sendMessage("§cThat arena was not found.");
                return true;
            }
            final int n = BotShow.stop(arena);
            sender.sendMessage(n == 0 ? "§eNo bot fight is on there." : "§aStopped " + me.zeronull.spawnarena.Grammar.count(n, "bot fight") + ".");
            return true;
        }
        if (args.length < 2 || args.length > 3) {
            sender.sendMessage(USAGE);
            return true;
        }
        final BotDifficulty one = level(args[0]), two = level(args[1]);
        if (one == null || two == null) {
            sender.sendMessage("§cUnknown level: " + (one == null ? args[0] : args[1]) + ".");
            sender.sendMessage(USAGE);
            return true;
        }
        final Arena arena = args.length == 3 ? SpawnArena.arenas.of(args[2]) : nearest(sender);
        if (arena == null) {
            sender.sendMessage(args.length == 3 ? "§cThat arena was not found." : "§cNo sumo arena to use: give one, /arenabotfight <level> <level> <arena>.");
            return true;
        }
        final String problem = BotShow.start(arena, one, two);
        if (problem != null)
            sender.sendMessage("§c" + problem);
        else if (!(sender instanceof Player p) || p.getWorld() != BotShow.worldOf(arena) || arena.distanceFrom(p.getLocation()) > 64)
            sender.sendMessage("§aBot fight started in " + arena.getArenaName() + ".");
        return true;
    }

    private static BotDifficulty level(final String name) {
        final BotDifficulty alias = ALIASES.get(name.toLowerCase());
        return alias != null ? alias : BotDifficulty.of(name);
    }

    /** The sumo arena bots can fight in nearest the sender (any, from the console). */
    private static Arena nearest(final CommandSender sender) {
        Arena best = null;
        double bestDistance = Double.MAX_VALUE;
        for (final Arena arena : SpawnArena.arenas.toList()) {
            if (!BotFight.available(arena))
                continue;
            double d = 0;
            if (sender instanceof Player p)
                d = BotShow.worldOf(arena) != p.getWorld() ? Double.MAX_VALUE / 2 : arena.distanceFrom(p.getLocation());
            if (best == null || d < bestDistance) {
                best = arena;
                bestDistance = d;
            }
        }
        return best;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String[] args) {
        final List<String> options = new ArrayList<>();
        if (args.length == 1)
            options.add("stop");
        if (args.length <= 2 && !(args.length == 2 && args[0].equalsIgnoreCase("stop")))
            options.addAll(List.of("easy", "medium", "hard", "chair", "combo"));
        else if (args.length == 3 || (args.length == 2 && args[0].equalsIgnoreCase("stop")))
            SpawnArena.arenas.toList().stream().filter(BotFight::available).forEach(a -> options.add(a.getArenaName()));
        final String typed = args[args.length - 1].toLowerCase();
        return options.stream().filter(s -> s.toLowerCase().startsWith(typed)).toList();
    }
}
