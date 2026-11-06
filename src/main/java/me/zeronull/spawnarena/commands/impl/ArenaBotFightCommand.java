package me.zeronull.spawnarena.commands.impl;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.BotDifficulty;
import me.zeronull.spawnarena.BotFight;
import me.zeronull.spawnarena.BotShow;
import me.zeronull.spawnarena.MixedBotFight;
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
 * Like /setupfight, with bots: /arenabotfight [arena] <a> <b> for a 1v1, or [arena] <a> <b> <c> <d> for a 2v2 (a and b
 * against c and d); each is a bot level or an online player (a level's name counts as the level). With no arena, the
 * nearest sumo arena. Bots only: a show to watch ({@link BotShow}); with players in it, a match ({@link MixedBotFight}).
 * /arenabotfight stop [arena] ends a show.
 */
public final class ArenaBotFightCommand implements CommandExecutor, TabCompleter {

    private static final String USAGE = "§cUsage: /arenabotfight [arena] <a> <b>  §7or (2v2, a and b vs c and d)  §c/arenabotfight [arena] <a> <b> <c> <d>"
            + "  §7or  §c/arenabotfight stop [arena]  §7(each a bot: easy, medium, hard, master, chair, combo; or an online player)";
    private static final List<String> LEVELS = List.of("easy", "medium", "hard", "master", "chair", "combo");
    /** Short names for the levels, as well as their own */
    private static final Map<String, BotDifficulty> ALIASES = Map.of(
            "chair", BotDifficulty.CHEATER, "sgc", BotDifficulty.CHEATER, "superior", BotDifficulty.CHEATER, "god", BotDifficulty.MASTER);

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
        final boolean arenaGiven = level(args[0]) == null && SpawnArena.arenas.of(args[0]) != null;
        if (!arenaGiven && level(args[0]) == null && player(args[0]) == null) {
            sender.sendMessage("§c" + args[0] + " isn't an arena, a bot level or an online player.");
            return true;
        }
        final Arena arena = arenaGiven ? SpawnArena.arenas.of(args[0]) : nearest(sender);
        if (arena == null) {
            sender.sendMessage("§cNo sumo arena to use: give one, /arenabotfight <arena> <a> <b>.");
            return true;
        }
        // Each one a bot level or an online player (null: a player, in players at the same place)
        final List<BotDifficulty> levels = new ArrayList<>();
        final List<Player> players = new ArrayList<>();
        for (int i = arenaGiven ? 1 : 0; i < args.length; i++) {
            final BotDifficulty level = level(args[i]);
            final Player player = level == null ? player(args[i]) : null;
            if (level == null && player == null) {
                sender.sendMessage("§c" + args[i] + " isn't a bot level or an online player.");
                sender.sendMessage(USAGE);
                return true;
            }
            levels.add(level);
            players.add(player);
        }
        if (levels.size() != 2 && levels.size() != 4) {
            sender.sendMessage("§cBot fights are 1v1 (2 in it) or 2v2 (4).");
            sender.sendMessage(USAGE);
            return true;
        }
        final String problem;
        if (players.stream().allMatch(java.util.Objects::isNull)) {
            problem = BotShow.start(arena, levels, sender); // bots only: a show (how it goes is told to the sender alone)
        } else {
            // With players: a match, the first half against the second
            final int half = levels.size() / 2;
            final List<Player> playersA = new ArrayList<>(), playersB = new ArrayList<>();
            final List<BotDifficulty> botsA = new ArrayList<>(), botsB = new ArrayList<>();
            for (int i = 0; i < levels.size(); i++) {
                if (players.get(i) != null)
                    (i < half ? playersA : playersB).add(players.get(i));
                else
                    (i < half ? botsA : botsB).add(levels.get(i));
            }
            problem = MixedBotFight.start(arena, playersA, botsA, playersB, botsB, sender);
        }
        if (problem != null)
            sender.sendMessage("§c" + problem);
        return true;
    }

    private static Player player(final String name) {
        return org.bukkit.Bukkit.getPlayerExact(name);
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
            org.bukkit.Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
        } else if (args[0].equalsIgnoreCase("stop")) {
            if (args.length == 2)
                SpawnArena.arenas.toList().stream().filter(BotFight::available).forEach(a -> options.add(a.getArenaName()));
        } else if (args.length <= 5) {
            options.addAll(LEVELS);
            org.bukkit.Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
        }
        final String typed = args[args.length - 1].toLowerCase();
        return options.stream().filter(s -> s.toLowerCase().startsWith(typed)).toList();
    }
}
