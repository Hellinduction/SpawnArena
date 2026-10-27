package me.zeronull.spawnarena;

import com.sk89q.worldedit.math.BlockVector3;
import de.oliver.fancyholograms.api.FancyHologramsPlugin;
import de.oliver.fancyholograms.api.HologramManager;
import de.oliver.fancyholograms.api.data.HologramData;
import de.oliver.fancyholograms.api.data.TextHologramData;
import de.oliver.fancyholograms.api.hologram.Hologram;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A MULTI crystal arena's leaderboard holograms (see {@link LeaderboardHolograms}) standing in its copied area, copied
 * into each copy at the same spot. Never saved; kept showing what the real ones show (their lines are placeholders that
 * update by themselves, so only a changed layout needs passing on); gone when the copies are retired or the server stops.
 * Only touch this class after checking FancyHolograms is enabled: it links against its API.
 */
final class CrystalCopyHolograms {
    /** Copy world -> (its copy's name -> the real hologram's name) */
    private static final Map<UUID, Map<String, String>> MADE = new ConcurrentHashMap<>();
    private static boolean syncing;

    private CrystalCopyHolograms() {}

    private static String copyName(final World world, final String original) {
        return "sa_copy_" + world.getName() + "_" + original;
    }

    /** Copies the arena's leaderboard holograms in the box (in {@code from}) into {@code to}, replacing earlier copies. */
    static void copy(final String arena, final World from, final World to, final BlockVector3 min, final BlockVector3 max) {
        removeFrom(to);
        final HologramManager manager = FancyHologramsPlugin.get().getHologramManager();
        final String prefix = LeaderboardHolograms.NAME_PREFIX + arena.toLowerCase(Locale.ROOT) + "_";
        final Map<String, String> made = new HashMap<>();
        for (final Hologram h : new ArrayList<>(manager.getHolograms())) {
            final HologramData d = h.getData();
            final Location at = d.getLocation();
            if (!d.getName().startsWith(prefix) || at == null || !from.equals(at.getWorld()) || !inside(at, min, max))
                continue;
            final String name = copyName(to, d.getName());
            final HologramData copy = d.copy(name);
            final Location there = at.clone();
            there.setWorld(to);
            copy.setLocation(there);
            copy.setPersistent(false);
            try {
                final Hologram made1 = manager.create(copy);
                manager.addHologram(made1);
                made.put(name, d.getName());
            } catch (final RuntimeException e) {
                SpawnArena.INSTANCE.getLogger().warning("Couldn't copy hologram " + d.getName() + " into " + to.getName() + ": " + e);
            }
        }
        if (!made.isEmpty()) {
            MADE.put(to.getUID(), made);
            SpawnArena.INSTANCE.getLogger().info("Copied " + made.size() + " hologram(s) into " + to.getName());
        }
        startSyncing();
    }

    private static boolean inside(final Location at, final BlockVector3 min, final BlockVector3 max) {
        return at.getBlockX() >= min.x() && at.getBlockX() <= max.x() && at.getBlockY() >= min.y() && at.getBlockY() <= max.y()
                && at.getBlockZ() >= min.z() && at.getBlockZ() <= max.z();
    }

    /** Every 10 seconds: a copy whose real hologram's lines changed (/arenaleaderboard update) gets them too. */
    private static synchronized void startSyncing() {
        if (syncing)
            return;
        syncing = true;
        Threads.globalTimer(task -> {
            if (MADE.isEmpty())
                return;
            final HologramManager manager = FancyHologramsPlugin.get().getHologramManager();
            for (final Map<String, String> made : MADE.values())
                made.forEach((copyName, realName) -> {
                    final Optional<Hologram> copy = manager.getHologram(copyName), real = manager.getHologram(realName);
                    if (copy.isEmpty() || real.isEmpty())
                        return;
                    if (copy.get().getData() instanceof TextHologramData c && real.get().getData() instanceof TextHologramData r
                            && !c.getText().equals(r.getText())) {
                        c.setText(new ArrayList<>(r.getText()));
                        copy.get().forceUpdate();
                        copy.get().refreshForViewers();
                    }
                });
        }, 200L, 200L);
    }

    static void removeFrom(final World world) {
        final Map<String, String> made = MADE.remove(world.getUID());
        if (made == null)
            return;
        final HologramManager manager = FancyHologramsPlugin.get().getHologramManager();
        for (final String name : made.keySet())
            manager.getHologram(name).ifPresent(manager::removeHologram);
    }

    static void removeAll() {
        for (final UUID id : new ArrayList<>(MADE.keySet())) {
            final World world = org.bukkit.Bukkit.getWorld(id);
            if (world != null)
                removeFrom(world);
        }
        MADE.clear();
    }
}
