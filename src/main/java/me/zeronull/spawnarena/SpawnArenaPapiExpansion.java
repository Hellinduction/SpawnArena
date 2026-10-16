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
 *   %spawnarena_queue_<1v1|2v2|total>_<arena>%    players waiting
 *   %spawnarena_matches_<1v1|2v2|total>_<arena>%  matches in progress
 */
public final class SpawnArenaPapiExpansion extends PlaceholderExpansion {

    private static final int TOP_CACHE_SIZE = me.zeronull.spawnarena.commands.impl.ArenaLeaderboardCommand.MAX_LIMIT;
    /** my_rank_<arena>_<stat> ("#14", or "unranked") and my_value_<arena>_<stat>, for the viewer. */
    private static String standing(final Player player, final String params) {
        if (player == null)
            return "";
        final boolean rank = params.startsWith("my_rank_");
        final String rest = params.substring(rank ? "my_rank_".length() : "my_value_".length());
        String stat = null;
        for (final String s : new String[]{"ffa_kills", "ffa_deaths", "ffa_streak", "best_streak", "streak", "wins", "elo"})
            if (rest.endsWith("_" + s)) { stat = s; break; }
        if (stat == null)
            return null;
        final String field = statToField(stat);
        final String arena = rest.substring(0, rest.length() - stat.length() - 1);
        final StatsManager.Standing standing = SpawnArena.statsManager.getStandingCached(player.getUniqueId(), arena, field);
        if (standing == null)
            return "...";
        if (rank)
            return standing.rank() > 0 ? "#" + standing.rank() : "unranked";
        return String.valueOf(standing.value());
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
        if (params.startsWith("my_rank_") || params.startsWith("my_value_"))
            return standing(player, params);
        if (params.startsWith("queue_") || params.startsWith("matches_"))
            return queue(params);

        final String[] parts = params.split("_");

        if (parts.length >= 2) {
            final String type = parts[0];

            // Free-for-all: ffa_kills_<arena>, ffa_deaths_<arena>, ffa_streak_<arena> (best kill streak)
            if (player != null && type.equals("ffa") && parts.length >= 3) {
                final String field = statToField("ffa_" + parts[1]);
                if (field == null)
                    return null;
                final String arena = String.join("_", java.util.Arrays.copyOfRange(parts, 2, parts.length));
                return String.valueOf(SpawnArena.statsManager.getField(player.getUniqueId(), arena, field));
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

                // stat is the segment(s) before the rank; best_streak and the ffa_ stats span two segments
                final boolean bestStreak = parts.length >= 6
                        && parts[parts.length - 3].equals("best") && parts[parts.length - 2].equals("streak");
                final boolean ffaStat = !bestStreak && parts.length >= 6 && parts[parts.length - 3].equals("ffa")
                        && statToField("ffa_" + parts[parts.length - 2]) != null;
                final String stat = bestStreak ? "best_streak" : ffaStat ? "ffa_" + parts[parts.length - 2] : parts[parts.length - 2];
                final int arenaEnd = parts.length - (bestStreak || ffaStat ? 3 : 2);
                final String arena = String.join("_", java.util.Arrays.copyOfRange(parts, 2, arenaEnd));

                final String dbField = statToField(stat);
                if (dbField == null) return null;

                final List<StatsManager.LeaderboardEntry> top = cachedTop(arena, dbField);

                final int idx = rank - 1;
                if (idx >= top.size()) return "-";

                final StatsManager.LeaderboardEntry entry = top.get(idx);
                return switch (subType) {
                    case "player" -> entry.name();
                    case "value" -> String.valueOf(entry.value());
                    default -> null;
                };
            }
        }

        return null;
    }

    /** queue_<1v1|2v2|total>_<arena> (players waiting), matches_<1v1|2v2|total>_<arena> (matches in progress) */
    private static String queue(final String params) {
        final String[] parts = params.split("_", 3);
        if (parts.length < 3 || SpawnArena.arenas == null)
            return null;
        final Arena arena = SpawnArena.arenas.of(parts[2]);
        if (arena == null)
            return null;

        final FightMode mode = FightMode.fromLabel(parts[1]);
        if (mode == null && !parts[1].equals("total"))
            return null;

        if (parts[0].equals("queue"))
            return String.valueOf(mode == null ? arena.queue.size() : arena.queue.size(mode));
        return String.valueOf(arena.getFights().stream().filter(f -> mode == null || f.getMode() == mode).count());
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

    private static String statToField(String stat) {
        return switch (stat.toLowerCase()) {
            case "wins" -> "wins";
            case "streak" -> "win_streak";
            case "best_streak", "best" -> "best_win_streak";
            case "elo" -> "elo";
            case "ffa_kills" -> "ffa_kills";
            case "ffa_deaths" -> "ffa_deaths";
            case "ffa_streak" -> "ffa_best_streak";
            default -> null;
        };
    }
}
