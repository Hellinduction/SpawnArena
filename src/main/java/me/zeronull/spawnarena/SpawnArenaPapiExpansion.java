package me.zeronull.spawnarena;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Placeholders:
 *   %spawnarena_wins_<arena>%
 *   %spawnarena_streak_<arena>%
 *   %spawnarena_best_streak_<arena>%
 *   %spawnarena_elo_<arena>%
 *   %spawnarena_top_player_<arena>_wins_<rank>%
 *   %spawnarena_top_player_<arena>_streak_<rank>%
 *   %spawnarena_top_player_<arena>_best_streak_<rank>%
 *   %spawnarena_top_value_<arena>_wins_<rank>%
 *   %spawnarena_top_value_<arena>_streak_<rank>%
 *   %spawnarena_top_value_<arena>_best_streak_<rank>%
 *   %spawnarena_reward_cooldown_<arena>%   "Ready!" or "1h 05m 09s" (per player)
 *   %spawnarena_reward_ready_<arena>%      true / false (per player)
 *   %spawnarena_reward_amount_<arena>%     e.g. "$50k" (boosted for the #1 win streak holder)
 *   %spawnarena_reward_every_<arena>%      e.g. "2h" ("5m" for the #1 win streak holder)
 *   %spawnarena_reward_leaderamount_<arena>% / %spawnarena_reward_leaderevery_<arena>%  the #1 streak bonus
 *   %spawnarena_reward_secondamount_<arena>% / _secondevery_ / _thirdamount_ / _thirdevery_  the #2 and #3 bonuses
 *   %spawnarena_reward_leader_<arena>%     true / false (per player)
 *   %spawnarena_reward_status_<arena>%     "Ready!", countdown, "Beat someone new to earn again", "Daily limit reached · resets in 5h 12m" or "Resetting the arena..."
 *   %spawnarena_reward_last_<arena>%       last win's result, e.g. "+$50k" or "Not paid: on cooldown"
 *   %spawnarena_reward_today% / %spawnarena_reward_dailycap% / %spawnarena_reward_dailyleft%
 *   %spawnarena_reward_dailyreset%      " · resets in 5h 12m" (empty with no daily cap)
 *   %spawnarena_ffa_<kills|deaths|kdr|streak|best_streak>_<arena>%  free-for-all stats (streak: the kill streak
 *       they're on right now in that free-for-all, 0 when not in it; best_streak: their best ever)
 *   Leaderboards take any type from BoardStats (wins, streak, best_streak, elo, wlr, playtime, ffa_kills, ffa_deaths,
 *       ffa_kdr, ffa_streak (live), ffa_best_streak) in top_player_ / top_value_ and my_rank_ / my_value_
 *   %spawnarena_queue_<1v1|2v2|ffa|total>_<arena>%    players waiting (ffa: a spleef arena's free-for-all rounds)
 *   %spawnarena_matches_<1v1|2v2|ffa|total>_<arena>%  matches (ffa: free-for-all rounds) in progress
 *   %spawnarena_queue_ffastarts_<arena>%  seconds until the waiting free-for-all round starts (empty while it isn't counting down)
 */
public final class SpawnArenaPapiExpansion extends PlaceholderExpansion {

    private static final int TOP_CACHE_SIZE = me.zeronull.spawnarena.commands.impl.ArenaLeaderboardCommand.MAX_LIMIT;
    /**
     * global_<stat> for every arena on this server together, global_<stat>_<arena> for one: matches, players, time,
     * ffa_kills, paid, and my_matches (the viewer's matches won or lost).
     */
    private static String global(final Player player, final String what) {
        for (final String stat : new String[]{"my_matches", "ffa_kills", "matches", "players", "time", "paid"}) {
            if (!what.equals(stat) && !what.startsWith(stat + "_"))
                continue;
            final String arena = what.equals(stat) ? null : what.substring(stat.length() + 1);
            if (stat.equals("my_matches"))
                return player == null ? "" : String.format("%,d", SpawnArena.statsManager.getMatchesPlayed(player.getUniqueId(), arena));
            final StatsManager.GlobalStats stats = SpawnArena.statsManager.getGlobalStats();
            if (stats == null)
                return "...";
            final StatsManager.ArenaTotals totals = stats.of(arena);
            return switch (stat) {
                case "matches" -> String.format("%,d", totals.matches());
                case "players" -> String.format("%,d", totals.players());
                case "time" -> StatsManager.formatDuration(totals.fightSeconds());
                case "ffa_kills" -> String.format("%,d", totals.ffaKills());
                default -> StatsManager.formatMoney(totals.paid());
            };
        }
        return null;
    }

    /** my_rank_<arena>_<stat> ("#14", or "unranked") and my_value_<arena>_<stat>, for the viewer. */
    private static String standing(final Player player, final String params) {
        if (player == null)
            return "";
        final boolean rank = params.startsWith("my_rank_");
        final String rest = params.substring(rank ? "my_rank_".length() : "my_value_".length());
        final String[] board = BoardStats.split(rest);
        if (board == null)
            return null;
        final String arena = board[0];
        if (BoardStats.isLive(board[1])) {
            // Only while they're in that free-for-all
            final List<FfaFight.LiveStreak> live = FfaFight.liveStreaks(arena);
            for (int i = 0; i < live.size(); i++)
                if (live.get(i).id().equals(player.getUniqueId()))
                    return rank ? "#" + (i + 1) : String.valueOf(live.get(i).streak());
            return rank ? "not fighting" : "-";
        }
        final String field = BoardStats.field(board[1]);
        final StatsManager.Standing standing = SpawnArena.statsManager.getStanding(player.getUniqueId(), arena, field);
        if (standing == null)
            return "...";
        if (rank)
            return standing.rank() > 0 ? "#" + standing.rank() : "unranked";
        return shown(field, standing.value());
    }

    private List<StatsManager.LeaderboardEntry> cachedTop(String arena, String field) {
        return SpawnArena.statsManager.getTopPlayersCached(arena, field, TOP_CACHE_SIZE);
    }

    @Override
    public @NotNull String getIdentifier() { return "spawnarena"; }

    @Override
    public @NotNull String getAuthor() { return "SpawnArena"; }

    @Override
    public @NotNull String getVersion() { return "1.0"; }

    @Override
    public boolean persist() { return true; }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        if (params.startsWith("reward_"))
            return reward(player, params.substring("reward_".length()));
        // When the daily boards start again (midnight, server time)
        if (params.equals("daily_reset"))
            return StatsManager.formatMinutes(StatsManager.minutesUntilDailyReset());
        if (params.startsWith("global_"))
            return global(player, params.substring("global_".length()));
        // The #1 Elo tag: " #1 CrystalPvP" (legacy & / &#hex colours), or "" if they're #1 nowhere
        if (params.equals("top_elo"))
            return player == null ? "" : TopEloSuffix.tagOf(player.getUniqueId());
        if (params.startsWith("my_rank_") || params.startsWith("my_value_"))
            return standing(player, params);
        if (params.startsWith("queue_") || params.startsWith("matches_"))
            return queue(params);

        final String[] parts = params.split("_");

        if (parts.length >= 2) {
            final String type = parts[0];

            // Free-for-all: ffa_kills_<arena>, ffa_deaths_<arena>, ffa_kdr_<arena>, ffa_streak_<arena> (the kill streak
            // they're on right now), ffa_best_streak_<arena> (their best)
            if (player != null && type.equals("ffa") && parts.length >= 3)
                return ffa(player, params.substring("ffa_".length()));

            // Ratios and losses: kdr_<arena> (free-for-all K/D), wlr_<arena> (W/L), losses_<arena>
            if (player != null && (type.equals("kdr") || type.equals("wlr") || type.equals("losses"))) {
                final String arena = String.join("_", java.util.Arrays.copyOfRange(parts, 1, parts.length));
                final UUID uuid = player.getUniqueId();
                final StatsManager stats = SpawnArena.statsManager;
                return switch (type) {
                    case "kdr" -> StatsManager.ratio(stats.getField(uuid, arena, "ffa_kills"), stats.getField(uuid, arena, "ffa_deaths"));
                    case "wlr" -> StatsManager.ratio(stats.getWins(uuid, arena), stats.getLosses(uuid, arena));
                    default -> String.valueOf(stats.getLosses(uuid, arena));
                };
            }

            // Personal stats: wins_<arena>, streak_<arena>, best_streak_<arena>
            if (player != null && (type.equals("wins") || type.equals("streak") || type.equals("best") || type.equals("elo"))) {
                final String arena = String.join("_", java.util.Arrays.copyOfRange(parts, type.equals("best") ? 2 : 1, parts.length));
                final UUID uuid = player.getUniqueId();
                return switch (type) {
                    case "wins" -> String.valueOf(SpawnArena.statsManager.getWins(uuid, arena));
                    case "streak" -> String.valueOf(SpawnArena.statsManager.getWinStreak(uuid, arena));
                    case "best" -> String.valueOf(SpawnArena.statsManager.getBestWinStreak(uuid, arena));
                    case "elo" -> String.valueOf(SpawnArena.statsManager.getElo(uuid, arena));
                    default -> null;
                };
            }

            // Leaderboard: top_player_<arena>_<stat>_<rank>  /  top_value_<arena>_<stat>_<rank>
            if ((type.equals("top")) && parts.length >= 5) {
                final String subType = parts[1]; // "player" or "value"
                // rank is last segment
                int rank;
                try {
                    rank = Integer.parseInt(parts[parts.length - 1]);
                } catch (NumberFormatException e) {
                    return null;
                }
                if (rank < 1 || rank > TOP_CACHE_SIZE) return null;

                // <arena>_<stat> is everything between "top_player_" and the rank; the stat can be several segments
                final String[] board = BoardStats.split(String.join("_", java.util.Arrays.copyOfRange(parts, 2, parts.length - 1)));
                if (board == null) return null;
                final String arena = board[0];

                if (BoardStats.isLive(board[1])) {
                    final List<FfaFight.LiveStreak> live = FfaFight.liveStreaks(arena);
                    if (rank > live.size()) return "-";
                    final FfaFight.LiveStreak entry = live.get(rank - 1);
                    return switch (subType) {
                        case "player" -> entry.name();
                        case "value" -> String.valueOf(entry.streak());
                        default -> null;
                    };
                }

                final String dbField = BoardStats.field(board[1]);
                if (dbField == null) return null;

                final List<StatsManager.LeaderboardEntry> top = cachedTop(arena, dbField);

                final int idx = rank - 1;
                if (idx >= top.size()) return "-";

                final StatsManager.LeaderboardEntry entry = top.get(idx);
                return switch (subType) {
                    case "player" -> entry.name();
                    case "value" -> shown(dbField, entry.value());
                    default -> null;
                };
            }
        }

        return null;
    }

    /** queue_<1v1|2v2|ffa|total>_<arena> (players waiting), matches_<1v1|2v2|ffa|total>_<arena> (matches in progress), queue_ffastarts_<arena> */
    private static String queue(final String params) {
        final String[] parts = params.split("_", 3);
        if (parts.length < 3 || SpawnArena.arenas == null)
            return null;
        final Arena arena = SpawnArena.arenas.of(parts[2]);
        if (arena == null)
            return null;

        // A spleef arena's free-for-all round: the seconds until it starts (empty while it isn't counting down)
        if (parts[0].equals("queue") && parts[1].equals("ffastarts")) {
            final int left = arena.queue.ffaSecondsLeft();
            return left < 0 ? "" : String.valueOf(left);
        }
        final FightMode mode = FightMode.fromLabel(parts[1]);
        if (mode == null && !parts[1].equals("total"))
            return null;

        if (parts[0].equals("queue"))
            return String.valueOf(mode == null ? arena.queue.size() : arena.queue.size(mode));
        return String.valueOf(arena.allFights().stream().filter(f -> mode == null || f.getMode() == mode).count());
    }

    /** reward_cooldown_<arena>, reward_ready_<arena>, reward_amount_<arena>, reward_every_<arena> */
    private static String reward(final Player player, final String rest) {
        // Daily totals aren't per arena
        final StatsManager stats = SpawnArena.statsManager;
        switch (rest) {
            case "today" -> { return player == null ? "" : formatAmount(stats.getEarnedToday(player.getUniqueId())); }
            case "dailycap" -> { return stats.dailyCap() > 0 ? formatAmount(stats.dailyCap()) : "No limit"; }
            // " · resets in 5h 12m", or nothing when there's no daily cap
            case "dailyreset" -> {
                return stats.dailyCap() > 0 ? " · resets in " + StatsManager.formatMinutes(StatsManager.minutesUntilDailyReset()) : "";
            }
            // Payouts in a row allowed against the same opponent
            case "samelimit" -> {
                final int limit = SpawnArena.settings == null ? 0 : SpawnArena.settings.sameOpponentLimit;
                return limit > 0 ? String.valueOf(limit) : "∞";
            }
            case "dailyleft" -> {
                if (player == null) return "";
                return stats.dailyCap() > 0
                        ? formatAmount(Math.max(0, stats.dailyCap() - stats.getEarnedToday(player.getUniqueId())))
                        : "No limit";
            }
        }

        final int split = rest.indexOf('_');
        if (split <= 0)
            return null;

        final String type = rest.substring(0, split);
        final Arena arena = SpawnArena.arenas == null ? null : SpawnArena.arenas.of(rest.substring(split + 1));
        if (arena == null)
            return null;

        final boolean enabled = arena.getWinReward() > 0;

        // A free-for-all's kill streak payouts
        if (type.startsWith("ffa")) {
            final boolean paying = arena.getFfaStreakReward() > 0 || arena.getFfaStreakRewardTens() > 0;
            return switch (type) {
                case "ffafives" -> arena.getFfaStreakReward() > 0 ? formatAmount(arena.getFfaStreakReward()) : "-";
                case "ffatens" -> arena.getFfaStreakRewardTens() > 0 ? formatAmount(arena.getFfaStreakRewardTens()) : "-";
                case "ffaevery" -> StatsManager.formatMinutes(arena.getFfaStreakRewardCooldownMinutes());
                case "ffasame" -> StatsManager.formatMinutes(arena.getFfaSameVictimMinutes());
                case "ffastreak" -> player == null ? "" : String.valueOf(me.zeronull.spawnarena.FfaFight.paidStreakOf(arena, player.getUniqueId()));
                case "ffanext" -> {
                    if (player == null) yield "";
                    if (!paying) yield "-";
                    if (stats.hasReachedDailyCap(player.getUniqueId()))
                        yield "Daily limit reached · resets in " + StatsManager.formatMinutes(StatsManager.minutesUntilDailyReset());
                    final int next = me.zeronull.spawnarena.FfaFight.nextPaying(player.getUniqueId(), arena,
                            me.zeronull.spawnarena.FfaFight.paidStreakOf(arena, player.getUniqueId()));
                    yield next == 0 ? "-" : next + " kills (" + formatAmount(arena.ffaRewardFor(next)) + ")";
                }
                case "ffawaits" -> player == null ? "" : me.zeronull.spawnarena.FfaFight.waits(player.getUniqueId(), arena);
                default -> null;
            };
        }

        return switch (type) {
            // amount/every are personal: the #1 win streak holder sees their boosted values
            case "amount" -> !enabled ? "-" : formatAmount(player == null ? arena.getWinReward()
                    : SpawnArena.statsManager.rewardAmountFor(player.getUniqueId(), arena));
            case "every" -> StatsManager.formatMinutes(player == null ? arena.getWinRewardCooldownMinutes()
                    : SpawnArena.statsManager.rewardCooldownMinutesFor(player.getUniqueId(), arena));
            case "leaderamount" -> enabled ? formatAmount(arena.getWinReward() * arena.getLeaderRewardMultiplier()) : "-";
            case "leaderevery" -> StatsManager.formatMinutes(arena.getLeaderRewardCooldownMinutes());
            case "secondamount" -> enabled ? formatAmount(arena.getWinReward() * arena.rewardMultiplierFor(2)) : "-";
            case "secondevery" -> StatsManager.formatMinutes(arena.rewardCooldownMinutesFor(2));
            case "thirdamount" -> enabled ? formatAmount(arena.getWinReward() * arena.rewardMultiplierFor(3)) : "-";
            case "thirdevery" -> StatsManager.formatMinutes(arena.rewardCooldownMinutesFor(3));
            case "leader" -> String.valueOf(player != null
                    && SpawnArena.statsManager.isStreakLeader(player.getUniqueId(), arena.getArenaName()));
            case "cooldown" -> {
                if (player == null) yield "";
                if (!enabled) yield "-";
                final long remaining = SpawnArena.statsManager.rewardCooldownRemaining(player.getUniqueId(), arena);
                yield remaining <= 0 ? "Ready!" : StatsManager.formatCountdown(remaining);
            }
            case "status" -> {
                if (player == null) yield "";
                if (arena.isResetting()) yield "Resetting the arena...";
                if (!enabled) yield "-";
                final UUID uuid = player.getUniqueId();
                if (stats.hasReachedDailyCap(uuid))
                    yield "Daily limit reached · resets in " + StatsManager.formatMinutes(StatsManager.minutesUntilDailyReset());
                if (stats.isSameOpponentBlocked(uuid, arena.getArenaName())) yield "Beat someone new to earn again";
                final long remaining = stats.rewardCooldownRemaining(uuid, arena);
                yield remaining <= 0 ? "Ready!" : StatsManager.formatCountdown(remaining);
            }
            case "last" -> player == null ? "" : stats.getLastResult(player.getUniqueId(), arena.getArenaName());
            case "ready" -> player == null || !enabled ? "false"
                    : String.valueOf(!stats.hasReachedDailyCap(player.getUniqueId())
                            && stats.rewardCooldownRemaining(player.getUniqueId(), arena) <= 0);
            default -> null;
        };
    }

    private static String formatAmount(final double amount) {
        return StatsManager.formatMoney(amount);
    }

    /** ffa_<kills|deaths|kdr|streak|best_streak>_<arena> for the viewer (given without the "ffa_"). */
    private static String ffa(final Player player, final String rest) {
        final UUID uuid = player.getUniqueId();
        final StatsManager stats = SpawnArena.statsManager;
        for (final String stat : new String[]{"best_streak", "streak", "kills", "deaths", "kdr"}) {
            if (!rest.startsWith(stat + "_") || rest.length() <= stat.length() + 1)
                continue;
            final String arena = rest.substring(stat.length() + 1);
            return switch (stat) {
                case "streak" -> {
                    for (final FfaFight.LiveStreak live : FfaFight.liveStreaks(arena))
                        if (live.id().equals(uuid))
                            yield String.valueOf(live.streak());
                    yield "0";
                }
                case "kdr" -> StatsManager.ratio(stats.getField(uuid, arena, "ffa_kills"), stats.getField(uuid, arena, "ffa_deaths"));
                default -> String.valueOf(stats.getField(uuid, arena, BoardStats.field("ffa_" + stat)));
            };
        }
        return null;
    }

    /** How a board's value shows: time for playtime, two decimals for the ratios, else the number. */
    public static String shown(final String field, final int value) {
        if (field.equals("fight_seconds"))
            return StatsManager.formatDuration(value);
        if (StatsManager.isRatio(field))
            return StatsManager.formatRatio(value);
        return String.valueOf(value);
    }
}
