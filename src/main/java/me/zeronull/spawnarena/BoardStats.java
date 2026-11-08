package me.zeronull.spawnarena;

import java.util.List;
import java.util.function.Predicate;

/**
 * The leaderboard types (/arenaleaderboard, the leaderboard holograms and the top_ / my_ placeholders), in one place.
 * Free-for-all ones start with ffa_ and mirror the usual ones: ffa_streak is the kill streak people are on right now
 * (live, from the free-for-all itself), ffa_best_streak the best they've ever had.
 */
public final class BoardStats {
    private BoardStats() {}

    /** The live one: read from the free-for-all running now, not the database. */
    public static final String LIVE_FFA_STREAK = "ffa_streak";

    /**
     * Every type, ordered so a name ending in one is never mistaken for a shorter one it also ends with (ffa_best_streak
     * before best_streak and ffa_streak, those before streak). "kdr" is the old name for ffa_kdr.
     */
    private static final List<String> PARSE_ORDER = List.of("ffa_best_streak", "ffa_daily_streak", "ffa_streak", "ffa_kills", "ffa_deaths",
            "ffa_kdr", "best_streak", "daily_streak", "streak", "wins", "elo", "playtime", "wlr", "kdr");

    /** What players and staff are offered, in this order. */
    public static final List<String> SHOWN = List.of("wins", "streak", "best_streak", "daily_streak", "elo", "wlr", "playtime",
            "ffa_kills", "ffa_deaths", "ffa_kdr", "ffa_streak", "ffa_best_streak", "ffa_daily_streak");

    /** Today's best streaks: they start again from nothing every day. */
    public static boolean isDaily(final String stat) {
        final String s = canonical(stat);
        return "daily_streak".equals(s) || "ffa_daily_streak".equals(s);
    }

    /** The type's own name (kdr -> ffa_kdr), or null if it isn't one. */
    public static String canonical(final String stat) {
        if (stat == null)
            return null;
        final String s = stat.toLowerCase(java.util.Locale.ROOT);
        if (s.equals("kdr"))
            return "ffa_kdr";
        return PARSE_ORDER.contains(s) ? s : null;
    }

    public static boolean isLive(final String stat) {
        return LIVE_FFA_STREAK.equals(canonical(stat));
    }

    /** The database field a type reads, or null for the live one and anything unknown. */
    public static String field(final String stat) {
        final String s = canonical(stat);
        if (s == null)
            return null;
        return switch (s) {
            case "wins" -> "wins";
            case "streak" -> "win_streak";
            case "best_streak" -> "best_win_streak";
            case "elo" -> "elo";
            case "ffa_kills" -> "ffa_kills";
            case "ffa_deaths" -> "ffa_deaths";
            case "ffa_best_streak" -> "ffa_best_streak";
            case "daily_streak" -> StatsManager.DAILY_STREAK;
            case "ffa_daily_streak" -> StatsManager.FFA_DAILY_STREAK;
            case "ffa_kdr" -> "kdr";
            case "playtime" -> "fight_seconds";
            case "wlr" -> "wlr";
            default -> null; // ffa_streak: live
        };
    }

    public static String label(final String stat) {
        final String s = canonical(stat);
        return switch (s == null ? "" : s) {
            case "streak" -> "Win Streak";
            case "best_streak" -> "Best Win Streak";
            case "elo" -> "Elo";
            case "ffa_kills" -> "FFA Kills";
            case "ffa_deaths" -> "FFA Deaths";
            case "ffa_kdr" -> "FFA K/D";
            case "ffa_streak" -> "FFA Kill Streak";
            case "ffa_best_streak" -> "Best FFA Kill Streak";
            case "daily_streak" -> "Best Win Streak Today";
            case "ffa_daily_streak" -> "Best FFA Kill Streak Today";
            case "playtime" -> "Time Fighting";
            case "wlr" -> "W/L";
            default -> "Wins";
        };
    }

    /**
     * The board's title: the arena's name and the stat, without saying FFA twice when the name already does
     * ("CrystalFFA Kill Streak", not "CrystalFFA FFA Kill Streak").
     */
    public static String title(final String arenaName, final String stat) {
        String label = label(stat);
        if (arenaName != null && arenaName.toLowerCase(java.util.Locale.ROOT).contains("ffa"))
            label = label.replace("FFA ", "");
        return arenaName + " " + label;
    }

    /**
     * Splits "&lt;arena&gt;_&lt;type&gt;" into {arena, type} (the type as written, see {@link #canonical}). An arena name
     * can itself end in something like "_ffa", so an ending that leaves the name of an existing arena (per
     * {@code isArena}) wins; failing that, the first that fits. Null if it ends in no type.
     */
    public static String[] split(final String text, final Predicate<String> isArena) {
        String[] first = null;
        for (final String s : PARSE_ORDER) {
            if (!text.endsWith("_" + s) || text.length() <= s.length() + 1)
                continue;
            final String[] found = {text.substring(0, text.length() - s.length() - 1), s};
            if (isArena == null || isArena.test(found[0]))
                return found;
            if (first == null)
                first = found;
        }
        return first;
    }

    /** {@link #split} preferring the names of this server's arenas (any case). */
    public static String[] split(final String text) {
        return split(text, name -> {
            if (SpawnArena.arenas != null)
                for (final Arena arena : SpawnArena.arenas)
                    if (arena.getArenaName().equalsIgnoreCase(name))
                        return true;
            return false;
        });
    }
}
