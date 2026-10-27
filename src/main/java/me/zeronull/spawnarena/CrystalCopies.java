package me.zeronull.spawnarena;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.WorldCreator;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * MULTI crystal arenas: each match is fought in a copy of the whole arena, in a world of its own
 * ("spawnarena_<arena>_<n>", empty apart from the copy) at the very same coordinates, so everything about the arena
 * (its WorldGuard regions, spawns, schematic, clean-up) works the same there. Each world gets the Spawn world's
 * difficulty, PvP, game rules, time and weather, and the WorldGuard regions around the arena. The number of copies is
 * crystalMultiMatches. Not on Folia, which can't create worlds while running.
 */
public final class CrystalCopies {
    /** Real arena (lower-case name) -> its copies, 1 first. */
    private static final Map<String, List<CrystalCopy>> COPIES = new ConcurrentHashMap<>();
    /** World -> the copy in it. */
    private static final Map<UUID, CrystalCopy> BY_WORLD = new ConcurrentHashMap<>();

    private CrystalCopies() {}

    public static boolean supported() {
        return !Threads.FOLIA && Arena.ArenaUtils.WORLD_EDIT_SUPPORT;
    }

    /** The copy whose world this is, or null. */
    public static CrystalCopy at(final World world) {
        return world == null || BY_WORLD.isEmpty() ? null : BY_WORLD.get(world.getUID());
    }

    /** This real arena's copies (none if it isn't a MULTI crystal arena). */
    public static List<CrystalCopy> of(final Arena real) {
        final List<CrystalCopy> copies = COPIES.get(real.getArenaName().toLowerCase(Locale.ROOT));
        return copies == null ? List.of() : copies;
    }

    public static Collection<CrystalCopy> all() {
        return BY_WORLD.values();
    }

    /** A copy of this arena that's free for a new match (nobody in it, not being reset), or null. */
    public static CrystalCopy free(final Arena real) {
        for (final CrystalCopy copy : of(real))
            if (copy.getFights().isEmpty() && !copy.isResetting())
                return copy;
        return null;
    }

    private static String worldName(final Arena real, final int index) {
        return "spawnarena_" + real.getArenaName().toLowerCase(Locale.ROOT) + "_" + index;
    }

    /** On start: every MULTI crystal arena gets its copies (the arena copied over only into worlds that are new). */
    public static void startAll() {
        if (!supported() || SpawnArena.arenas == null)
            return;
        for (final Arena arena : SpawnArena.arenas)
            if (arena instanceof SavableArena real && real.isCrystalPvp() && real.getArenaMode() == ArenaMode.MULTI)
                setUp(real, false);
    }

    /**
     * Makes sure this arena has its crystalMultiMatches copies, and (with {@code recopy}, or for a world that's new)
     * copies the arena as it is on spawn into them. Completes once every copy is ready.
     */
    public static CompletableFuture<Void> setUp(final SavableArena real, final boolean recopy) {
        if (!supported())
            return CompletableFuture.completedFuture(null);
        final World spawn = real.spawnPoint1 == null ? null : real.spawnPoint1.getWorld();
        if (spawn == null)
            return CompletableFuture.completedFuture(null);
        final int wanted = Math.max(1, SpawnArena.settings == null ? 3 : SpawnArena.settings.crystalMultiMatches);
        final List<CrystalCopy> copies = new ArrayList<>(of(real));
        final List<CompletableFuture<Void>> work = new ArrayList<>();
        for (int i = 1; i <= wanted; i++) {
            final int index = i;
            if (copies.stream().anyMatch(c -> c.index() == index)) {
                final CrystalCopy existing = copies.stream().filter(c -> c.index() == index).findFirst().orElseThrow();
                existing.sync();
                if (recopy && existing.getFights().isEmpty())
                    work.add(copyArena(real, spawn, existing.world()));
                decorate(real, spawn, existing.world());
                continue;
            }
            final String name = worldName(real, index);
            final boolean isNew = Bukkit.getWorld(name) == null && !new java.io.File(Bukkit.getWorldContainer(), name).exists();
            final World world = createWorld(name, spawn, spawn.getBiome(real.spawnPoint1));
            if (world == null)
                continue;
            matchSettings(world, spawn);
            try {
                copyRegions(real, spawn, world);
            } catch (final RuntimeException e) {
                SpawnArena.INSTANCE.getLogger().log(Level.WARNING, "Couldn't copy the WorldGuard regions into " + name, e);
            }
            final CrystalCopy copy = new CrystalCopy(real, world, index);
            copies.add(copy);
            BY_WORLD.put(world.getUID(), copy);
            if (recopy || isNew)
                work.add(copyArena(real, spawn, world));
            decorate(real, spawn, world);
        }
        copies.sort(Comparator.comparingInt(CrystalCopy::index));
        COPIES.put(real.getArenaName().toLowerCase(Locale.ROOT), List.copyOf(copies));
        return CompletableFuture.allOf(work.toArray(new CompletableFuture[0]));
    }

    /** The real arena's settings changed (reloaded, set up again): its copies take them. */
    public static void sync(final Arena real) {
        for (final CrystalCopy copy : of(real))
            copy.sync();
    }

