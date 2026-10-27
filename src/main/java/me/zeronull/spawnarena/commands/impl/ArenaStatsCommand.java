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
 * /arenastats set <player> <arena> wins|streak|best_streak|elo <value>
 * /arenastats reset <player> <arena>
 * /arenastats resetcooldown <player> <arena|all>
 * /arenastats payouts <player> [page]
 * /arenastats log [player|console] [page]                     — staff/console stat changes, newest first
 * /arenastats rollback <staff|console> [30m|12h|7d|all] [confirm] — undo that staff member's stat changes
 */
public final class ArenaStatsCommand implements CommandExecutor, TabCompleter {

    private static final List<String> STATS = List.of("wins", "streak", "best_streak", "elo");
    private static final List<String> SUBCOMMANDS = List.of("get", "set", "reset", "resetcooldown", "payouts", "players", "log", "rollback", "migrate");
    private static final String ROLLBACK_PERMISSION = "spawnarena.arenastats.rollback";

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("migrate"))
            return migrate(sender, args);
        if (args.length >= 1 && args[0].equalsIgnoreCase("players"))
            return players(sender);
        if (args.length >= 1 && args[0].equalsIgnoreCase("log"))
            return log(sender, args);
        if (args.length >= 1 && args[0].equalsIgnoreCase("rollback"))
            return rollback(sender, args);

        final String sub = args.length >= 1 ? args[0].toLowerCase() : "";
        final boolean getAll = sub.equals("get") && args.length == 2;
        final boolean payouts = sub.equals("payouts") && args.length >= 2;
        if (args.length < 3 && !getAll && !payouts) {
            sender.sendMessage("§cUsage: /arenastats get <player> [arena]  §7or  §c/arenastats payouts <player> [page]");
            sender.sendMessage("§c       /arenastats <set|reset> <player> <arena> [stat] [value]");
            sender.sendMessage("§c       /arenastats resetcooldown <player> <arena|all>  §7or  §c/arenastats migrate");
            sender.sendMessage("§c       /arenastats log [player|console] [page]  §7or  §c/arenastats rollback <staff|console> [time]");
            sender.sendMessage("§c       /arenastats players");
            return true;
        }

        // Only players the server has actually seen: getOfflinePlayer(name) invents an ID for unknown
        // names, which silently shows 0 stats
        final OfflinePlayer target = resolvePlayer(args[1]);
        if (target == null) {
            sender.sendMessage("§cNo player named §e" + args[1] + "§c has played on this server.");
            return true;
        }
        final UUID uuid = target.getUniqueId();

        if (payouts) {
            int page = 1;
            if (args.length >= 3) {
                try { page = Math.max(1, Integer.parseInt(args[2])); } catch (NumberFormatException ignored) {}
            }
            showPayouts(sender, target, page);
            return true;
        }

        if (getAll) {
            sender.sendMessage("§6Stats for §e" + target.getName() + "§6:");
            for (final me.zeronull.spawnarena.Arena a : SpawnArena.arenas) {
                final String name = a.getArenaName();
                final int wins = SpawnArena.statsManager.getWins(uuid, name);
                final int losses = SpawnArena.statsManager.getLosses(uuid, name);
                sender.sendMessage(String.format("§7- §e%s§7: wins=§e%d §7losses=§e%d §7games=§e%d §7streak=§e%d §7best=§e%d §7elo=§e%d", name,
                        wins, losses, wins + losses,
                        SpawnArena.statsManager.getWinStreak(uuid, name),
                        SpawnArena.statsManager.getBestWinStreak(uuid, name),
                        SpawnArena.statsManager.getElo(uuid, name)));
            }
            sendLossesNote(sender);
            return true;
        }

        final String arena = args[2];
        final boolean allArenas = sub.equals("resetcooldown") && arena.equalsIgnoreCase("all");
        if (!allArenas && SpawnArena.arenas.of(arena) == null) {
            final List<String> names = new java.util.ArrayList<>();
            SpawnArena.arenas.forEach(a -> names.add(a.getArenaName()));
            sender.sendMessage("§cNo arena named §e" + arena + "§c. Arenas: §e" + String.join("§c, §e", names));
            return true;
        }

        switch (sub) {
            case "resetcooldown" -> {
                final boolean all = arena.equalsIgnoreCase("all");
                final int cleared = SpawnArena.statsManager.clearRewardCooldown(uuid, all ? null : arena);
                if (cleared >= 0)
                    audit(sender, target, all ? null : arena, null, null, null, "resetcooldown");
                if (cleared < 0)
                    sender.sendMessage("§cCouldn't reset the cooldown — check the console.");
                else
                    sender.sendMessage(String.format("§aReset §e%s§a win reward cooldown for §e%s§a. They can earn it on their next win.",
                            me.zeronull.spawnarena.Grammar.possessive(target.getName()), all ? "all arenas" : arena));
            }
            case "get" -> {
                final int wins = SpawnArena.statsManager.getWins(uuid, arena);
                final int streak = SpawnArena.statsManager.getWinStreak(uuid, arena);
                final int best = SpawnArena.statsManager.getBestWinStreak(uuid, arena);
                final int losses = SpawnArena.statsManager.getLosses(uuid, arena);
                sender.sendMessage(String.format("§6Stats for §e%s §6in §e%s§6: wins=§e%d §6losses=§e%d §6W/L=§e%s §6games=§e%d §6streak=§e%d §6best=§e%d §6elo=§e%d",
                        target.getName(), arena, wins, losses, me.zeronull.spawnarena.StatsManager.ratio(wins, losses), wins + losses, streak, best,
                        SpawnArena.statsManager.getElo(uuid, arena)));
                sendLossesNote(sender);
                sender.sendMessage("§6Time fighting here: §e" + me.zeronull.spawnarena.StatsManager.formatDuration(
                        SpawnArena.statsManager.getField(uuid, arena, "fight_seconds")));
                // Free-for-all, counted apart: shown for free-for-all arenas, or anywhere they've played one
                final int ffaKills = SpawnArena.statsManager.getField(uuid, arena, "ffa_kills");
                final int ffaDeaths = SpawnArena.statsManager.getField(uuid, arena, "ffa_deaths");
                final me.zeronull.spawnarena.Arena found = SpawnArena.arenas.of(arena);
                if (ffaKills > 0 || ffaDeaths > 0 || (found != null && found.isFfa()))
                    sender.sendMessage(String.format("§6Free-for-all: kills=§e%d §6deaths=§e%d §6best kill streak=§e%d §6K/D=§e%s",
                            ffaKills, ffaDeaths, SpawnArena.statsManager.getField(uuid, arena, "ffa_best_streak"),
                            me.zeronull.spawnarena.StatsManager.ratio(ffaKills, ffaDeaths)));
                final double[] made = SpawnArena.statsManager.getMoneyMade(uuid, arena);
                sender.sendMessage(String.format("§6Money made: §e%s §6here · §e%s §6in all arenas",
                        me.zeronull.spawnarena.StatsManager.formatMoney(made[0]), me.zeronull.spawnarena.StatsManager.formatMoney(made[1])));
                final me.zeronull.spawnarena.StatsManager.LostStreak lost = SpawnArena.statsManager.getLostStreak(uuid, arena);
                if (lost.streak() > 0) {
                    final int minutes = (int) ((System.currentTimeMillis() - lost.at()) / 60_000);
                    sender.sendMessage(String.format("§6Last lost streak: §e%d §7(%s)", lost.streak(),
                            minutes < 1 ? "just now" : me.zeronull.spawnarena.StatsManager.formatMinutes(minutes) + " ago"));
                }
            }
            case "set" -> {
                if (args.length < 5) {
                    sender.sendMessage("§cUsage: /arenastats set <player> <arena> wins|streak|best_streak|elo <value>");
                    return true;
                }
                final String stat = args[3].toLowerCase();
                final String field = statToField(stat);
                if (field == null) {
                    sender.sendMessage("§cUnknown stat. Use: wins, streak, best_streak, elo");
                    return true;
                }
                int value;
                try {
                    value = Integer.parseInt(args[4]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cValue must be an integer.");
                    return true;
                }
                if (value < 0) {
                    sender.sendMessage("§cValue can't be negative.");
                    return true;
                }
                final int old = field.equals("elo") ? SpawnArena.statsManager.getElo(uuid, arena)
                        : SpawnArena.statsManager.getField(uuid, arena, field);
                SpawnArena.statsManager.setField(uuid, arena, field, value);
                audit(sender, target, arena, field, old, value, "set");
                sender.sendMessage(String.format("§aSet §e%s §afor §e%s §ain §e%s §ato §e%d",
                        stat, target.getName(), arena, value));
                if (field.equals("elo"))
                    me.zeronull.spawnarena.TopEloSuffix.refreshLater(); // may change who's #1
            }
            case "reset" -> {
                // elo_games too: off the Elo board again until they've played enough rated games
                for (final String field : new String[]{"wins", "losses", "win_streak", "best_win_streak", "elo_games"}) {
                    final int old = SpawnArena.statsManager.getField(uuid, arena, field);
                    SpawnArena.statsManager.setField(uuid, arena, field, 0);
                    if (old != 0)
                        audit(sender, target, arena, field, old, 0, "reset");
                }
                final int oldElo = SpawnArena.statsManager.getElo(uuid, arena);
                SpawnArena.statsManager.setField(uuid, arena, "elo", me.zeronull.spawnarena.StatsManager.START_ELO);
                if (oldElo != me.zeronull.spawnarena.StatsManager.START_ELO)
                    audit(sender, target, arena, "elo", oldElo, me.zeronull.spawnarena.StatsManager.START_ELO, "reset");
                sender.sendMessage(String.format("§aReset all stats for §e%s §ain §e%s", target.getName(), arena));
                me.zeronull.spawnarena.TopEloSuffix.refreshLater();
            }
            default -> sender.sendMessage("§cUnknown subcommand. Use: get, set, reset, resetcooldown, payouts, log, rollback, players, migrate");
        }
        // The boards show the change now rather than on their next redraw
        if ((sub.equals("set") || sub.equals("reset")) && org.bukkit.Bukkit.getPluginManager().isPluginEnabled("FancyHolograms"))
            me.zeronull.spawnarena.LeaderboardHolograms.refreshAll();
        return true;
    }

    /** Losses (and so games) only count from when this version first ran, if there were stats before that. */
    private static void sendLossesNote(final CommandSender sender) {
        final long since = SpawnArena.statsManager.getLossesSince();
        if (since > 0)
            sender.sendMessage("§7(losses and games counted since " + new java.text.SimpleDateFormat("d MMM yyyy").format(new java.util.Date(since)) + ")");
    }

    // ── Audit log and rollback ──────────────────────────────────────────────

    private static void audit(final CommandSender sender, final OfflinePlayer target, final String arena, final String field,
                              final Integer oldValue, final Integer newValue, final String action) {
        final UUID actor = sender instanceof org.bukkit.entity.Player p ? p.getUniqueId() : null;
        SpawnArena.statsManager.logStatChange(actor, sender.getName(), target.getUniqueId(), target.getName(),
                arena, field, oldValue, newValue, action);
    }

    private static final int LOG_PER_PAGE = 10;

    private boolean log(final CommandSender sender, final String[] args) {
        UUID who = null;
        boolean console = false;
        String label = "everyone";
        int page = 1;
        int next = 1;
        if (args.length >= 2 && !isNumber(args[1])) {
            if (args[1].equalsIgnoreCase("console")) {
                console = true;
                label = "the console";
            } else {
                final OfflinePlayer p = resolvePlayer(args[1]);
                if (p == null) {
                    sender.sendMessage("§cNo player named §e" + args[1] + "§c has played on this server.");
                    return true;
                }
                who = p.getUniqueId();
                label = p.getName();
            }
            next = 2;
        }
        if (args.length > next && isNumber(args[next]))
            page = Math.max(1, Integer.parseInt(args[next]));

        final List<StatsManager.AuditEntry> entries = SpawnArena.statsManager.getAudit(who, console, page, LOG_PER_PAGE);
        sender.sendMessage("§6Stat changes " + (console ? "made by " : who == null ? "by " : "by or to ") + "§e" + label + "§6 (page " + page + "):");
        if (entries.isEmpty()) {
            sender.sendMessage("§7None.");
            return true;
        }
        for (final StatsManager.AuditEntry e : entries) {
            final String change = e.field() == null ? "" : " §7" + e.field() + " §e" + e.oldValue() + "§7→§e" + e.newValue();
            sender.sendMessage(String.format("§7%s §8· §f%s §7%s §f%s%s%s%s",
                    PAYOUT_TIME.format(java.time.Instant.ofEpochMilli(e.changedAt())), e.actorName(), e.action(),
                    nameOf(e.target()), e.arena() == null ? "" : " §7in §e" + e.arena(), change,
                    e.rolledBack() ? " §c(rolled back)" : ""));
        }
        if (entries.size() == LOG_PER_PAGE)
            sender.sendMessage("§7More: /arenastats log " + (args.length >= 2 && !isNumber(args[1]) ? args[1] + " " : "") + (page + 1));
        return true;
    }

    private boolean rollback(final CommandSender sender, final String[] args) {
        if (!sender.hasPermission(ROLLBACK_PERMISSION)) {
            sender.sendMessage("§cYou don't have permission!");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /arenastats rollback <staff|console> [30m|12h|7d|all] [confirm]");
            return true;
        }

        final UUID actor;
        final String actorLabel;
        if (args[1].equalsIgnoreCase("console")) {
            actor = null;
            actorLabel = "the console";
        } else {
            final OfflinePlayer p = resolvePlayer(args[1]);
            if (p == null) {
                sender.sendMessage("§cNo player named §e" + args[1] + "§c has played on this server.");
                return true;
            }
            actor = p.getUniqueId();
            actorLabel = p.getName();
        }

        final String window = args.length >= 3 && !args[2].equalsIgnoreCase("confirm") ? args[2] : "all";
        final long millis = parseDuration(window);
        if (millis < 0) {
            sender.sendMessage("§cTime must look like 30m, 12h, 7d or all.");
            return true;
        }
        final long since = millis == 0 ? 0 : System.currentTimeMillis() - millis;
        final boolean confirm = args[args.length - 1].equalsIgnoreCase("confirm");

        final List<StatsManager.AuditEntry> entries = SpawnArena.statsManager.getRollbackable(actor, since);
        final String when = millis == 0 ? "ever" : "in the last " + window;
        if (entries.isEmpty()) {
            sender.sendMessage("§7" + actorLabel + " hasn't changed any stats " + when + " (that aren't already rolled back).");
            return true;
        }

        if (!confirm) {
            final java.util.Set<UUID> players = new java.util.HashSet<>();
            entries.forEach(e -> players.add(e.target()));
            sender.sendMessage("§e" + actorLabel + " made §6" + me.zeronull.spawnarena.Grammar.count(entries.size(), "stat change")
                    + "§e to §6" + me.zeronull.spawnarena.Grammar.count(players.size(), "player") + "§e " + when + ".");
            sender.sendMessage("§7Each change is undone by its difference, so wins earned in real fights since then are kept.");
            sender.sendMessage("§7See them with §e/arenastats log " + args[1] + "§7. Run §e/arenastats rollback " + args[1] + " " + window + " confirm§7 to undo them.");
            return true;
        }

        final UUID performer = sender instanceof org.bukkit.entity.Player p ? p.getUniqueId() : null;
        final String performerName = sender.getName();
        sender.sendMessage("§7Rolling back...");
        Bukkit.getAsyncScheduler().runNow(SpawnArena.INSTANCE, task -> {
            final int[] result = SpawnArena.statsManager.rollback(entries, performer, performerName);
            me.zeronull.spawnarena.TopEloSuffix.refreshLater(); // an Elo change undone may change who's #1
            sender.sendMessage("§aRolled back §e" + me.zeronull.spawnarena.Grammar.count(result[0], "stat change")
                    + "§a made by §e" + actorLabel + "§a.");
            if (result[1] > 0)
                sender.sendMessage("§e" + me.zeronull.spawnarena.Grammar.count(result[1], "cooldown reset")
                        + " can't be undone (check §6/arenastats payouts§e for rewards paid after them).");
        });
        return true;
    }

    /** "30m", "12h", "7d" in millis; 0 for "all"; -1 if it can't be read. */
    private static long parseDuration(final String raw) {
        if (raw.equalsIgnoreCase("all"))
            return 0;
        if (raw.length() < 2)
            return -1;
        final long amount;
        try {
            amount = Long.parseLong(raw.substring(0, raw.length() - 1));
        } catch (final NumberFormatException e) {
            return -1;
        }
        if (amount <= 0)
            return -1;
        return switch (Character.toLowerCase(raw.charAt(raw.length() - 1))) {
            case 'm' -> amount * 60_000L;
            case 'h' -> amount * 3_600_000L;
            case 'd' -> amount * 86_400_000L;
            default -> -1;
        };
    }

    private static boolean isNumber(final String s) {
        try {
            Integer.parseInt(s);
            return true;
        } catch (final NumberFormatException e) {
            return false;
        }
    }

    private static final int PAYOUTS_PER_PAGE = 10;
    private static final java.time.format.DateTimeFormatter PAYOUT_TIME =
            java.time.format.DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(java.time.ZoneId.systemDefault());

    private static void showPayouts(final CommandSender sender, final OfflinePlayer target, final int page) {
        final StatsManager stats = SpawnArena.statsManager;
        final UUID uuid = target.getUniqueId();
        final double[] totals = stats.getPayoutTotals(uuid);

        sender.sendMessage(String.format("§6Payouts for §e%s§6: §e%d§6 total, §e%s §6(today: §e%s§6)",
                target.getName(), (long) totals[0], StatsManager.formatMoney(totals[1]),
                StatsManager.formatMoney(stats.getEarnedToday(uuid))));

        final List<StatsManager.OpponentTotal> top = stats.getTopPayoutOpponents(uuid, 3);
        if (!top.isEmpty()) {
            final List<String> parts = new java.util.ArrayList<>();
            for (final StatsManager.OpponentTotal t : top)
                parts.add(String.format("§e%s §7×%d (%s)", nameOf(t.opponent()), t.payouts(), StatsManager.formatMoney(t.amount())));
            sender.sendMessage("§6Paid most against: " + String.join("§7, ", parts));
        }

        final List<StatsManager.Payout> list = stats.getPayouts(uuid, page, PAYOUTS_PER_PAGE);
        if (list.isEmpty()) {
            sender.sendMessage(page == 1 ? "§7No payouts yet." : "§7No payouts on page " + page + ".");
            return;
        }
        for (final StatsManager.Payout p : list) {
            sender.sendMessage(String.format("§7%s §8· §e%s §8· §a+%s%s §7vs §f%s",
                    PAYOUT_TIME.format(java.time.Instant.ofEpochMilli(p.paidAt())), p.arena(),
                    StatsManager.formatMoney(p.amount()), p.leader() ? " §6(#1)" : "",
                    p.opponent() == null ? "-"
                            : nameOf(p.opponent()) + (p.opponent2() == null ? "" : " & " + nameOf(p.opponent2()))));
        }
        if (list.size() == PAYOUTS_PER_PAGE)
            sender.sendMessage("§7More: /arenastats payouts " + target.getName() + " " + (page + 1));
    }

    private static String nameOf(final UUID uuid) {
        final String name = SpawnArena.statsManager.nameOf(uuid);
        return name == null ? uuid.toString().substring(0, 8) : name;
    }

    /** Online player by name, else a player the server has seen before (no web lookups, no invented IDs). */
    private static OfflinePlayer resolvePlayer(final String name) {
        final org.bukkit.entity.Player online = Bukkit.getPlayerExact(name);
        if (online != null)
            return online;
        final org.bukkit.OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null)
            return cached;
        // Someone the server's own cache has forgotten, but whose name we saved
        final UUID saved = SpawnArena.statsManager.uuidOfName(name);
        return saved == null ? null : Bukkit.getOfflinePlayer(saved);
    }

    /** How many different players have had a counted fight in each arena (duels don't count). */
    private boolean players(final CommandSender sender) {
        final java.util.Map<String, Integer> counts = SpawnArena.statsManager.getPlayerCounts();
        final int total = counts.getOrDefault("", 0);
        sender.sendMessage("§6Players who've fought in each arena §7(counted fights, not duels):");
        for (final me.zeronull.spawnarena.Arena a : SpawnArena.arenas)
            sender.sendMessage("§7- §e" + a.getArenaName() + "§7: §e" + counts.getOrDefault(a.getArenaName().toLowerCase(), 0));
        sender.sendMessage("§6Different players overall: §e" + total);
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
        if (args[0].equalsIgnoreCase("log") || args[0].equalsIgnoreCase("rollback")) {
            if (args.length == 2) {
                final List<String> names = new java.util.ArrayList<>(List.of("console"));
                Bukkit.getOnlinePlayers().forEach(p -> names.add(p.getName()));
                return names.stream().filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).toList();
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("rollback")) return filter(List.of("30m", "12h", "7d", "all"), args[2]);
            if (args.length == 4 && args[0].equalsIgnoreCase("rollback")) return filter(List.of("confirm"), args[3]);
            return Collections.emptyList();
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("set")) return filter(STATS, args[3]);
        if (args.length == 3 && !args[0].equalsIgnoreCase("migrate") && !args[0].equalsIgnoreCase("payouts")) {
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
            case "elo" -> "elo";
            default -> null;
        };
    }
}
