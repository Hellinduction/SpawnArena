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
 * MULTI crystal and spleef arenas: each match is fought in a copy of the whole arena, in a world of its own
 * ("spawnarena_<arena>_<n>", empty apart from the copy) at the very same coordinates, so everything about the arena
 * (its WorldGuard regions, spawns, schematic, clean-up) works the same there. Each world gets the Spawn world's
 * difficulty, PvP, game rules, time and weather, and the WorldGuard regions around the arena. The number of copies is
 * crystalMultiMatches.
 *
 * <p>Folia: everything here is done on the global thread (game rules, time, weather and the world border are the global
 * region's), and the arena is copied in a chunk at a time on each chunk's own thread (see {@link RegionArenaEdits}). Plain
 * Folia can't create worlds while running: there the Worlds plugin makes them (see {@link WorldsBridge}), or without it
 * they must be made beforehand (named as above, any terrain: the copied box is emptied first). Either way they're waited
 * for for up to a minute. Forks that can create worlds (Canvas and those built on it) get them made as on Paper.
 */
public final class CrystalCopies {
    /** Real arena (lower-case name) -> its copies, 1 first. */
    private static final Map<String, List<CrystalCopy>> COPIES = new ConcurrentHashMap<>();
    /** World -> the copy in it. */
    private static final Map<UUID, CrystalCopy> BY_WORLD = new ConcurrentHashMap<>();

    private CrystalCopies() {}

    /** How long missing worlds are waited for where they can't be made (plain Folia): checked every second. */
    private static final int WAIT_SECONDS = 60;
    /** False once this server has refused to create a world (plain Folia): from then on the worlds are only waited for. */
    private static volatile boolean canCreateWorlds = true;
    /** Real arena (lower-case name) -> copies whose worlds are still being waited for (plain Folia), by number. */
    private static final Map<String, Set<Integer>> WAITING = new ConcurrentHashMap<>();

    public static boolean supported() {
        return Arena.ArenaUtils.WORLD_EDIT_SUPPORT;
    }

    /** The copies of this arena whose worlds aren't there yet and are being waited for (plain Folia), by number. */
    public static Set<Integer> waitingFor(final Arena real) {
        final Set<Integer> waiting = WAITING.get(real.getArenaName().toLowerCase(Locale.ROOT));
        return waiting == null ? Set.of() : Set.copyOf(waiting);
    }

    /** Whether this server makes the copies' worlds itself (false on plain Folia, once it's refused to). */
    public static boolean canCreateWorlds() {
        return canCreateWorlds;
    }

    /** The copy whose world this is, or null. */
    public static CrystalCopy at(final World world) {
        return world == null || BY_WORLD.isEmpty() ? null : BY_WORLD.get(world.getUID());
    }

    /** This real arena's copies (none if it isn't a MULTI crystal or spleef arena). */
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

    public static String worldName(final Arena real, final int index) {
        return "spawnarena_" + real.getArenaName().toLowerCase(Locale.ROOT) + "_" + index;
    }

    /** On start: every MULTI crystal or spleef arena gets its copies (the arena copied over only into worlds that are new). */
    public static void startAll() {
        if (!supported() || SpawnArena.arenas == null)
            return;
        for (final Arena arena : SpawnArena.arenas)
            if (arena instanceof SavableArena real && real.usesCopies() && real.getArenaMode() == ArenaMode.MULTI) {
                // A free-for-all runs in the arena itself: no copies, nor a viewing arena that would be put back under it
                if (real.isFfa())
                    SpawnArena.INSTANCE.getLogger().warning(real.getArenaName() + " is set to both MULTI and free-for-all: it runs as a "
                            + "free-for-all, without copies. Turn one of them off with /togglearenamode.");
                else
                    setUp(real, false);
            }
    }

    /**
     * Makes sure this arena has its crystalMultiMatches copies, and (with {@code recopy}, or for a world that's new)
     * copies the arena as it is on spawn into them. Completes once every copy is ready.
     */
    public static CompletableFuture<Void> setUp(final SavableArena real, final boolean recopy) {
        return setUp(real, recopy, 0);
    }

    /** {@code attempt} 1 and up: the worlds still missing are looked for again (only those: the rest are set up already). */
    private static CompletableFuture<Void> setUp(final SavableArena real, final boolean recopy, final int attempt) {
        if (!supported())
            return CompletableFuture.completedFuture(null);
        // Folia: on the global thread (on starting, once the server ticks: after any worlds other plugins make)
        if (Threads.FOLIA && !Bukkit.isGlobalTickThread()) {
            final CompletableFuture<Void> done = new CompletableFuture<>();
            Threads.global(() -> setUp(real, recopy, attempt).whenComplete((ok, error) -> {
                if (error != null)
                    done.completeExceptionally(error);
                else
                    done.complete(null);
            }));
            return done;
        }
        final World spawn = real.spawnPoint1 == null ? null : real.spawnPoint1.getWorld();
        if (spawn == null)
            return CompletableFuture.completedFuture(null);
        final int wanted = Math.max(1, SpawnArena.settings == null ? 3 : SpawnArena.settings.crystalMultiMatches);
        final List<CrystalCopy> copies = new ArrayList<>(of(real));
        final List<CompletableFuture<Void>> work = new ArrayList<>();
        final Set<Integer> missing = new TreeSet<>();
        for (int i = 1; i <= wanted; i++) {
            final int index = i;
            if (copies.stream().anyMatch(c -> c.index() == index)) {
                if (attempt > 0)
                    continue; // set up on the first go: only the missing ones are looked for again
                final CrystalCopy existing = copies.stream().filter(c -> c.index() == index).findFirst().orElseThrow();
                existing.sync();
                // (SpawnArena turned off and on again in the same run keeps its copies: one whose match was cut off is
                // copied in again here, as it would be after a restart)
                final boolean leftOver = existing.getFights().isEmpty() && CrystalCopy.leftInUse(existing.world());
                if (leftOver)
                    SpawnArena.INSTANCE.getLogger().info("A match in " + existing.world().getName() + " was never put back: copying the arena in again.");
                if ((recopy || leftOver) && existing.getFights().isEmpty())
                    work.add(copyArena(real, spawn, existing.world(), true).thenRun(existing::markClean));
                decorate(real, spawn, existing.world());
                continue;
            }
            final String name = worldName(real, index);
            final boolean isNew = Bukkit.getWorld(name) == null && !new java.io.File(Bukkit.getWorldContainer(), name).exists();
            final World world = createWorld(name, spawn, spawn.getBiome(real.spawnPoint1));
            if (world == null) {
                if (!canCreateWorlds)
                    missing.add(index);
                continue;
            }
            matchSettings(world, spawn);
            try {
                copyRegions(real, spawn, world);
            } catch (final RuntimeException e) {
                SpawnArena.INSTANCE.getLogger().log(Level.WARNING, "Couldn't copy the WorldGuard regions into " + name, e);
            }
            final CrystalCopy copy = new CrystalCopy(real, world, index);
            copies.add(copy);
            BY_WORLD.put(world.getUID(), copy);
            final boolean leftOver = !isNew && CrystalCopy.leftInUse(world);
            if (leftOver)
                SpawnArena.INSTANCE.getLogger().info("A match in " + name + " was never put back: copying the arena in again.");
            // A world made beforehand by someone else (plain Folia) has nothing of ours in it yet
            final boolean neverCopied = !isNew && lastBox(world) == null;
            if (recopy || isNew || leftOver || neverCopied) {
                copy.setResetting(true); // no match in it until the arena is
                work.add(copyArena(real, spawn, world, !isNew).whenComplete((ok, error) -> {
                    copy.markClean();
                    copy.setResetting(false);
                    Threads.global(() -> real.queue.tryStartFight());
                }));
            }
            decorate(real, spawn, world);
        }
        copies.sort(Comparator.comparingInt(CrystalCopy::index));
        final String key = real.getArenaName().toLowerCase(Locale.ROOT);
        COPIES.put(key, List.copyOf(copies));
        // Worlds this server can't make: waited for (another plugin may still be loading them), then given up on
        if (missing.isEmpty())
            WAITING.remove(key);
        else if (attempt < WAIT_SECONDS) {
            WAITING.put(key, Set.copyOf(missing));
            if (attempt == 0 && !WorldsBridge.available())
                SpawnArena.INSTANCE.getLogger().info("Waiting for world(s) " + names(real, missing) + " for " + real.getArenaName()
                        + "'s copies: this server can't create worlds, so they must be made beforehand, or install the Worlds plugin.");
            final CompletableFuture<Void> later = new CompletableFuture<>();
            Threads.globalLater(() -> {
                if (!WAITING.containsKey(key)) { // retired meanwhile
                    later.complete(null);
                    return;
                }
                setUp(real, recopy, attempt + 1).whenComplete((ok, error) -> later.complete(null));
            }, 20L);
            work.add(later);
        } else {
            WAITING.remove(key);
            SpawnArena.INSTANCE.getLogger().warning("Gave up waiting for world(s) " + names(real, missing) + ": " + real.getArenaName()
                    + " runs with " + copies.size() + " cop" + (copies.size() == 1 ? "y" : "ies") + ". Make them (empty or not), then "
                    + "/crystalcopies setup " + real.getArenaName() + " or restart.");
        }
        // The server's combat logger mustn't treat the copies as somewhere new (or tag fighters going in and out of them)
        final List<String> names = new ArrayList<>();
        for (final CrystalCopy copy : copies)
            names.add(copy.world().getName());
        CombatLoggerWorlds.include(names);
        return CompletableFuture.allOf(work.toArray(new CompletableFuture[0]));
    }

    private static String names(final Arena real, final Set<Integer> indexes) {
        final List<String> names = new ArrayList<>();
        for (final int index : indexes)
            names.add(worldName(real, index));
        return String.join(", ", names);
    }

    /**
     * Copies the real arena into this copy again, as when it was first made (/crystalcopies reset): no match starts in
     * it meanwhile. Fails if a match is being fought in it or it's being copied or put back already.
     */
    public static CompletableFuture<Void> recopy(final CrystalCopy copy) {
        final World spawn = copy.real().spawnPoint1 == null ? null : copy.real().spawnPoint1.getWorld();
        if (spawn == null)
            return CompletableFuture.failedFuture(new IllegalStateException(copy.real().getArenaName() + " has no spawn points"));
        // Under the real arena's lock, as a new match picking a free copy is (ArenaQueue#target)
        synchronized (copy.real()) {
            if (!copy.getFights().isEmpty() || copy.isResetting())
                return CompletableFuture.failedFuture(new IllegalStateException("a match is being fought in it, or it's being put back"));
            copy.setResetting(true);
        }
        return copyArena(copy.real(), spawn, copy.world(), true).whenComplete((ok, error) -> {
            copy.markClean();
            copy.setResetting(false);
            Threads.global(() -> copy.real().queue.tryStartFight());
        });
    }

    /** The real arena's settings changed (reloaded, set up again): its copies take them. */
    public static void sync(final Arena real) {
        for (final CrystalCopy copy : of(real))
            copy.sync();
    }

    /** No longer MULTI (or deleted): its copies aren't used for new matches. Their worlds stay for next time. */
    public static void retire(final Arena real) {
        WAITING.remove(real.getArenaName().toLowerCase(Locale.ROOT));
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
        if (!canCreateWorlds)
            return makeThroughWorlds(name, biome); // plain Folia
        try {
            return new WorldCreator(name)
                    .environment(like.getEnvironment())
                    .generator(new Empty())
                    .biomeProvider(new OneBiome(biome)) // the copied box gets the real biomes; the rest, the arena's
                    .generateStructures(false)
                    .keepSpawnLoaded(net.kyori.adventure.util.TriState.FALSE) // nothing's around its spawn: don't hold the server up loading it
                    .createWorld();
        } catch (final UnsupportedOperationException e) {
            // Plain Folia: no new worlds while it runs (Canvas and forks of it can)
            canCreateWorlds = false;
            return makeThroughWorlds(name, biome);
        } catch (final RuntimeException e) {
            SpawnArena.INSTANCE.getLogger().log(Level.SEVERE, "Couldn't create world " + name + " for a crystal arena copy", e);
            return null;
        }
    }

    /** Worlds being made through the Worlds plugin (plain Folia), and those it couldn't make (not tried again). */
    private static final Set<String> MAKING = ConcurrentHashMap.newKeySet(), COULDNT_MAKE = ConcurrentHashMap.newKeySet();

    /**
     * Plain Folia: has the Worlds plugin make the world (or load it, if its folder is there), which it can do while the
     * server runs. Null for now: it's picked up on a later look (see setUp's waiting) once it's there.
     */
    private static World makeThroughWorlds(final String name, final org.bukkit.block.Biome biome) {
        if (!WorldsBridge.available() || COULDNT_MAKE.contains(name) || !MAKING.add(name))
            return null;
        SpawnArena.INSTANCE.getLogger().info("Making world " + name + " through the Worlds plugin (the server can't make it itself)...");
        WorldsBridge.create(name, new Empty(), new OneBiome(biome)).whenComplete((world, error) -> {
            MAKING.remove(name);
            if (error != null) {
                COULDNT_MAKE.add(name);
                SpawnArena.INSTANCE.getLogger().log(Level.SEVERE, "The Worlds plugin couldn't make world " + name + " for a crystal arena copy", error);
            }
        });
        return null;
    }

    /** Whether worlds this server can't make itself are made through the Worlds plugin instead. */
    public static boolean viaWorldsPlugin() {
        return !canCreateWorlds && WorldsBridge.available();
    }

    /** Nothing at all: the arena is copied in. */
    private static final class Empty extends org.bukkit.generator.ChunkGenerator {
        /**
         * A spawn point given, so the server doesn't search the empty world for ground to put it on (seconds of loading
         * chunks while the world is made, holding up the global thread on Folia). It's set to Spawn's right after anyway.
         */
        @Override
        public org.bukkit.Location getFixedSpawnLocation(final World world, final java.util.Random random) {
            return new org.bukkit.Location(world, 0.5, 100, 0.5);
        }
    }

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
        return box(real, spawn, true);
    }

    /** With {@code useCopyRegion} false: the box without the &lt;arena&gt;_copy region (the region and its margin). */
    private static BlockVector3[] box(final SavableArena real, final World spawn, final boolean useCopyRegion) {
        final RegionManager manager = Arena.ArenaUtils.REGION_CONTAINER.get(BukkitAdapter.adapt(spawn));
        if (manager == null)
            return null;
        final int lowest = spawn.getMinHeight(), highest = spawn.getMaxHeight() - 1;
        BlockVector3 min, max;
        final ProtectedRegion area = useCopyRegion ? manager.getRegion(real.getArenaName() + "_copy") : null;
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

    /**
     * Copies the arena into the copy's world. Into a world that already had a copy, what the last copy covered is
     * emptied first: a smaller box this time (an &lt;arena&gt;_copy region added since) mustn't leave the rest of the old
     * one standing. A world copied into before its box was remembered is emptied as far as the old way could reach (the
     * region and its margin).
     */
    private static CompletableFuture<Void> copyArena(final SavableArena real, final World spawn, final World world, final boolean hadCopy) {
        final BlockVector3[] box = box(real, spawn);
        if (box == null) {
            SpawnArena.INSTANCE.getLogger().warning("Arena " + real.getArenaName() + " has no WorldGuard region, so it can't be copied.");
            return CompletableFuture.completedFuture(null);
        }
        BlockVector3[] old = hadCopy ? lastBox(world) : null;
        if (hadCopy && old == null)
            old = box(real, spawn, false);
        final BlockVector3[] clear = old;
        SpawnArena.INSTANCE.getLogger().info("Copying arena " + real.getArenaName() + " into " + world.getName() + "...");
        return WorldEditUtils.copyBox(spawn, world, box[0], box[1], clear == null ? null : clear[0], clear == null ? null : clear[1])
                .thenRun(() -> rememberBox(world, box));
    }

    private static java.io.File boxFile(final World world) {
        return new java.io.File(world.getWorldFolder(), "spawnarena-copied-box.txt");
    }

    /** The box last copied into this world ("x y z x y z"), or null if it isn't known. */
    private static BlockVector3[] lastBox(final World world) {
        try {
            final java.io.File file = boxFile(world);
            if (!file.isFile())
                return null;
            final String[] n = java.nio.file.Files.readString(file.toPath()).trim().split("\\s+");
            return new BlockVector3[]{
                    BlockVector3.at(Integer.parseInt(n[0]), Integer.parseInt(n[1]), Integer.parseInt(n[2])),
                    BlockVector3.at(Integer.parseInt(n[3]), Integer.parseInt(n[4]), Integer.parseInt(n[5]))};
        } catch (final Exception e) {
            return null;
        }
    }

    private static void rememberBox(final World world, final BlockVector3[] box) {
        try {
            java.nio.file.Files.writeString(boxFile(world).toPath(), box[0].x() + " " + box[0].y() + " " + box[0].z() + " "
                    + box[1].x() + " " + box[1].y() + " " + box[1].z());
        } catch (final Exception e) {
            SpawnArena.INSTANCE.getLogger().warning("Couldn't note the box copied into " + world.getName() + ": " + e);
        }
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
