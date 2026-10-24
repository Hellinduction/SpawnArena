package me.zeronull.spawnarena;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;
import java.util.logging.Level;

/**
 * Each arena's #1 on its Elo board gets a LuckPerms suffix (topEloSuffix, e.g. " #1 CrystalPvP"). Someone #1 in
 * several arenas shows the one they reached first. Checked every minute and after each rated fight; who holds what
 * since when, and the suffix each player was given, are kept in top-elo.yml so they survive restarts and the right
 * suffix can be taken off again. Without LuckPerms (or with topEloSuffix empty) it does nothing.
 */
public final class TopEloSuffix {
    private static final File FILE = new File(SpawnArena.INSTANCE.getDataFolder(), "top-elo.yml");
    /** Arena -> its #1 and when they became it. */
    private static final Map<String, Holder> HOLDERS = new LinkedHashMap<>();
    /** Player -> the suffix SpawnArena gave them (exactly as given, so it can be removed). */
    private static final Map<UUID, String> GIVEN = new HashMap<>();
    /** Player -> their #1 tag right now (what %spawnarena_top_elo% shows). */
    private static final Map<UUID, String> TAGS = new java.util.concurrent.ConcurrentHashMap<>();

    /** The #1 tag for this player (" #1 CrystalPvP" in topEloSuffix's colours), or "" if they're #1 nowhere. */
    public static String tagOf(final UUID uuid) {
        return TAGS.getOrDefault(uuid, "");
    }
    private static volatile boolean loaded;
    private static volatile boolean warned;

    private record Holder(UUID uuid, long since) {}

    private TopEloSuffix() {}

    public static void start() {
        Threads.globalTimer(task -> refreshLater(), 20L * 30, 20L * 60);
    }

    /** Works it out again off the main thread (the board is a database query). */
    public static void refreshLater() {
        Threads.async(TopEloSuffix::refresh);
    }

    private static synchronized void refresh() {
        try {
            if (SpawnArena.arenas == null || SpawnArena.statsManager == null)
                return;
            load();
            final String format = SpawnArena.settings == null ? "" : SpawnArena.settings.topEloSuffix;
            final int priority = SpawnArena.settings == null ? 1000 : SpawnArena.settings.topEloSuffixPriority;
            final long now = System.currentTimeMillis();
            boolean changed = false;

            // Each arena's #1 now; a new one holds it from now
            final Map<String, String> suffixOf = new HashMap<>();
            for (final Arena arena : SpawnArena.arenas) {
                final String name = arena.getArenaName().toLowerCase(java.util.Locale.ROOT);
                final List<StatsManager.LeaderboardEntry> top = SpawnArena.statsManager.getTopPlayers(name, "elo", 1);
                final UUID first = top.isEmpty() ? null : top.get(0).uuid();
                final Holder was = HOLDERS.get(name);
                if (first == null) {
                    changed |= HOLDERS.remove(name) != null;
                } else if (was == null || !was.uuid().equals(first)) {
                    HOLDERS.put(name, new Holder(first, now));
                    changed = true;
                }
                suffixOf.put(name, format.replace("%arena%", Arena.displayName(arena.getArenaName())));
            }
            HOLDERS.keySet().removeIf(name -> !suffixOf.containsKey(name)); // arenas deleted since

            // What each player should have: the arena they became #1 in first
            final Map<UUID, String> wanted = new HashMap<>();
            final Map<UUID, Long> earliest = new HashMap<>();
            if (!format.isEmpty())
                for (final Map.Entry<String, Holder> e : HOLDERS.entrySet()) {
                    final Holder h = e.getValue();
                    final Long best = earliest.get(h.uuid());
                    if (best == null || h.since() < best) {
                        earliest.put(h.uuid(), h.since());
                        wanted.put(h.uuid(), suffixOf.get(e.getKey()));
                    }
                }

            TAGS.keySet().retainAll(wanted.keySet());
            TAGS.putAll(wanted);

            // As a LuckPerms suffix too, only if asked for (topEloLuckPermsSuffix): otherwise any given before come off
            final boolean asSuffix = SpawnArena.settings != null && SpawnArena.settings.topEloLuckPermsSuffix && luckPermsPresent();
            if (!asSuffix)
                wanted.clear();
            if (!luckPermsPresent()) {
                if (changed)
                    save();
                return;
            }
            final java.util.Set<UUID> everyone = new java.util.HashSet<>(GIVEN.keySet());
            everyone.addAll(wanted.keySet());
            for (final UUID uuid : everyone) {
                final String had = GIVEN.get(uuid), want = wanted.get(uuid);
                if (java.util.Objects.equals(had, want))
                    continue;
                LuckPermsHook.set(uuid, had, want, priority);
                if (want == null)
                    GIVEN.remove(uuid);
                else
                    GIVEN.put(uuid, want);
                changed = true;
            }
            if (changed)
                save();
        } catch (final Throwable t) {
            if (!warned) {
                warned = true;
                SpawnArena.INSTANCE.getLogger().log(Level.WARNING, "Couldn't update the #1 Elo suffixes (will keep trying quietly)", t);
            }
        }
    }

    private static boolean luckPermsPresent() {
        return Bukkit.getPluginManager().isPluginEnabled("LuckPerms");
    }

    private static void load() {
        if (loaded)
            return;
        loaded = true;
        if (!FILE.exists())
            return;
        final YamlConfiguration yml = YamlConfiguration.loadConfiguration(FILE);
        final org.bukkit.configuration.ConfigurationSection holders = yml.getConfigurationSection("holders");
        if (holders != null)
            for (final String arena : holders.getKeys(false))
                HOLDERS.put(arena, new Holder(UUID.fromString(holders.getString(arena + ".uuid")), holders.getLong(arena + ".since")));
        final org.bukkit.configuration.ConfigurationSection given = yml.getConfigurationSection("given");
        if (given != null)
            for (final String uuid : given.getKeys(false))
                GIVEN.put(UUID.fromString(uuid), given.getString(uuid));
    }

    private static void save() {
        final YamlConfiguration yml = new YamlConfiguration();
        for (final Map.Entry<String, Holder> e : HOLDERS.entrySet()) {
            yml.set("holders." + e.getKey() + ".uuid", e.getValue().uuid().toString());
            yml.set("holders." + e.getKey() + ".since", e.getValue().since());
        }
        for (final Map.Entry<UUID, String> e : GIVEN.entrySet())
            yml.set("given." + e.getKey(), e.getValue());
        try {
            yml.save(FILE);
        } catch (final java.io.IOException e) {
            SpawnArena.INSTANCE.getLogger().log(Level.WARNING, "Couldn't save top-elo.yml", e);
        }
    }

    /** Everything that touches LuckPerms' classes, only loaded once LuckPerms is known to be there. */
    private static final class LuckPermsHook {
        static void set(final UUID uuid, final String remove, final String add, final int priority) {
            final net.luckperms.api.LuckPerms lp = net.luckperms.api.LuckPermsProvider.get();
            lp.getUserManager().modifyUser(uuid, user -> {
                if (remove != null)
                    user.data().clear(node -> node instanceof net.luckperms.api.node.types.SuffixNode s
                            && s.getPriority() == priority && s.getMetaValue().equals(remove));
                if (add != null)
                    user.data().add(net.luckperms.api.node.types.SuffixNode.builder(add, priority).build());
            });
        }
    }
}
