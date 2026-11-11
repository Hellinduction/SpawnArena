package me.zeronull.spawnarena;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Recorded matches each player has favourited in /matches, kept in favourite-matches.yml (player -> "day/file" paths,
 * newest favourite first). A favourited recording is never deleted to make room ({@link MatchRecorder}'s pruning skips
 * it).
 */
public final class MatchFavourites {
    /** The most favourites a player can have */
    public static final int MAX = 100;
    private static final Map<UUID, LinkedHashSet<String>> BY_PLAYER = new ConcurrentHashMap<>();
    private static volatile boolean loaded;

    private MatchFavourites() {}

    private static File file() {
        return new File(SpawnArena.INSTANCE.getDataFolder(), "favourite-matches.yml");
    }

    /** "day/file", as kept here and as /arenareplay file takes it. */
    public static String key(final File recording) {
        return recording.getParentFile().getName() + "/" + recording.getName();
    }

    private static synchronized void load() {
        if (loaded)
            return;
        loaded = true;
        final File f = file();
        if (!f.exists())
            return;
        final YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        for (final String id : yml.getKeys(false))
            try {
                BY_PLAYER.put(UUID.fromString(id), new LinkedHashSet<>(yml.getStringList(id)));
            } catch (final IllegalArgumentException ignored) {
                // not a player
            }
    }

    private static synchronized void save() {
        final YamlConfiguration yml = new YamlConfiguration();
        for (final Map.Entry<UUID, LinkedHashSet<String>> e : BY_PLAYER.entrySet())
            if (!e.getValue().isEmpty())
                yml.set(e.getKey().toString(), new ArrayList<>(e.getValue()));
        try {
            yml.save(file());
        } catch (final java.io.IOException e) {
            SpawnArena.INSTANCE.getLogger().log(Level.WARNING, "Couldn't save favourite-matches.yml", e);
        }
    }

    public static boolean is(final UUID player, final File recording) {
        load();
        final Set<String> set = BY_PLAYER.get(player);
        return set != null && set.contains(key(recording));
    }

    /**
     * Favourites it, or takes it off their favourites. Returns true if it's now a favourite; null if they already have
     * the most they can.
     */
    public static synchronized Boolean toggle(final UUID player, final File recording) {
        load();
        final LinkedHashSet<String> set = BY_PLAYER.computeIfAbsent(player, u -> new LinkedHashSet<>());
        final String key = key(recording);
        final boolean now;
        if (set.remove(key))
            now = false;
        else {
            if (set.size() >= MAX)
                return null;
            // Newest first
            final LinkedHashSet<String> again = new LinkedHashSet<>();
            again.add(key);
            again.addAll(set);
            set.clear();
            set.addAll(again);
            now = true;
        }
        Threads.async(MatchFavourites::save);
        return now;
    }

    /** Their favourites ("day/file"), newest favourite first. */
    public static List<String> of(final UUID player) {
        load();
        final Set<String> set = BY_PLAYER.get(player);
        return set == null ? List.of() : new ArrayList<>(set);
    }

    /** Every favourited recording ("day/file"), anyone's: kept when the oldest are cleared out. */
    public static Set<String> all() {
        load();
        final Set<String> out = new HashSet<>();
        for (final Set<String> s : BY_PLAYER.values())
            out.addAll(s);
        return out;
    }
}
