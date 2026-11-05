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
 * Like /setupfight, with bots: /arenabotfight [arena] <bot1> <bot2> for a 1v1, or [arena] <bot1> <bot2> <bot3> <bot4>
 * for a 2v2 (1 and 2 against 3 and 4); each bot is a level. With no arena, the nearest sumo arena.
 * /arenabotfight stop [arena] ends it. See {@link BotShow}.
 */
public final class ArenaBotFightCommand implements CommandExecutor, TabCompleter {

    private static final String USAGE = "§cUsage: /arenabotfight [arena] <bot1> <bot2>  §7or (2v2)  §c/arenabotfight [arena] <bot1> <bot2> <bot3> <bot4>"
            + "  §7or  §c/arenabotfight stop [arena]  §7(bots: easy, medium, hard, chair, combo)";
    private static final List<String> LEVELS = List.of("easy", "medium", "hard", "chair", "combo");
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
        if (args.length == 0) {
            sender.sendMessage(USAGE);
            return true;
        }
        // The arena first, as /setupfight has it (or none: the nearest)
        final boolean arenaGiven = level(args[0]) == null;
        final Arena arena = arenaGiven ? SpawnArena.arenas.of(args[0]) : nearest(sender);
        if (arena == null) {
            sender.sendMessage(arenaGiven ? "§c" + args[0] + " isn't an arena or a bot level." : "§cNo sumo arena to use: give one, /arenabotfight <arena> <bot1> <bot2>.");
            return true;
        }
        final List<BotDifficulty> levels = new ArrayList<>();
        for (int i = arenaGiven ? 1 : 0; i < args.length; i++) {
            final BotDifficulty level = level(args[i]);
            if (level == null) {
                sender.sendMessage("§cUnknown bot level: " + args[i] + ".");
                sender.sendMessage(USAGE);
                return true;
            }
            levels.add(level);
        }
        if (levels.size() != 2 && levels.size() != 4) {
            sender.sendMessage("§cBot fights are 1v1 (2 bots) or 2v2 (4 bots).");
            sender.sendMessage(USAGE);
            return true;
        }
        final String problem = BotShow.start(arena, levels);
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
        if (args.length == 1) {
            options.add("stop");
            SpawnArena.arenas.toList().stream().filter(BotFight::available).forEach(a -> options.add(a.getArenaName()));
            options.addAll(LEVELS);
        } else if (args[0].equalsIgnoreCase("stop")) {
            if (args.length == 2)
                SpawnArena.arenas.toList().stream().filter(BotFight::available).forEach(a -> options.add(a.getArenaName()));
        } else if (args.length <= 5) {
            options.addAll(LEVELS);
        }
        final String typed = args[args.length - 1].toLowerCase();
        return options.stream().filter(s -> s.toLowerCase().startsWith(typed)).toList();
    }
}
