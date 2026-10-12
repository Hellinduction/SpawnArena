package me.zeronull.spawnarena.commands.impl;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.LeaderboardHolograms;
import me.zeronull.spawnarena.SpawnArena;
import me.zeronull.spawnarena.StatsManager;
import org.bukkit.Bukkit;
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
 * /arenaleaderboard [arena] [wins|streak|best_streak] [limit]       — show in chat
 * /arenaleaderboard spawn <arena> <wins|streak|best_streak> [limit] — spawn a hologram where you stand
 * /arenaleaderboard spawn <arena> reward — per-player live countdown to their next win reward
 * /arenaleaderboard remove <hologram>
 * /arenaleaderboard list
 * /arenaleaderboard reload
 * /arenaleaderboard update — rebuild existing holograms with the current layout, in place
 */
public final class ArenaLeaderboardCommand implements CommandExecutor, TabCompleter {

    private static final List<String> STATS = List.of("wins", "streak", "best_streak");
    private static final String SPAWN_PERMISSION = "spawnarena.arenaleaderboard.spawn";
    public static final int MAX_LIMIT = 25;

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, String[] args) {
        if (args.length >= 1) {
            switch (args[0].toLowerCase()) {
                case "spawn" -> { return spawn(sender, args); }
                case "remove" -> { return remove(sender, args); }
                case "list" -> { return list(sender); }
                case "reload" -> { return reload(sender); }
                case "update" -> { return update(sender); }
            }
        }
        return show(sender, args);
    }

    private boolean show(CommandSender sender, String[] args) {
        final String arena = args.length >= 1 ? args[0] : "arena";
        final String stat = args.length >= 2 ? args[1].toLowerCase() : "wins";
        final int limit = args.length >= 3 ? parseLimit(args[2], 10) : 10;

        final String field = statToField(stat);
        if (field == null) {
            sender.sendMessage("§cUnknown stat. Use: wins, streak, best_streak");
            return true;
        }

        final List<StatsManager.LeaderboardEntry> entries = SpawnArena.statsManager.getTopPlayers(arena, field, limit);
        if (entries.isEmpty()) {
            sender.sendMessage("§7No stats found for arena §e" + arena + "§7.");
            return true;
        }

        sender.sendMessage("§6§l--- " + arena + " Leaderboard (" + statLabel(stat) + ") ---");
        for (int i = 0; i < entries.size(); i++) {
            final StatsManager.LeaderboardEntry e = entries.get(i);
            sender.sendMessage(String.format("§e#%d §f%s §7— §e%d", i + 1, e.name(), e.value()));
        }
        return true;
    }

    private boolean spawn(CommandSender sender, String[] args) {
        if (!canManageHolograms(sender)) return true;
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cYou must be a player to spawn a leaderboard.");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /arenaleaderboard spawn <arena> <wins|streak|best_streak> [limit]");
            return true;
        }

        final String arena = args[1];
        final String stat = args[2].toLowerCase();

        if (stat.equals("reward")) {
            final LeaderboardHolograms.Placement placement =
                    LeaderboardHolograms.correctPlacement(p.getLocation(), LeaderboardHolograms.REWARD_LINES);
            final String name = LeaderboardHolograms.spawnReward(arena, placement.location());
            sender.sendMessage(String.format("§aCreated reward countdown hologram for §e%s§a named §e%s§a.", arena, name));
            if (SpawnArena.arenas.of(arena) == null)
                sender.sendMessage("§eNote: there's no arena named §6" + arena + "§e (names are case-sensitive), so it will show blanks.");
            return true;
        }

        if (statToField(stat) == null) {
            sender.sendMessage("§cUnknown type. Use: wins, streak, best_streak, reward");
            return true;
        }
        final int limit = args.length >= 4 ? parseLimit(args[3], 10) : 10;

        final LeaderboardHolograms.Placement placement = LeaderboardHolograms.correctPlacement(p.getLocation(), limit + 1);
        final String name = LeaderboardHolograms.spawn(arena, stat, limit, placement.location());
        sender.sendMessage(String.format("§aCreated %s leaderboard hologram for §e%s§a named §e%s§a.", statLabel(stat), arena, name));

        final double shift = placement.location().getY() - p.getLocation().getY();
        if (Math.abs(shift) > 0.01)
            sender.sendMessage(String.format("§7Moved %s by %.2f blocks to avoid clipping into the %s.",
                    shift > 0 ? "up" : "down", Math.abs(shift), shift > 0 ? "ground" : "ceiling"));
        if (!placement.fits())
            sender.sendMessage("§eNot enough headroom for " + (limit + 1) + " lines here — the top will clip. Use a smaller limit or more open spot.");
        return true;
    }

    private boolean remove(CommandSender sender, String[] args) {
        if (!canManageHolograms(sender)) return true;
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /arenaleaderboard remove <hologram>");
            return true;
        }
        if (LeaderboardHolograms.remove(args[1]))
            sender.sendMessage("§aRemoved leaderboard hologram §e" + args[1] + "§a.");
        else
            sender.sendMessage("§cNo hologram named §e" + args[1] + "§c.");
        return true;
    }

    private boolean list(CommandSender sender) {
        if (!canManageHolograms(sender)) return true;
        final List<String> names = LeaderboardHolograms.list();
        sender.sendMessage(names.isEmpty() ? "§7No leaderboard holograms." : "§6Leaderboard holograms: §e" + String.join("§7, §e", names));
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission(SPAWN_PERMISSION)) {
            sender.sendMessage("§cYou don't have permission!");
            return true;
        }

        SpawnArena.statsManager.invalidateLeaderboards();

        if (Bukkit.getPluginManager().isPluginEnabled("FancyHolograms")) {
            final int count = LeaderboardHolograms.refreshAll();
            sender.sendMessage("§aReloaded leaderboards and redrew §e" + me.zeronull.spawnarena.Grammar.count(count, "hologram") + "§a.");
        } else {
            sender.sendMessage("§aReloaded leaderboards.");
        }
        return true;
    }

    private boolean update(CommandSender sender) {
        if (!canManageHolograms(sender)) return true;

        final LeaderboardHolograms.UpdateResult result = LeaderboardHolograms.updateAll();
        sender.sendMessage("§aUpdated §e" + me.zeronull.spawnarena.Grammar.count(result.updated(), "hologram") + "§a to the current layout, in place.");
        if (!result.skipped().isEmpty())
            sender.sendMessage("§eSkipped (arena no longer exists): §6" + String.join("§e, §6", result.skipped()));
        return true;
    }

    private boolean canManageHolograms(CommandSender sender) {
        if (!sender.hasPermission(SPAWN_PERMISSION)) {
            sender.sendMessage("§cYou don't have permission!");
            return false;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("FancyHolograms")) {
            sender.sendMessage("§cFancyHolograms is not installed.");
            return false;
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String label, String[] args) {
        final boolean manage = sender.hasPermission(SPAWN_PERMISSION);
        final List<String> options = new ArrayList<>();

        if (args.length == 1) {
            if (manage) options.addAll(List.of("spawn", "remove", "list", "reload", "update"));
            options.addAll(arenaNames());
        } else if (args[0].equalsIgnoreCase("spawn") && manage) {
            if (args.length == 2) options.addAll(arenaNames());
            else if (args.length == 3) { options.addAll(STATS); options.add("reward"); }
        } else if (args[0].equalsIgnoreCase("remove") && manage) {
            if (args.length == 2 && Bukkit.getPluginManager().isPluginEnabled("FancyHolograms"))
                options.addAll(LeaderboardHolograms.list());
        } else if (args.length == 2) {
            options.addAll(STATS);
        }

        if (options.isEmpty()) return Collections.emptyList();
        final String prefix = args[args.length - 1].toLowerCase();
        return options.stream().filter(s -> s.toLowerCase().startsWith(prefix)).toList();
    }

    private static List<String> arenaNames() {
        final List<String> names = new ArrayList<>();
        if (SpawnArena.arenas != null)
            for (final Arena a : SpawnArena.arenas) names.add(a.getArenaName());
        return names;
    }

    private static int parseLimit(String raw, int def) {
        try { return Math.min(Math.max(Integer.parseInt(raw), 1), MAX_LIMIT); }
        catch (NumberFormatException e) { return def; }
    }

    private static String statLabel(String stat) {
        return switch (stat) {
            case "streak" -> "Win Streak";
            case "best_streak" -> "Best Win Streak";
            default -> "Wins";
        };
    }

    private static String statToField(String stat) {
        return switch (stat) {
            case "wins" -> "wins";
            case "streak" -> "win_streak";
            case "best_streak" -> "best_win_streak";
            default -> null;
        };
    }
}
