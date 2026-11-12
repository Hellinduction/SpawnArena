package me.zeronull.spawnarena;

import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.transform.BlockTransformExtent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Arena pastes and copies on Folia with plain WorldEdit (FAWE doesn't run on Folia; Paper doesn't need this). On Folia a
 * thread may only touch the blocks of its own region, and an arena can lie across more than one region, so WorldEdit's
 * usual paste in one go (on one region's thread) would reach into chunks that thread doesn't own. Here the work is split
 * into pieces of at most one chunk section (4096 blocks), each done on the thread of the region owning that chunk, one
 * piece after another. Blocks already as they should be are left alone, so putting back an arena only rewrites what the
 * fight changed. Side effects as {@link WorldEditUtils}' resets: no neighbour updates, physics or state fixing, lighting on.
 */
final class RegionArenaEdits {
    /** Blocks written in one go on a region's thread (a chunk section): small enough not to hold its tick up. */
    private static final int BLOCKS_PER_STEP = 4096;
    private static final SideEffectSet SIDE_EFFECTS = SideEffectSet.defaults()
            .with(SideEffect.NEIGHBORS, SideEffect.State.OFF)
            .with(SideEffect.UPDATE, SideEffect.State.OFF)
            .with(SideEffect.VALIDATION, SideEffect.State.OFF);
    private static final BaseBlock AIR = BlockTypes.AIR.getDefaultState().toBaseBlock();

    private RegionArenaEdits() {}

    /** One block to write (and its biome, for copies: biomes are stored every 4 blocks, so only those carry one). */
    private record Cell(BlockVector3 at, BaseBlock block, BiomeType biome) {}

    /** Runs {@code task} on the thread owning chunk {@code cx, cz} of {@code world}, once that chunk is loaded. */
    private static <T> CompletableFuture<T> onChunk(final World world, final int cx, final int cz, final Supplier<T> task) {
        final CompletableFuture<T> result = new CompletableFuture<>();
        world.getChunkAtAsync(cx, cz, true).whenComplete((chunk, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
                return;
            }
            try {
                Bukkit.getRegionScheduler().execute(SpawnArena.INSTANCE, world, cx, cz, () -> {
                    try {
                        result.complete(task.get());
                    } catch (final Throwable t) {
                        result.completeExceptionally(t);
                    }
                });
            } catch (final Throwable t) { // e.g. SpawnArena is being disabled
                result.completeExceptionally(t);
            }
        });
        return result;
    }

    /** Writes the cells that differ from what's there. On the thread owning their chunk. */
    private static void write(final World world, final List<Cell> cells) {
        final com.sk89q.worldedit.world.World target = BukkitAdapter.adapt(world);
        try {
            for (final Cell cell : cells) {
                if (!target.getFullBlock(cell.at()).equals(cell.block()))
                    target.setBlock(cell.at(), cell.block(), SIDE_EFFECTS);
                if (cell.biome() != null && !cell.biome().equals(target.getBiome(cell.at())))
                    target.setBiome(cell.at(), cell.biome());
            }
        } catch (final WorldEditException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Pastes a schematic already read like {@link WorldEditUtils#paste}: its origin at {@code loc}, turned {@code turn}
     * degrees clockwise seen from above, its air left out with {@code ignoreAir}. Fails the future if a piece fails.
     */
    static CompletableFuture<Void> paste(final Clipboard clipboard, final Location loc, final boolean ignoreAir, final int turn) {
        final World world = loc.getWorld();
        final BlockVector3 origin = clipboard.getOrigin();
        final BlockVector3 to = BlockVector3.at(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        final boolean turned = turn % 360 != 0;
        final AffineTransform transform = new AffineTransform().rotateY(-turn); // //rotate's way round, as WorldEditUtils
        // Turns the blocks themselves (stairs facing, etc.); where each one goes is worked out below
        final Extent source = turned ? new BlockTransformExtent(clipboard, transform) : clipboard;

        // Grouped by the chunk they land in, in the order met
        final Map<Long, List<Cell>> byChunk = new LinkedHashMap<>();
        for (final BlockVector3 from : clipboard.getRegion()) {
            final BaseBlock block = source.getFullBlock(from);
            if (ignoreAir && block.getBlockType().getMaterial().isAir())
                continue;
            final BlockVector3 relative = from.subtract(origin);
            final BlockVector3 at = (turned ? transform.apply(relative.toVector3()).toBlockPoint() : relative).add(to);
            if (at.y() < world.getMinHeight() || at.y() >= world.getMaxHeight())
                continue; // as WorldEdit: outside the world is left out
            byChunk.computeIfAbsent(chunkKey(at.x() >> 4, at.z() >> 4), k -> new ArrayList<>()).add(new Cell(at, block, null));
        }

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (final Map.Entry<Long, List<Cell>> entry : byChunk.entrySet()) {
            final int cx = (int) (entry.getKey() >> 32), cz = (int) (long) entry.getKey();
            final List<Cell> cells = entry.getValue();
            for (int i = 0; i < cells.size(); i += BLOCKS_PER_STEP) {
                final List<Cell> step = cells.subList(i, Math.min(cells.size(), i + BLOCKS_PER_STEP));
                chain = chain.thenCompose(ignored -> onChunk(world, cx, cz, () -> {
                    write(world, step);
                    return null;
                }));
            }
        }
        return chain;
    }

    /**
     * Copies the box {@code min..max} of {@code from} into the same place in {@code to}, biomes included, like
     * {@link WorldEditUtils#copyBox}: first emptying {@code clearMin..clearMax} in {@code to} (if given). Each piece is
     * read on its chunk's thread in {@code from}, then written on its chunk's thread in {@code to}.
     */
    static CompletableFuture<Void> copyBox(final World from, final World to, final BlockVector3 min, final BlockVector3 max,
                                           final BlockVector3 clearMin, final BlockVector3 clearMax) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        if (clearMin != null && clearMax != null)
            for (final BlockVector3[] piece : pieces(clearMin, clearMax))
                chain = chain.thenCompose(ignored -> onChunk(to, piece[0].x() >> 4, piece[0].z() >> 4, () -> {
                    final List<Cell> air = new ArrayList<>();
                    final com.sk89q.worldedit.world.World target = BukkitAdapter.adapt(to);
                    each(piece[0], piece[1], at -> {
                        if (!target.getBlock(at).getBlockType().getMaterial().isAir())
                            air.add(new Cell(at, AIR, null));
                    });
                    write(to, air);
                    return null;
                }));
        for (final BlockVector3[] piece : pieces(min, max)) {
            final int cx = piece[0].x() >> 4, cz = piece[0].z() >> 4;
            chain = chain.thenCompose(ignored -> onChunk(from, cx, cz, () -> {
                final com.sk89q.worldedit.world.World source = BukkitAdapter.adapt(from);
                final List<Cell> cells = new ArrayList<>();
                each(piece[0], piece[1], at -> cells.add(new Cell(at, source.getFullBlock(at),
                        (at.x() & 3) == 0 && (at.y() & 3) == 0 && (at.z() & 3) == 0 ? source.getBiome(at) : null)));
                return cells;
            })).thenCompose(cells -> onChunk(to, cx, cz, () -> {
                write(to, cells);
                return null;
            }));
        }
        return chain;
    }

    /** The box cut into pieces of at most one chunk section each (one chunk column, 16 blocks high). */
    private static List<BlockVector3[]> pieces(final BlockVector3 min, final BlockVector3 max) {
        final List<BlockVector3[]> pieces = new ArrayList<>();
        for (int cx = min.x() >> 4; cx <= max.x() >> 4; cx++)
            for (int cz = min.z() >> 4; cz <= max.z() >> 4; cz++)
                for (int y = min.y(); y <= max.y(); y += 16)
                    pieces.add(new BlockVector3[]{
                            BlockVector3.at(Math.max(min.x(), cx << 4), y, Math.max(min.z(), cz << 4)),
                            BlockVector3.at(Math.min(max.x(), (cx << 4) + 15), Math.min(max.y(), y + 15), Math.min(max.z(), (cz << 4) + 15))});
        return pieces;
    }

    private static void each(final BlockVector3 min, final BlockVector3 max, final Consumer<BlockVector3> action) {
        for (int y = min.y(); y <= max.y(); y++)
            for (int x = min.x(); x <= max.x(); x++)
                for (int z = min.z(); z <= max.z(); z++)
                    action.accept(BlockVector3.at(x, y, z));
    }

    private static long chunkKey(final int cx, final int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