    /** No longer MULTI (or deleted): its copies aren't used for new matches. Their worlds stay for next time. */
    public static void retire(final Arena real) {
        final List<CrystalCopy> copies = COPIES.remove(real.getArenaName().toLowerCase(Locale.ROOT));
        if (copies != null)
            for (final CrystalCopy copy : copies) {
                BY_WORLD.remove(copy.world().getUID());
                undecorate(copy.world());
            }
    }

    /**
     * The NPCs and this arena's leaderboard holograms in the copied area, copied into a copy (never saved: done again
     * after every start). A few seconds later, once FancyNpcs and FancyHolograms have loaded theirs.
     */
    private static void decorate(final SavableArena real, final World spawn, final World world) {
        final BlockVector3[] box = box(real, spawn);
        if (box == null)
            return;
        Threads.globalLater(() -> {
            if (!BY_WORLD.containsKey(world.getUID()))
                return; // retired meanwhile
            try {
                if (Bukkit.getPluginManager().isPluginEnabled("FancyNpcs"))
                    CrystalCopyNpcs.copy(spawn, world, box[0], box[1]);
            } catch (final LinkageError | RuntimeException e) {
                SpawnArena.INSTANCE.getLogger().warning("Couldn't copy NPCs into " + world.getName() + ": " + e);
            }
            try {
                if (Bukkit.getPluginManager().isPluginEnabled("FancyHolograms"))
                    CrystalCopyHolograms.copy(real.getArenaName(), spawn, world, box[0], box[1]);
            } catch (final LinkageError | RuntimeException e) {
                SpawnArena.INSTANCE.getLogger().warning("Couldn't copy holograms into " + world.getName() + ": " + e);
            }
        }, 100L);
    }

    private static void undecorate(final World world) {
        try {
            if (Bukkit.getPluginManager().isPluginEnabled("FancyNpcs"))
                CrystalCopyNpcs.removeFrom(world);
        } catch (final LinkageError | RuntimeException ignored) {
            // FancyNpcs gone: so are its NPCs
        }
        try {
            if (Bukkit.getPluginManager().isPluginEnabled("FancyHolograms"))
                CrystalCopyHolograms.removeFrom(world);
        } catch (final LinkageError | RuntimeException ignored) {
            // FancyHolograms gone: so are its holograms
        }
    }

    /** On shutdown: the copied NPCs and holograms go (they're never saved anyway). */
    public static void undecorateAll() {
        for (final CrystalCopy copy : BY_WORLD.values())
            undecorate(copy.world());
    }

    // ── The worlds ──────────────────────────────────────────────────────────

    private static World createWorld(final String name, final World like, final org.bukkit.block.Biome biome) {
        final World existing = Bukkit.getWorld(name);
        if (existing != null)
            return existing;
        try {
            return new WorldCreator(name)
                    .environment(like.getEnvironment())
                    .generator(new Empty())
                    .biomeProvider(new OneBiome(biome)) // the copied box gets the real biomes; the rest, the arena's
                    .generateStructures(false)
                    .keepSpawnLoaded(net.kyori.adventure.util.TriState.FALSE) // nothing's around its spawn: don't hold the server up loading it
                    .createWorld();
        } catch (final RuntimeException e) {
            SpawnArena.INSTANCE.getLogger().log(Level.SEVERE, "Couldn't create world " + name + " for a crystal arena copy", e);
            return null;
        }
    }

    /** Nothing at all: the arena is copied in. */
    private static final class Empty extends org.bukkit.generator.ChunkGenerator {}

    /** The same biome everywhere (the world's own seed would give every copy different grass and water colours). */
    private static final class OneBiome extends org.bukkit.generator.BiomeProvider {
        private final org.bukkit.block.Biome biome;

        OneBiome(final org.bukkit.block.Biome biome) {
            this.biome = biome;
        }

        @Override
        public org.bukkit.block.Biome getBiome(final org.bukkit.generator.WorldInfo info, final int x, final int y, final int z) {
            return this.biome;
        }

        @Override
        public java.util.List<org.bukkit.block.Biome> getBiomes(final org.bukkit.generator.WorldInfo info) {
            return java.util.List.of(this.biome);
        }
    }

