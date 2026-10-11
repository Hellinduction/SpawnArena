package me.zeronull.spawnarena.commands.impl;

import me.zeronull.spawnarena.SpawnArena;
import me.zeronull.spawnarena.StatsManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * /arenastats get <player> <arena>
 * /arenastats set <player> <arena> wins|streak|best_streak <value>
 * /arenastats reset <player> <arena>
 * /arenastats resetcooldown <player> <arena|all>
 */
public final class ArenaStatsCommand implements CommandExecutor, TabCompleter {

    private static final List<String> STATS = List.of("wins", "streak", "best_streak");
    private static final List<String> SUBCOMMANDS = List.of("get", "set", "reset", "resetcooldown", "migrate");

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("migrate"))
            return migrate(sender, args);

        if (args.length < 3) {
            sender.sendMessage("§cUsage: /arenastats <get|set|reset> <player> <arena> [stat] [value]");
            sender.sendMessage("§c       /arenastats resetcooldown <player> <arena|all>  §7or  §c/arenastats migrate");
            return true;
        }

        final String sub = args[0].toLowerCase();
        @SuppressWarnings("deprecation")
        final OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        final UUID uuid = target.getUniqueId();
        final String arena = args[2];

        switch (sub) {
            case "resetcooldown" -> {
                final boolean all = arena.equalsIgnoreCase("all");
                final int cleared = SpawnArena.statsManager.clearRewardCooldown(uuid, all ? null : arena);
                if (cleared < 0)
                    sender.sendMessage("§cCouldn't reset the cooldown — check the console.");
                else
                    sender.sendMessage(String.format("§aReset §e%s§a's win reward cooldown for §e%s§a. They can earn it on their next win.",
                            target.getName(), all ? "all arenas" : arena));
            }
            case "get" -> {
                final int wins = SpawnArena.statsManager.getWins(uuid, arena);
                final int streak = SpawnArena.statsManager.getWinStreak(uuid, arena);
                final int best = SpawnArena.statsManager.getBestWinStreak(uuid, arena);
                sender.sendMessage(String.format("§6Stats for §e%s §6in §e%s§6: wins=§e%d §6streak=§e%d §6best=§e%d",
                        target.getName(), arena, wins, streak, best));
            }
            case "set" -> {
                if (args.length < 5) {
                    sender.sendMessage("§cUsage: /arenastats set <player> <arena> wins|streak|best_streak <value>");
                    return true;
                }
                final String stat = args[3].toLowerCase();
                final String field = statToField(stat);
                if (field == null) {
                    sender.sendMessage("§cUnknown stat. Use: wins, streak, best_streak");
                    return true;
                }
                int value;
                try {
                    value = Integer.parseInt(args[4]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cValue must be an integer.");
                    return true;
                }
                SpawnArena.statsManager.setField(uuid, arena, field, value);
                sender.sendMessage(String.format("§aSet §e%s §afor §e%s §ain §e%s §ato §e%d",
                        stat, target.getName(), arena, value));
            }
            case "reset" -> {
                SpawnArena.statsManager.setField(uuid, arena, "wins", 0);
                SpawnArena.statsManager.setField(uuid, arena, "win_streak", 0);
                SpawnArena.statsManager.setField(uuid, arena, "best_win_streak", 0);
                sender.sendMessage(String.format("§aReset all stats for §e%s §ain §e%s", target.getName(), arena));
            }
            default -> sender.sendMessage("§cUnknown subcommand. Use: get, set, reset");
        }
        return true;
    }

    private boolean migrate(CommandSender sender, String[] args) {
        final StatsManager stats = SpawnArena.statsManager;

        if (!stats.isMySql()) {
            sender.sendMessage("§cSet §etype: MYSQL§c in plugins/SpawnArena/database.yml and restart before migrating.");
            return true;
        }
        if (!stats.h2DatabaseExists()) {
            sender.sendMessage("§cNo H2 database (plugins/SpawnArena/stats.mv.db) found to migrate from.");
            return true;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
            sender.sendMessage("§eThis copies all stats from the local H2 file into MySQL.");
            sender.sendMessage("§ePlayers that already have stats in MySQL will be §coverwritten§e by the H2 values.");
            sender.sendMessage("§eRun §6/arenastats migrate confirm§e to continue.");
            return true;
        }

        sender.sendMessage("§7Migrating stats from H2 to MySQL...");
        Bukkit.getAsyncScheduler().runNow(SpawnArena.INSTANCE, task -> {
            try {
                final int rows = stats.migrateH2ToMySql();
                sender.sendMessage("§aMigrated §e" + rows + "§a stat rows to MySQL. The H2 file was left in place as a backup.");
            } catch (Exception e) {
                SpawnArena.INSTANCE.getLogger().log(java.util.logging.Level.SEVERE, "H2 -> MySQL migration failed", e);
                sender.sendMessage("§cMigration failed (nothing was committed): " + e.getMessage());
            }
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String label, String[] args) {
        if (args.length == 1) return filter(SUBCOMMANDS, args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("migrate")) return filter(List.of("confirm"), args[1]);
        if (args.length == 4 && args[0].equalsIgnoreCase("set")) return filter(STATS, args[3]);
        if (args.length == 3 && !args[0].equalsIgnoreCase("migrate")) {
            final List<String> arenas = new java.util.ArrayList<>();
            if (SpawnArena.arenas != null)
                SpawnArena.arenas.forEach(a -> arenas.add(a.getArenaName()));
            if (args[0].equalsIgnoreCase("resetcooldown"))
                arenas.add("all");
            return arenas.stream().filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase())).toList();
        }
        if (args.length == 2) return null; // online player names
        return Collections.emptyList();
    }

    private List<String> filter(List<String> source, String prefix) {
        return source.stream().filter(s -> s.startsWith(prefix.toLowerCase())).toList();
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
