package me.zeronull.spawnarena;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Who played in each recorded match ({@link MatchRecorder}), kept in memory so a player's own match history
 * ({@link MatchHistoryMenu}) opens straight away: every recording's header is read once at startup, and each new
 * recording is added as it's saved. A recording since deleted (the oldest go past recordingMaxMb) is skipped.
 */
public final class MatchIndex {
    /** One recorded match, as the history menu shows it. */
    public record Match(File file, String arena, long started, int lengthMs, String variant, boolean duel, List<Side> fighters, boolean session) {
        /** The fighter with this UUID, or null. */
        public Side side(final UUID uuid) {
            for (final Side s : this.fighters)
                if (s.uuid().equals(uuid))
                    return s;
            return null;
        }

        /** A free-for-all round: more than two sides (each fighter on their own). */
        public boolean freeForAll() {
            return this.fighters.stream().map(Side::team).distinct().count() > 2;
        }

        /** Everyone on the other team from {@code me}. */
        public List<Side> against(final Side me) {
            final List<Side> out = new ArrayList<>();
            for (final Side s : this.fighters)
                if (s.team() != me.team())
                    out.add(s);
            return out;
        }
    }

    /** One fighter (kills and deaths: a free-for-all session's part only). */
    public record Side(UUID uuid, String name, int team, boolean won, int kills, int deaths) {}

    /** Player -> their matches, newest first. */
    private static final Map<UUID, List<Match>> BY_PLAYER = new ConcurrentHashMap<>();
    private static volatile boolean loaded;

    private MatchIndex() {}

    private static File root() {
        return new File(SpawnArena.INSTANCE.getDataFolder(), "recordings");
    }

    /** Reads every recording's header (off the main thread). */
    public static void load() {
        Threads.async(() -> {
            final long t0 = System.currentTimeMillis();
            int n = 0;
            for (final File f : CheatChecks.recordings(root(), Integer.MAX_VALUE)) {
                if (add(f, false))
                    n++;
            }
            for (final List<Match> list : BY_PLAYER.values())
                synchronized (list) {
                    list.sort(Comparator.comparingLong(Match::started).reversed());
                }
            loaded = true;
            SpawnArena.INSTANCE.getLogger().info("Indexed " + n + " recorded match(es) for /matches in " + (System.currentTimeMillis() - t0) + " ms.");
        });
    }

    /** A recording just saved: added to its fighters' history. */
    public static void added(final File file) {
        add(file, true);
    }

    private static boolean add(final File file, final boolean newest) {
        final Recording r;
        try {
            r = Recording.header(file);
        } catch (final IOException | RuntimeException e) {
            if (newest)
                SpawnArena.INSTANCE.getLogger().log(Level.FINE, "Couldn't index " + file, e);
            return false;
        }
        final List<Side> sides = new ArrayList<>(r.fighters.size());
        for (final Recording.Fighter f : r.fighters)
            sides.add(new Side(f.uuid, f.name, f.team, f.won, f.kills, f.deaths));
        final Match m = new Match(file, r.arena, r.started, r.lengthMs, r.variant, r.duel, List.copyOf(sides), r.session);
        for (final Side s : sides) {
            final List<Match> list = BY_PLAYER.computeIfAbsent(s.uuid(), u -> new ArrayList<>());
            synchronized (list) {
                if (newest)
                    list.add(0, m);
                else
                    list.add(m);
            }
        }
        return true;
    }

    /** Whether the startup scan has finished. */
    public static boolean ready() {
        return loaded;
    }

    /** This player's recorded matches, newest first (only those still on disk), in one arena or all (arena null). */
    public static List<Match> of(final UUID player, final String arena) {
        final List<Match> list = BY_PLAYER.get(player);
        if (list == null)
            return List.of();
        final List<Match> out = new ArrayList<>();
        synchronized (list) {
            list.removeIf(m -> !m.file().isFile());
            for (final Match m : list)
                if (arena == null || m.arena().equalsIgnoreCase(arena))
                    out.add(m);
        }
        return out;
    }
}