    /** Everything about the Spawn world that matters to a fight. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void matchSettings(final World world, final World spawn) {
        world.setDifficulty(spawn.getDifficulty());
        world.setPVP(spawn.getPVP());
        for (final GameRule rule : GameRule.values()) {
            try {
                final Object value = spawn.getGameRuleValue(rule);
                if (value != null)
                    world.setGameRule(rule, value);
            } catch (final IllegalArgumentException ignored) {
                // a rule this version doesn't have (renamed or removed)
            }
        }
        world.setTime(spawn.getTime());
        world.setStorm(spawn.hasStorm());
        world.setThundering(spawn.isThundering());
        world.setSpawnFlags(spawn.getAllowMonsters(), spawn.getAllowAnimals());
        world.getWorldBorder().setCenter(spawn.getWorldBorder().getCenter().clone().toLocation(world));
        world.getWorldBorder().setSize(spawn.getWorldBorder().getSize());
        world.setSpawnLocation(spawn.getSpawnLocation().getBlockX(), spawn.getSpawnLocation().getBlockY(), spawn.getSpawnLocation().getBlockZ());
    }

    /**
     * The box copied: a WorldGuard region called "&lt;arena&gt;_copy" if there is one (exactly what belongs to the arena:
     * walls, deck, dome, and nothing of spawn around it), otherwise the arena's region widened by crystalCopyMargin each
     * way.
     */
    static BlockVector3[] box(final SavableArena real, final World spawn) {
        final RegionManager manager = Arena.ArenaUtils.REGION_CONTAINER.get(BukkitAdapter.adapt(spawn));
        if (manager == null)
            return null;
        final int lowest = spawn.getMinHeight(), highest = spawn.getMaxHeight() - 1;
        BlockVector3 min, max;
        final ProtectedRegion area = manager.getRegion(real.getArenaName() + "_copy");
        if (area != null) {
            min = area.getMinimumPoint();
            max = area.getMaximumPoint();
        } else {
            final ProtectedRegion region = manager.getRegion(real.getArenaName());
            if (region == null)
                return null;
            final int margin = SpawnArena.settings == null ? 32 : Math.max(0, SpawnArena.settings.crystalCopyMargin);
            min = region.getMinimumPoint().subtract(margin, margin, margin);
            max = region.getMaximumPoint().add(margin, margin, margin);
        }
        // Always the spot watchers are sent to (/arenawatch, the arena's exit) and the floor under it, so nobody
        // watching a match up close lands in the void
        final org.bukkit.Location deck = real.exitLocation;
        if (deck != null && spawn.equals(deck.getWorld())) {
            min = min.getMinimum(BlockVector3.at(deck.getBlockX() - 3, deck.getBlockY() - 3, deck.getBlockZ() - 3));
            max = max.getMaximum(BlockVector3.at(deck.getBlockX() + 3, deck.getBlockY() + 3, deck.getBlockZ() + 3));
        }
        return new BlockVector3[]{
                BlockVector3.at(min.x(), Math.max(lowest, min.y()), min.z()),
                BlockVector3.at(max.x(), Math.min(highest, max.y()), max.z())};
    }

    private static CompletableFuture<Void> copyArena(final SavableArena real, final World spawn, final World world) {
        final BlockVector3[] box = box(real, spawn);
        if (box == null) {
            SpawnArena.INSTANCE.getLogger().warning("Crystal arena " + real.getArenaName() + " has no WorldGuard region, so it can't be copied.");
            return CompletableFuture.completedFuture(null);
        }
        SpawnArena.INSTANCE.getLogger().info("Copying crystal arena " + real.getArenaName() + " into " + world.getName() + "...");
        return WorldEditUtils.copyBox(spawn, world, box[0], box[1]);
    }

    /**
     * The Spawn world's WorldGuard regions that reach into the copied box (the arena's own included, by the same name),
     * with their flags and priorities, and its global region's flags.
     */
    private static void copyRegions(final SavableArena real, final World spawn, final World world) {
        final RegionManager from = Arena.ArenaUtils.REGION_CONTAINER.get(BukkitAdapter.adapt(spawn));
        final RegionManager to = Arena.ArenaUtils.REGION_CONTAINER.get(BukkitAdapter.adapt(world));
        final BlockVector3[] box = box(real, spawn);
        if (from == null || to == null || box == null)
            return;
        final ProtectedCuboidRegion area = new ProtectedCuboidRegion("spawnarena_copy_box", box[0], box[1]);
        for (final ProtectedRegion region : from.getRegions().values()) {
            final ProtectedRegion copy;
            if (region.getId().equals(ProtectedRegion.GLOBAL_REGION)) {
                // A new world has no global region until something makes one
                ProtectedRegion global = to.getRegion(ProtectedRegion.GLOBAL_REGION);
                if (global == null) {
                    global = new com.sk89q.worldguard.protection.regions.GlobalProtectedRegion(ProtectedRegion.GLOBAL_REGION);
                    to.addRegion(global);
                }
                global.setFlags(new HashMap<>(region.getFlags()));
                continue;
            }
            if (area.getIntersectingRegions(List.of(region)).isEmpty() && !region.getId().equalsIgnoreCase(real.getArenaName()))
                continue;
            if (region instanceof ProtectedPolygonalRegion poly)
                copy = new ProtectedPolygonalRegion(poly.getId(), poly.getPoints(), poly.getMinimumPoint().y(), poly.getMaximumPoint().y());
            else
                copy = new ProtectedCuboidRegion(region.getId(), region.getMinimumPoint(), region.getMaximumPoint());
            copy.setPriority(region.getPriority());
            copy.setFlags(new HashMap<>(region.getFlags()));
            to.addRegion(copy);
        }
        try {
            to.save();
        } catch (final Exception e) {
            SpawnArena.INSTANCE.getLogger().log(Level.WARNING, "Couldn't save the WorldGuard regions of " + world.getName(), e);
        }
    }
}
