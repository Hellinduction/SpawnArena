package me.zeronull.spawnarena;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.logging.Level;

/**
 * Crystal PvP arenas: fighters can build inside the arena's region, but anything that was there when the fight
 * started (its snapshot) can't be broken, blown up or built over; spots that were empty are fair game. When the
 * fight ends, every block in the region is put back as the snapshot has it, and crystals and items are removed.
 *
 * <p>What's been placed (and which fights were running) is also written to crystal-cleanup.txt, so a crash
 * mid-fight is cleaned up on the next start.
 */
public final class CrystalPvp {
    private CrystalPvp() {}

    public record BlockKey(UUID world, int x, int y, int z) {
        public static BlockKey of(final Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }

        public static BlockKey of(final Location loc) {
            return new BlockKey(loc.getWorld().getUID(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        }

        public Block block() {
            final World world = Bukkit.getWorld(this.world);
            return world == null ? null : world.getBlockAt(this.x, this.y, this.z);
        }
    }

    /** What one fight has changed in its arena. */
    private static final class Track {
        final Fight fight;
        /** Position -> the block that was there before anything was placed, in placement order */
        final Map<BlockKey, BlockData> original = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
        final Set<UUID> crystals = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final Set<UUID> items = java.util.concurrent.ConcurrentHashMap.newKeySet();
        /** The arena's region as it was when the fight started (null until taken) */
        volatile Snapshot snapshot;

        Track(final Fight fight) {
            this.fight = fight;
        }
    }

    /** Every block in the box around an arena's region, as it was when a fight started. */
    static final class Snapshot {
        final UUID world;
        final int minX, minY, minZ, sizeX, sizeY, sizeZ;
        final BlockData[] data;

        Snapshot(final World world, final int minX, final int minY, final int minZ, final int maxX, final int maxY, final int maxZ) {
            this.world = world.getUID();
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.sizeX = maxX - minX + 1;
            this.sizeY = maxY - minY + 1;
            this.sizeZ = maxZ - minZ + 1;
            this.data = new BlockData[this.sizeX * this.sizeY * this.sizeZ];
        }

        /** Records the columns from (x0, z0) to (x1, z1) from that chunk's frozen copy. Any thread. */
        void fill(final org.bukkit.ChunkSnapshot chunk, final int x0, final int x1, final int z0, final int z1) {
            for (int x = x0; x <= x1; x++)
                for (int z = z0; z <= z1; z++)
                    for (int y = 0; y < this.sizeY; y++)
                        this.data[this.index(x - this.minX, y, z - this.minZ)] = chunk.getBlockData(x & 15, this.minY + y, z & 15);
        }

        /**
         * The box split into chunk-sized pieces {x0, x1, z0, z1}: each is handled on the thread that owns it,
         * since on Folia an arena can span more than one region.
         */
        List<int[]> chunkPieces() {
            final List<int[]> pieces = new ArrayList<>();
            final int maxX = this.minX + this.sizeX - 1, maxZ = this.minZ + this.sizeZ - 1;
            for (int cx = this.minX >> 4; cx <= maxX >> 4; cx++)
                for (int cz = this.minZ >> 4; cz <= maxZ >> 4; cz++)
                    pieces.add(new int[]{Math.max(this.minX, cx << 4), Math.min(maxX, (cx << 4) + 15),
                            Math.max(this.minZ, cz << 4), Math.min(maxZ, (cz << 4) + 15)});
            return pieces;
        }

        private int index(final int x, final int y, final int z) {
            return (y * this.sizeZ + z) * this.sizeX + x;
        }

        /** What was at this spot when the fight started, or null if it's outside the snapshot. */
        BlockData at(final Block block) {
            if (!block.getWorld().getUID().equals(this.world))
                return null;
            final int x = block.getX() - this.minX, y = block.getY() - this.minY, z = block.getZ() - this.minZ;
            if (x < 0 || y < 0 || z < 0 || x >= this.sizeX || y >= this.sizeY || z >= this.sizeZ)
                return null;
            return this.data[this.index(x, y, z)];
        }
    }

    /**
     * Each arena's last good snapshot, as a fallback if taking a new one fails. Thrown away when the arena may
     * have changed since: someone built in it outside a fight, its settings were reloaded or it was set up
     * again, or it's older than SNAPSHOT_FALLBACK_MILLIS (WorldEdit edits can't be seen any other way).
     */
    private record LastSnapshot(Snapshot snapshot, long takenAt) {}
    private static final Map<String, LastSnapshot> LAST_SNAPSHOTS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long SNAPSHOT_FALLBACK_MILLIS = 30 * 60 * 1000L;

    /** Makes the next snapshot fail, once (only the test server sets this, to check the fallback works). */
    public static volatile boolean failNextSnapshot;

    /** The arena may have changed: its saved snapshot can't be used as a fallback any more. */
    public static void forgetSnapshot(final Arena arena) {
        LAST_SNAPSHOTS.remove(arena.getArenaName().toLowerCase());
    }

    /** Runs work off the server's threads (reading frozen chunk copies). */
    private static final java.util.concurrent.Executor OFF_THREAD =
            task -> Bukkit.getAsyncScheduler().runNow(SpawnArena.INSTANCE, t -> task.run());

    /**
     * Takes the snapshot when a crystal fight starts: each chunk is copied (cheaply) on the thread that owns it,
     * and the blocks are read out of those copies off the server's threads, so the server doesn't stall. The
     * fight waits for this before anyone is sent in (see Fight#startFight): true once it's ready, false if it
     * failed or took over 10 seconds (then the fight is called off). Until it's ready every block counts as part
     * of the arena anyway, so nothing can be broken in the meantime.
     */
    public static java.util.concurrent.CompletableFuture<Boolean> takeSnapshot(final Fight fight) {
        final Arena arena = fight.getArena();
        final Location anchor = arena.spawnPoint1;
        if (anchor == null || anchor.getWorld() == null || !Arena.ArenaUtils.WORLD_EDIT_SUPPORT)
            return java.util.concurrent.CompletableFuture.completedFuture(true);
        final int[] box = Regions.bounds(arena, anchor.getWorld());
        if (box == null)
            return java.util.concurrent.CompletableFuture.completedFuture(true);
        final Track track = trackFor(fight);
        markDirty(); // so a crash mid-fight is known about (see restoreLeftovers)
        final World world = anchor.getWorld();
        final Snapshot snapshot = new Snapshot(world, box[0], box[1], box[2], box[3], box[4], box[5]);
        final List<int[]> pieces = snapshot.chunkPieces();
        final List<java.util.concurrent.CompletableFuture<org.bukkit.ChunkSnapshot>> copies = new ArrayList<>();
        for (final int[] p : pieces)
            copies.add(Threads.supplyAt(new Location(world, p[0], box[1], p[2]),
                    () -> world.getChunkAt(p[0] >> 4, p[2] >> 4).getChunkSnapshot(false, false, false)));
        return java.util.concurrent.CompletableFuture.allOf(copies.toArray(new java.util.concurrent.CompletableFuture[0]))
                .thenApplyAsync(ignored -> {
                    if (failNextSnapshot) {
                        failNextSnapshot = false;
                        throw new IllegalStateException("snapshot made to fail (test)");
                    }
                    for (int i = 0; i < pieces.size(); i++) {
                        final int[] p = pieces.get(i);
                        snapshot.fill(copies.get(i).join(), p[0], p[1], p[2], p[3]);
                    }
                    track.snapshot = snapshot;
                    LAST_SNAPSHOTS.put(arena.getArenaName().toLowerCase(), new LastSnapshot(snapshot, System.currentTimeMillis()));
                    return true;
                }, OFF_THREAD)
                .completeOnTimeout(false, 10, java.util.concurrent.TimeUnit.SECONDS)
                .exceptionally(e -> {
                    SpawnArena.INSTANCE.getLogger().log(Level.SEVERE, "Couldn't record crystal arena " + arena.getArenaName(), e);
                    return false;
                })
                .thenApply(ok -> ok || useLastSnapshot(arena, track));
    }

    /** A fresh snapshot failed: falls back on the arena's last good one, if it can still be trusted. */
    private static boolean useLastSnapshot(final Arena arena, final Track track) {
        final LastSnapshot last = LAST_SNAPSHOTS.get(arena.getArenaName().toLowerCase());
        if (last == null || System.currentTimeMillis() - last.takenAt() > SNAPSHOT_FALLBACK_MILLIS)
            return false;
        track.snapshot = last.snapshot();
        SpawnArena.INSTANCE.getLogger().warning("Couldn't take a fresh snapshot of crystal arena " + arena.getArenaName()
                + "; using the one from " + ((System.currentTimeMillis() - last.takenAt()) / 60000) + " minute(s) ago.");
        return true;
    }

    /**
     * Whether this block is part of the arena itself and must not be broken, blown up or built over: anything
     * that wasn't empty when the fight started (walls, floor, bedrock), and anything outside the region.
     * Spots that were empty are fair game whatever is there now: placed blocks, water and wherever it flowed,
     * generated cobble, fire...
     */
    public static boolean isOriginal(final Arena arena, final Block block) {
        if (!arena.equals(arenaAt(block)))
            return true;
        final Track track = TRACKS.get(arena);
        final Snapshot snapshot = track == null ? null : track.snapshot;
        if (snapshot == null)
            return true;
        final BlockData before = snapshot.at(block);
        return before == null || !isEmptySpace(before.getMaterial());
    }

    /**
     * Whether a fighter may open this container: one put there during the fight (a shulker of gaps they brought, a
     * chest...), never one of the arena's own and never an ender chest. A double chest needs both halves placed.
     */
    public static boolean canOpen(final Arena arena, final org.bukkit.inventory.Inventory inventory) {
        // A crafting table has no inventory of its own: it's the table block they opened
        if (inventory.getType() == org.bukkit.event.inventory.InventoryType.WORKBENCH) {
            final Location at = inventory.getLocation();
            return at != null && at.getBlock().getType() == Material.CRAFTING_TABLE && activeFight(arena) != null
                    && !isOriginal(arena, at.getBlock());
        }
        return canOpen(arena, inventory.getHolder(false));
    }

    /** As {@link #canOpen(Arena, org.bukkit.inventory.Inventory)}, for a container's inventory holder. */
    public static boolean canOpen(final Arena arena, final org.bukkit.inventory.InventoryHolder holder) {
        if (holder instanceof org.bukkit.block.DoubleChest chest)
            return canOpen(arena, chest.getLeftSide()) && canOpen(arena, chest.getRightSide());
        return holder instanceof org.bukkit.block.Container container && container.getType() != Material.ENDER_CHEST
                && activeFight(arena) != null && !isOriginal(arena, container.getBlock());
    }

    /**
     * Whether a fighter may break this block: one that wasn't there when the fight started, or part of the arena's
     * own floor they're allowed to dig into ({@link #isDiggableFloor}).
     */
    public static boolean canBreak(final Arena arena, final Block block) {
        // In the diggable floor anything goes, dug ground or whatever was put back into a hole (it's all restored):
        // otherwise obsidian placed in a hole they dug could never be broken again
        return !isOriginal(arena, block) || (block.getType() != Material.BEDROCK && floorType(arena, block) != null);
    }

    /** Whether a fighter may place or pour into this spot: one that was empty when the fight started, or a hole they dug. */
    public static boolean canBuild(final Arena arena, final Block block) {
        return canBuild(arena, block, block.getType());
    }

    /** As {@link #canBuild(Arena, Block)}, with what's there now given (a place event already shows the new block). */
    public static boolean canBuild(final Arena arena, final Block block, final Material there) {
        return !isOriginal(arena, block) || (isEmptySpace(there) && floorType(arena, block) != null);
    }

    /**
     * Ground fighters may dig into: a block of a type in crystalDiggableBlocks (never bedrock), inside the arena's
     * region, below the spawns' feet, and under the open pit: the spot above it at the spawns' height was empty when
     * the fight started, so walls and whatever stands on the ground stay put. Dug blocks are put back with the rest.
     */
    public static boolean isDiggableFloor(final Arena arena, final Block block) {
        return block.getType() == floorType(arena, block);
    }

    /** What this spot of diggable floor was when the fight started (see {@link #isDiggableFloor}), or null if it isn't one. */
    private static Material floorType(final Arena arena, final Block block) {
        if (!arena.equals(arenaAt(block)))
            return null;
        final Integer spawnY = arena.lowestSpawnY();
        if (spawnY == null || block.getY() >= spawnY)
            return null;
        final Track track = TRACKS.get(arena);
        final Snapshot snapshot = track == null ? null : track.snapshot;
        if (snapshot == null)
            return null;
        final BlockData was = snapshot.at(block);
        if (was == null || !diggable().contains(was.getMaterial()))
            return null;
        final BlockData above = snapshot.at(block.getWorld().getBlockAt(block.getX(), spawnY, block.getZ()));
        return above != null && isEmptySpace(above.getMaterial()) ? was.getMaterial() : null;
    }

    private static volatile java.util.List<String> diggableFrom;
    private static volatile java.util.Set<Material> diggable = java.util.EnumSet.noneOf(Material.class);

    /** Whether this block type is in crystalDiggableBlocks (bedrock never is). */
    public static boolean isDiggableType(final Material type) {
        return diggable().contains(type);
    }

    /** crystalDiggableBlocks as materials, worked out again whenever the setting changes. */
    private static java.util.Set<Material> diggable() {
        final java.util.List<String> names = SpawnArena.settings == null ? java.util.List.of() : SpawnArena.settings.crystalDiggableBlocks;
        if (names != diggableFrom) {
            final java.util.Set<Material> set = java.util.EnumSet.noneOf(Material.class);
            if (names != null)
                for (final String name : names) {
                    final Material m = name == null ? null : Material.matchMaterial(name.trim());
                    if (m != null && m.isBlock() && m != Material.BEDROCK)
                        set.add(m);
                }
            diggable = set;
            diggableFrom = names;
        }
        return diggable;
    }

    private static final Map<Arena, Track> TRACKS = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * True on a thread while it's putting a crystal arena's blocks back: anything the block updates drop is
     * discarded (see CrystalArenaListener#onItemSpawn). Per thread, since on Folia areas clean up independently.
     */
    private static final ThreadLocal<Boolean> CLEANING = ThreadLocal.withInitial(() -> false);

    // ── Queries ─────────────────────────────────────────────────────────────

    /** The crystal PvP arena whose region contains this location, or null. Cheap when there are none. */
    public static Arena arenaAt(final Location loc) {
        if (loc == null || loc.getWorld() == null || SpawnArena.arenas == null || !Arena.ArenaUtils.WORLD_EDIT_SUPPORT)
            return null;
        for (final Arena arena : SpawnArena.arenas)
            if (arena.isCrystalPvp() && Regions.contains(arena, loc, 0))
                return arena;
        return null;
    }

    public static Arena arenaAt(final Block block) {
        return arenaAt(block.getLocation());
    }

    /** Whether any crystal PvP arena's region comes within {@code margin} blocks of this location. */
    public static boolean anyNear(final Location loc, final int margin) {
        if (loc == null || loc.getWorld() == null || SpawnArena.arenas == null || !Arena.ArenaUtils.WORLD_EDIT_SUPPORT)
            return false;
        for (final Arena arena : SpawnArena.arenas)
            if (arena.isCrystalPvp() && Regions.contains(arena, loc, margin))
                return true;
        return false;
    }

    /**
     * WorldGuard lookups, kept in their own class so the rest of CrystalPvp (fight cleanup, crash
     * recovery) never loads WorldGuard classes on a server without it. Only used after checking
     * {@link Arena.ArenaUtils#WORLD_EDIT_SUPPORT}.
     */
    private static final class Regions {
        /** {minX, minY, minZ, maxX, maxY, maxZ} of the arena's region in that world, or null. */
        static int[] bounds(final Arena arena, final World world) {
            final com.sk89q.worldguard.protection.managers.RegionManager manager =
                    Arena.ArenaUtils.REGION_CONTAINER.get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(world));
            final com.sk89q.worldguard.protection.regions.ProtectedRegion region =
                    manager == null ? null : manager.getRegion(arena.getArenaName());
            if (region == null)
                return null;
            final com.sk89q.worldedit.math.BlockVector3 min = region.getMinimumPoint(), max = region.getMaximumPoint();
            return new int[]{min.x(), min.y(), min.z(), max.x(), max.y(), max.z()};
        }

        /** Whether loc is inside the arena's region, or within {@code margin} blocks of its bounding box. */
        static boolean contains(final Arena arena, final Location loc, final int margin) {
            final com.sk89q.worldguard.protection.managers.RegionManager manager =
                    Arena.ArenaUtils.REGION_CONTAINER.get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(loc.getWorld()));
            final com.sk89q.worldguard.protection.regions.ProtectedRegion region =
                    manager == null ? null : manager.getRegion(arena.getArenaName());
            if (region == null)
                return false;
            if (margin == 0)
                return region.contains(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            final com.sk89q.worldedit.math.BlockVector3 min = region.getMinimumPoint(), max = region.getMaximumPoint();
            return loc.getBlockX() >= min.x() - margin && loc.getBlockX() <= max.x() + margin
                    && loc.getBlockY() >= min.y() - margin && loc.getBlockY() <= max.y() + margin
                    && loc.getBlockZ() >= min.z() - margin && loc.getBlockZ() <= max.z() + margin;
        }
    }

    /** The fight currently using this arena (counting down excluded), or null. */
    public static Fight activeFight(final Arena arena) {
        for (final Fight fight : arena.getFights())
            if (fight.getState() == FightState.IN_FIGHT || fight.getState() == FightState.PRE_FIGHT_START
                    || fight.getState() == FightState.ENDING)
                return fight;
        return null;
    }

    /** True if this player is still fighting (not eliminated) in a running fight in this arena. */
    public static boolean isActiveFighter(final Arena arena, final org.bukkit.entity.Player player) {
        final Fight fight = arena.getFight(player);
        return fight != null && fight.getState() == FightState.IN_FIGHT && fight.isFighter(player);
    }

    public static boolean isPlacedCrystal(final Arena arena, final Entity entity) {
        final Track track = TRACKS.get(arena);
        return track != null && track.crystals.contains(entity.getUniqueId());
    }

    // ── Dropped items ───────────────────────────────────────────────────────

    /** Marks items dropped in a crystal fight with the arena's name, so leftovers can always be recognised. */
    public static final org.bukkit.NamespacedKey ITEM_KEY = new org.bukkit.NamespacedKey(SpawnArena.INSTANCE, "crystal_fight_item");

    public static void recordItem(final Fight fight, final org.bukkit.entity.Item item) {
        item.getPersistentDataContainer().set(ITEM_KEY, org.bukkit.persistence.PersistentDataType.STRING, fight.getArena().getArenaName());
        trackFor(fight).items.add(item.getUniqueId());
    }

    /** The arena an item was dropped in during a crystal fight, or null if it's an ordinary item. */
    public static String matchOf(final Entity entity) {
        if (!(entity instanceof org.bukkit.entity.Item item))
            return null;
        return item.getPersistentDataContainer().get(ITEM_KEY, org.bukkit.persistence.PersistentDataType.STRING);
    }

    /** True if this is a match item from a fight that's still going (so it should stay). */
    public static boolean isLiveMatchItem(final Entity entity) {
        final String arenaName = matchOf(entity);
        if (arenaName == null || SpawnArena.arenas == null)
            return false;
        final Arena arena = SpawnArena.arenas.of(arenaName);
        final Track track = arena == null ? null : TRACKS.get(arena);
        return track != null && track.items.contains(entity.getUniqueId());
    }

    /** Removes match items whose fight is over (e.g. left behind by a crash). */
    public static void removeStaleItems(final Iterable<? extends Entity> entities) {
        for (final Entity entity : entities)
            if (matchOf(entity) != null && !isLiveMatchItem(entity))
                entity.remove();
    }

    /** Whether this thread is putting a crystal arena's blocks back right now. */
    public static boolean isCleaning(final Arena arena) {
        return CLEANING.get();
    }

    /**
     * Blocks a fighter may not place in a crystal arena: things that move, spread, push or remove
     * blocks that were there before the fight.
     */
    public static boolean isBanned(final Material type, final World world) {
        // Everything else is fine: whatever it does, it can only change spots that were empty, and those are
        // put back from the snapshot when the fight ends
        if (org.bukkit.Tag.BEDS.isTagged(type))
            return world.getEnvironment() == World.Environment.NORMAL; // they'd set spawn points; bed bombs only elsewhere
        return switch (type) {
            case PISTON, STICKY_PISTON -> true; // could push the arena's own blocks
            // Fire TNT, armor stands, boats... with a button; a hopper could empty the arena's own containers
            case DISPENSER, DROPPER, CRAFTER, HOPPER -> true;
            default -> false;
        };
    }

    /** Placing is only allowed into empty space, so nothing that was there before is ever replaced. */
    public static boolean isEmptySpace(final Material type) {
        return type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR || type == Material.LIGHT;
    }

    // ── Tracking ────────────────────────────────────────────────────────────

    private static synchronized Track trackFor(final Fight fight) {
        final Track existing = TRACKS.get(fight.getArena());
        if (existing != null && existing.fight.equals(fight))
            return existing;
        if (existing != null)
            cleanup(existing.fight); // left over from a fight that never cleaned up
        final Track track = new Track(fight);
        TRACKS.put(fight.getArena(), track);
        return track;
    }

    /** Remembers what was at this position before the fight first changed it. */
    public static void recordPlaced(final Fight fight, final Block block, final BlockData before) {
        trackFor(fight).original.putIfAbsent(BlockKey.of(block), before.clone());
        markDirty();
    }

    public static void recordCrystal(final Fight fight, final Entity crystal) {
        trackFor(fight).crystals.add(crystal.getUniqueId());
        markDirty();
    }

    /** Takes this fight's track out (once), so a new fight can't mix with what's being cleaned up. */
    private static synchronized Track take(final Fight fight) {
        final Track track = TRACKS.get(fight.getArena());
        if (track == null || !track.fight.equals(fight))
            return null;
        TRACKS.remove(fight.getArena());
        return track;
    }

    /**
     * Puts this fight's arena back the way it was. Safe to call more than once, from any thread: each
     * crystal, item and block is dealt with on the thread that owns it (immediately on Paper). Done once the
     * returned future completes (the re-paste waits for it).
     */
    public static java.util.concurrent.CompletableFuture<Void> cleanup(final Fight fight) {
        final Arena arena = fight.getArena();
        final Track track = take(fight);
        if (track == null)
            return java.util.concurrent.CompletableFuture.completedFuture(null);

        for (final UUID id : track.crystals)
            removeEntity(id);
        for (final UUID id : track.items)
            removeEntity(id);

        // Newest first, with block updates so neighbours (fences, walls...) reconnect as before
        final List<Map.Entry<BlockKey, BlockData>> entries;
        synchronized (track.original) {
            entries = new ArrayList<>(track.original.entrySet());
        }
        for (int i = entries.size() - 1; i >= 0; i--) {
            final Block block = entries.get(i).getKey().block();
            final BlockData data = entries.get(i).getValue();
            if (block != null)
                Threads.location(block.getLocation(), () -> putBack(arena, block, data));
        }

        // Everything else that changed, however it changed (water flowing, cobble forming, things falling...)
        final Snapshot snapshot = track.snapshot;
        final World world = snapshot == null ? null : Bukkit.getWorld(snapshot.world);
        final List<java.util.concurrent.CompletableFuture<Void>> pieces = new ArrayList<>();
        if (world != null) {
            for (final int[] p : snapshot.chunkPieces()) {
                final Location at = new Location(world, p[0], snapshot.minY, p[2]);
                if (!SpawnArena.INSTANCE.isEnabled()) {
                    // Shutting down: no other threads to hand work to, so it's all done here and now
                    Threads.location(at, () -> setChanged(arena, world, snapshot, p, changedIn(world.getChunkAt(p[0] >> 4, p[2] >> 4)
                            .getChunkSnapshot(false, false, false), snapshot, p)));
                    continue;
                }
                // Copy the chunk where it lives, find what changed off the server's threads, set only that back
                pieces.add(Threads.supplyAt(at, () -> world.getChunkAt(p[0] >> 4, p[2] >> 4).getChunkSnapshot(false, false, false))
                        .thenApplyAsync(copy -> changedIn(copy, snapshot, p), OFF_THREAD)
                        .thenCompose(changed -> Threads.supplyAt(at, () -> {
                            setChanged(arena, world, snapshot, p, changed);
                            return (Void) null;
                        }))
                        .exceptionally(e -> {
                            SpawnArena.INSTANCE.getLogger().log(Level.SEVERE, "Couldn't fully put back crystal arena " + arena.getArenaName(), e);
                            return null;
                        }));
            }
        }
        saveNow();
        return java.util.concurrent.CompletableFuture.allOf(pieces.toArray(new java.util.concurrent.CompletableFuture[0]))
                .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .exceptionally(e -> null); // the re-paste still follows
    }

    /** Spots in this piece that differ from the snapshot, top down: {x, y, z} with y relative to the snapshot. */
    private static List<int[]> changedIn(final org.bukkit.ChunkSnapshot now, final Snapshot snapshot, final int[] p) {
        final List<int[]> changed = new ArrayList<>();
        for (int y = snapshot.sizeY - 1; y >= 0; y--)
            for (int x = p[0]; x <= p[1]; x++)
                for (int z = p[2]; z <= p[3]; z++)
                    if (!now.getBlockData(x & 15, snapshot.minY + y, z & 15).equals(snapshot.data[snapshot.index(x - snapshot.minX, y, z - snapshot.minZ)]))
                        changed.add(new int[]{x, y, z});
        return changed;
    }

    /** Puts those spots back as the snapshot has them. On the thread that owns them. */
    private static void setChanged(final Arena arena, final World world, final Snapshot snapshot, final int[] p, final List<int[]> changed) {
        CLEANING.set(true);
        try {
            for (final int[] c : changed)
                world.getBlockAt(c[0], snapshot.minY + c[1], c[2])
                        .setBlockData(snapshot.data[snapshot.index(c[0] - snapshot.minX, c[1], c[2] - snapshot.minZ)], false);
        } catch (final Exception e) {
            SpawnArena.INSTANCE.getLogger().log(Level.SEVERE, "Couldn't fully put back crystal arena " + arena.getArenaName(), e);
        } finally {
            CLEANING.set(false);
        }
    }

    private static void putBack(final Arena arena, final Block block, final BlockData data) {
        CLEANING.set(true);
        try {
            block.setBlockData(data, true);
        } catch (final Exception e) {
            SpawnArena.INSTANCE.getLogger().log(Level.SEVERE, "Couldn't put back a block in crystal arena " + arena.getArenaName(), e);
        } finally {
            CLEANING.set(false);
        }
    }

    private static void removeEntity(final UUID id) {
        final Entity entity = Bukkit.getEntity(id);
        if (entity != null)
            Threads.entity(entity, entity::remove);
    }

    public static void cleanupAll() {
        for (final Track track : new ArrayList<>(TRACKS.values()))
            cleanup(track.fight);
    }

    // ── Crash safety ────────────────────────────────────────────────────────

    private static final File FILE = new File(SpawnArena.INSTANCE.getDataFolder(), "crystal-cleanup.txt");
    private static volatile boolean dirty;
    /** Bumped for every snapshot, so a slow async write can never overwrite a newer one */
    private static final java.util.concurrent.atomic.AtomicLong version = new java.util.concurrent.atomic.AtomicLong();
    private static long writtenVersion = -1;
    /** End crystals left behind by a crash, removed as soon as their chunk loads. */
    private static final Set<UUID> LEFTOVER_CRYSTALS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void markDirty() {
        dirty = true;
    }

    /** Saves changes every second, off the main thread. */
    public static void startSaving() {
        Threads.globalTimer(task -> {
            if (!dirty)
                return;
            dirty = false;
            final String contents = serialize();
            final long v = version.incrementAndGet();
            Threads.async(() -> write(contents, v));
        }, 20L, 20L);
    }

    private static void saveNow() {
        dirty = false;
        write(serialize(), version.incrementAndGet());
    }

    /** A snapshot taken under a lock, so it's consistent and the version matches it. */
    private static synchronized String serialize() {
        final StringBuilder out = new StringBuilder();
        for (final Track track : TRACKS.values()) {
            final List<Map.Entry<BlockKey, BlockData>> entries;
            synchronized (track.original) {
                entries = new ArrayList<>(track.original.entrySet());
            }
            for (final Map.Entry<BlockKey, BlockData> e : entries) {
                final BlockKey k = e.getKey();
                out.append("B ").append(k.world()).append(' ').append(k.x()).append(' ').append(k.y()).append(' ')
                        .append(k.z()).append(' ').append(e.getValue().getAsString()).append('\n');
            }
            for (final UUID id : track.crystals)
                out.append("E ").append(id).append('\n');
            out.append("F ").append(track.fight.getArena().getArenaName()).append('\n');
        }
        for (final UUID id : LEFTOVER_CRYSTALS)
            out.append("E ").append(id).append('\n');
        return out.toString();
    }

    private static synchronized void write(final String contents, final long v) {
        if (v < writtenVersion)
            return;
        writtenVersion = v;
        try {
            if (contents.isEmpty()) {
                Files.deleteIfExists(FILE.toPath());
                return;
            }
            final File tmp = new File(FILE.getParentFile(), FILE.getName() + ".tmp");
            Files.writeString(tmp.toPath(), contents, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), FILE.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException e) {
            SpawnArena.INSTANCE.getLogger().log(Level.WARNING, "Couldn't save " + FILE.getName(), e);
        }
    }

    /** On startup: undoes whatever a crash left behind in crystal arenas. */
    public static void restoreLeftovers() {
        if (!FILE.exists())
            return;

        final List<String> lines;
        try {
            lines = Files.readAllLines(FILE.toPath(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            SpawnArena.INSTANCE.getLogger().log(Level.SEVERE, "Couldn't read " + FILE.getName()
                    + "; blocks from a crystal fight interrupted by a crash may still be in the arena", e);
            return;
        }

        final List<String[]> blocks = new ArrayList<>();
        final List<String> interrupted = new ArrayList<>();
        for (final String line : lines) {
            final String[] parts = line.split(" ", 6);
            if (parts.length == 2 && parts[0].equals("F"))
                interrupted.add(parts[1]);
            else if (parts.length == 6 && parts[0].equals("B"))
                blocks.add(parts);
            else if (parts.length == 2 && parts[0].equals("E")) {
                try { LEFTOVER_CRYSTALS.add(UUID.fromString(parts[1])); } catch (final IllegalArgumentException ignored) {}
            }
        }

        int restored = 0;
        for (int i = blocks.size() - 1; i >= 0; i--) {
            final String[] p = blocks.get(i);
            try {
                final World world = Bukkit.getWorld(UUID.fromString(p[1]));
                if (world == null)
                    continue;
                final Block block = world.getBlockAt(Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]));
                final BlockData data = Bukkit.createBlockData(p[5]);
                Threads.location(block.getLocation(), () -> {
                    CLEANING.set(true);
                    try {
                        block.setBlockData(data, true);
                    } finally {
                        CLEANING.set(false);
                    }
                });
                restored++;
            } catch (final Exception e) {
                SpawnArena.INSTANCE.getLogger().warning("Skipped a crystal arena block in " + FILE.getName() + ": " + String.join(" ", p));
            }
        }
        if (restored > 0 || !LEFTOVER_CRYSTALS.isEmpty())
            SpawnArena.INSTANCE.getLogger().info("Cleaned up " + restored + " block(s) left in crystal arenas by an unfinished fight.");

        // Anything else those fights changed (water, cobble...) wasn't recorded: paste their schematic back
        for (final String name : interrupted) {
            final Arena arena = SpawnArena.arenas.of(name);
            if (arena instanceof SavableArena savable) {
                try {
                    final java.util.concurrent.CompletableFuture<Void> reset = savable.resetArena();
                    if (!reset.isDone()) {
                        savable.setResetting(true);
                        reset.orTimeout(2, java.util.concurrent.TimeUnit.MINUTES).whenComplete((ok, error) -> {
                            savable.setResetting(false);
                            Threads.global(() -> savable.queue.tryStartFight());
                        });
                    }
                    SpawnArena.INSTANCE.getLogger().info("Re-pasting crystal arena " + name + " after a fight there never finished.");
                } catch (final Exception e) {
                    SpawnArena.INSTANCE.getLogger().warning("Couldn't re-paste crystal arena " + name + ": " + e.getMessage());
                }
            }
        }

        // Match items left by a crash: on Paper search the worlds now; on Folia (no whole-world searches)
        // they're removed as their chunks load (onEntitiesLoad)
        if (!Threads.FOLIA)
            for (final World world : Bukkit.getWorlds())
                removeStaleItems(world.getEntitiesByClass(org.bukkit.entity.Item.class));

        for (final UUID id : new ArrayList<>(LEFTOVER_CRYSTALS)) {
            final Entity crystal = Bukkit.getEntity(id);
            if (crystal != null) {
                Threads.entity(crystal, crystal::remove);
                LEFTOVER_CRYSTALS.remove(id);
            }
        }
        saveNow();
    }

    /** Called as entities load: removes end crystals a crash left behind. */
    public static void onEntitiesLoad(final List<Entity> entities) {
        removeStaleItems(entities);
        if (LEFTOVER_CRYSTALS.isEmpty())
            return;
        boolean changed = false;
        for (final Entity entity : entities) {
            if (LEFTOVER_CRYSTALS.remove(entity.getUniqueId())) {
                entity.remove();
                changed = true;
            }
        }
        if (changed)
            markDirty();
    }
}
