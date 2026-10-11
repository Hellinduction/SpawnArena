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
 *   %spawnarena_top_player_<arena>_wins_<rank>%
 *   %spawnarena_top_player_<arena>_streak_<rank>%
 *   %spawnarena_top_player_<arena>_best_streak_<rank>%
 *   %spawnarena_top_value_<arena>_wins_<rank>%
 *   %spawnarena_top_value_<arena>_streak_<rank>%
 *   %spawnarena_top_value_<arena>_best_streak_<rank>%
 */
public final class SpawnArenaPapiExpansion extends PlaceholderExpansion {

    private static final int TOP_CACHE_SIZE = me.zeronull.spawnarena.commands.impl.ArenaLeaderboardCommand.MAX_LIMIT;
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
        final String[] parts = params.split("_");

        if (parts.length >= 2) {
            final String type = parts[0];

            // Personal stats: wins_<arena>, streak_<arena>, best_streak_<arena>
            if (player != null && (type.equals("wins") || type.equals("streak") || type.equals("best"))) {
                final String arena = String.join("_", java.util.Arrays.copyOfRange(parts, type.equals("best") ? 2 : 1, parts.length));
                final UUID uuid = player.getUniqueId();
                return switch (type) {
                    case "wins" -> String.valueOf(SpawnArena.statsManager.getWins(uuid, arena));
                    case "streak" -> String.valueOf(SpawnArena.statsManager.getWinStreak(uuid, arena));
                    case "best" -> String.valueOf(SpawnArena.statsManager.getBestWinStreak(uuid, arena));
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

                // stat is the segment(s) before the rank; best_streak spans two segments
                final boolean bestStreak = parts.length >= 6
                        && parts[parts.length - 3].equals("best") && parts[parts.length - 2].equals("streak");
                final String stat = bestStreak ? "best_streak" : parts[parts.length - 2];
                final int arenaEnd = parts.length - (bestStreak ? 3 : 2);
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

    private static String statToField(String stat) {
        return switch (stat.toLowerCase()) {
            case "wins" -> "wins";
            case "streak" -> "win_streak";
            case "best_streak", "best" -> "best_win_streak";
            default -> null;
        };
    }
}
