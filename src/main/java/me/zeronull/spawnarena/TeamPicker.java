package me.zeronull.spawnarena;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Splits 4 players into two 2v2 teams: keeps SKFriends friends together where possible, and otherwise
 * avoids repeating each player's recent teammates. Friend lookups fail soft — without SKFriends (or if
 * it changes), friendships are simply ignored.
 */
public final class TeamPicker {
    private static final int RECENT_TEAMMATES = 5;

    private TeamPicker() {}

    /** Returns {teamA, teamB}. Expects exactly 4 players. */
    public static List<List<Player>> split(final List<Player> four) {
        final Player a = four.get(0), b = four.get(1), c = four.get(2), d = four.get(3);
        // The only 3 ways to split 4 players into two pairs
        final List<List<List<Player>>> options = new ArrayList<>(List.of(
                List.of(List.of(a, b), List.of(c, d)),
                List.of(List.of(a, c), List.of(b, d)),
                List.of(List.of(a, d), List.of(b, c))));
        Collections.shuffle(options, ThreadLocalRandom.current());

        List<List<Player>> best = options.get(0);
        int bestFriends = -1, bestRepeats = Integer.MAX_VALUE;
        for (final List<List<Player>> option : options) {
            int friends = 0, repeats = 0;
            for (final List<Player> pair : option) {
                if (Friends.areFriends(pair.get(0), pair.get(1)))
                    friends++;
                repeats += recentlyTeamed(pair.get(0), pair.get(1));
            }
            // Most friends together first, then fewest repeated teammates
            if (friends > bestFriends || (friends == bestFriends && repeats < bestRepeats)) {
                best = option;
                bestFriends = friends;
                bestRepeats = repeats;
            }
        }
        return best;
    }

    /** Records who teamed with whom (stored, so it survives restarts), for spreading teammates around next time. */
    public static void remember(final List<Player> teamA, final List<Player> teamB) {
        for (final List<Player> team : List.of(teamA, teamB))
            for (final Player p : team)
                for (final Player mate : team)
                    if (!p.equals(mate))
                        SpawnArena.statsManager.recordTeammate(p.getUniqueId(), mate.getUniqueId());
    }

    private static int recentlyTeamed(final Player a, final Player b) {
        int count = 0;
        if (SpawnArena.statsManager.getRecentTeammates(a.getUniqueId(), RECENT_TEAMMATES).contains(b.getUniqueId())) count++;
        if (SpawnArena.statsManager.getRecentTeammates(b.getUniqueId(), RECENT_TEAMMATES).contains(a.getUniqueId())) count++;
        return count;
    }

    /** SKFriends lookups. SKFriends has no public API, so this reads it reflectively and falls back to PlaceholderAPI. */
    static final class Friends {
        private static boolean reflectionBroken;
        private static boolean warned;

        static boolean areFriends(final Player a, final Player b) {
            final Plugin plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("SKFriends");
            if (plugin == null || !plugin.isEnabled())
                return false;

            if (!reflectionBroken) {
                try {
                    final Method storeMethod = plugin.getClass().getDeclaredMethod("store");
                    storeMethod.setAccessible(true);
                    final Object store = storeMethod.invoke(plugin);
                    final Method areFriends = store.getClass().getDeclaredMethod("areFriends", UUID.class, UUID.class);
                    areFriends.setAccessible(true);
                    return (boolean) areFriends.invoke(store, a.getUniqueId(), b.getUniqueId());
                } catch (final Throwable t) {
                    reflectionBroken = true;
                    warnOnce("Couldn't read SKFriends directly (" + t + "); falling back to its placeholders.");
                }
            }

            try {
                if (org.bukkit.Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI"))
                    return Placeholders.friendListContains(a, b.getName()) || Placeholders.friendListContains(b, a.getName());
            } catch (final Throwable t) {
                warnOnce("Couldn't read SKFriends placeholders either (" + t + "); 2v2 teams will ignore friends.");
            }
            return false;
        }

        private static void warnOnce(final String message) {
            if (!warned) {
                warned = true;
                SpawnArena.INSTANCE.getLogger().warning(message);
            }
        }
    }

    /** Kept separate so PlaceholderAPI classes are only touched when it's installed. */
    static final class Placeholders {
        static boolean friendListContains(final Player player, final String name) {
            final String raw = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, "%skfriends_online_list%");
            if (raw == null || raw.isEmpty() || raw.contains("%skfriends"))
                return false;
            final String plain = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', raw)).replaceAll("<[^>]+>", "");
            return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE)
                    .matcher(plain).find();
        }
    }
}
