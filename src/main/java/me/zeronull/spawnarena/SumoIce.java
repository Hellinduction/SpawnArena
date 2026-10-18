package me.zeronull.spawnarena;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Ice Rink (random sumo): the platform under the spawns turns to packed ice for the match and goes back exactly as it
 * was afterwards. The platform is found by spreading out from the floor under each spawn, only through solid blocks on
 * that level inside the arena's region. What was changed is written to disk first, so a crash can't leave the ice
 * behind (it's put back on the next start).
 */
public final class SumoIce implements org.bukkit.event.Listener {
    private record Saved(int x, int y, int z, String data) {}

    /** At most this many floor blocks are turned to ice (a sumo platform is far smaller). */
    private static final int MAX_BLOCKS = 4096;
    private static final Map<String, List<Saved>> FROZEN = new ConcurrentHashMap<>();

    public SumoIce() {} // only for its click listener

    private static File folder() {
        return new File(SpawnArena.INSTANCE.getDataFolder(), "sumo-ice");
    }

    private static File fileFor(final Arena arena) {
        return new File(folder(), arena.getArenaName().toLowerCase() + ".txt");
    }

    /** Turns the arena's platform to ice, on the thread that owns it. Completes once it's done. */
    public static CompletableFuture<Void> freeze(final Arena arena) {
        return save(arena, true);
    }

    /** Saves the platform (to put back with thaw) without changing it, for Shrinking Platform. */
    public static CompletableFuture<Void> record(final Arena arena) {
        return save(arena, false);
    }

    /** The saved platform's block positions ({x, y, z}), empty if none is saved. */
    public static List<int[]> platformOf(final Arena arena) {
        final List<Saved> saved = FROZEN.get(key(arena));
        final List<int[]> out = new ArrayList<>();
        if (saved != null)
            for (final Saved s : saved)
                out.add(new int[]{s.x(), s.y(), s.z()});
        return out;
    }

    private static CompletableFuture<Void> save(final Arena arena, final boolean ice) {
        final Location spawn = arena.spawnPoint1;
        if (spawn == null || spawn.getWorld() == null)
            return CompletableFuture.completedFuture(null);
        return Threads.supplyAt(spawn, () -> {
            try {
                if (FROZEN.containsKey(key(arena)))
                    thawNow(arena); // left over: put it back before recording the floor again
                final List<Block> floor = findPlatform(arena);
                final List<Saved> saved = new ArrayList<>();
                for (final Block b : floor)
                    saved.add(new Saved(b.getX(), b.getY(), b.getZ(), b.getBlockData().getAsString()));
                write(arena, spawn.getWorld(), saved); // on disk before anything changes
                FROZEN.put(key(arena), saved);
                if (ice)
                    for (final Block b : floor)
                        b.setType(Material.PACKED_ICE, false);
            } catch (final Exception e) {
                SpawnArena.INSTANCE.getLogger().log(Level.WARNING, "Couldn't turn the floor of " + arena.getArenaName() + " to ice", e);
            }
            return null;
        });
    }

    /** Puts the platform back, on the thread that owns it. Safe to call when nothing was frozen. */
    public static CompletableFuture<Void> thaw(final Arena arena) {
        // Always queued on the floor's thread, so it can't run before a freeze that's still waiting there
        final Location spawn = arena.spawnPoint1;
        if (spawn == null || spawn.getWorld() == null)
            return CompletableFuture.completedFuture(null);
        return Threads.supplyAt(spawn, () -> {
            thawNow(arena);
            return null;
        });
    }

    private static void thawNow(final Arena arena) {
        final List<Saved> saved = FROZEN.remove(key(arena));
        final World world = arena.spawnPoint1 == null ? null : arena.spawnPoint1.getWorld();
        if (saved == null || world == null)
            return;
        restore(world, saved);
        if (!fileFor(arena).delete() && fileFor(arena).exists())
            SpawnArena.INSTANCE.getLogger().warning("Couldn't delete " + fileFor(arena));
    }

    private static void restore(final World world, final List<Saved> saved) {
        for (final Saved s : saved) {
            final Block b = world.getBlockAt(s.x(), s.y(), s.z());
            if (!Bukkit.isOwnedByCurrentRegion(b.getLocation()))
                continue; // can't happen for a platform found from one place; just never throw over it
            try {
                final BlockData data = Bukkit.createBlockData(s.data());
                b.setBlockData(data, false);
            } catch (final IllegalArgumentException e) {
                SpawnArena.INSTANCE.getLogger().warning("Couldn't put back " + s.data() + " at " + s.x() + " " + s.y() + " " + s.z());
            }
        }
    }

    /** The solid floor level under the spawns, spread out within the arena. */
    private static List<Block> findPlatform(final Arena arena) {
        final Set<Block> found = new HashSet<>();
        final ArrayDeque<Block> queue = new ArrayDeque<>();
        for (final Location spawn : new Location[]{arena.spawnPoint1, arena.spawnPoint2}) {
            if (spawn == null || spawn.getWorld() == null || !Bukkit.isOwnedByCurrentRegion(spawn))
                continue;
            final Block under = spawn.getBlock().getRelative(BlockFace.DOWN);
            if (isFloor(arena, under) && found.add(under))
                queue.add(under);
        }
        final BlockFace[] sides = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        while (!queue.isEmpty() && found.size() < MAX_BLOCKS) {
            final Block b = queue.poll();
            for (final BlockFace f : sides) {
                final Block next = b.getRelative(f);
                if (!found.contains(next) && isFloor(arena, next)) {
                    found.add(next);
                    queue.add(next);
                }
            }
        }
        return new ArrayList<>(found);
    }

    private static boolean isFloor(final Arena arena, final Block b) {
        if (!Bukkit.isOwnedByCurrentRegion(b.getLocation()))
            return false;
        final Material type = b.getType();
        if (!type.isSolid() || b.isLiquid() || type == Material.PACKED_ICE || b.getState() instanceof org.bukkit.block.TileState)
            return false;
        // Only something you can stand on top of (not under a ceiling of the arena itself)
        if (!b.getRelative(BlockFace.UP).isPassable())
            return false;
        return !Arena.ArenaUtils.WORLD_EDIT_SUPPORT || arena.equals(SpawnArena.arenas.of(b.getLocation().add(0.5, 1, 0.5)));
    }

    private static String key(final Arena arena) {
        return arena.getArenaName().toLowerCase();
    }

    private static void write(final Arena arena, final World world, final List<Saved> saved) throws IOException {
        Files.createDirectories(folder().toPath());
        final List<String> lines = new ArrayList<>();
        lines.add(world.getName());
        for (final Saved s : saved)
            lines.add(s.x() + " " + s.y() + " " + s.z() + " " + s.data());
        Files.write(fileFor(arena).toPath(), lines, StandardCharsets.UTF_8);
    }

    // ── Shown only to one match's players (MULTI arenas) ─────────────────────

    /** A platform block and what it really is. */
    public record FakeBlock(Location at, BlockData real) {}

    /** Finds the platform (on its thread) for showing it as ice to one match's players. Nothing in the world changes. */
    public static CompletableFuture<List<FakeBlock>> findForFake(final Arena arena) {
        final Location spawn = arena.spawnPoint1;
        if (spawn == null || spawn.getWorld() == null)
            return CompletableFuture.completedFuture(List.of());
        return Threads.supplyAt(spawn, () -> {
            final List<FakeBlock> blocks = new ArrayList<>();
            for (final Block b : findPlatform(arena))
                blocks.add(new FakeBlock(b.getLocation(), b.getBlockData().clone()));
            return blocks;
        });
    }

    private static final BlockData ICE = Material.PACKED_ICE.createBlockData();

    /** Shows the platform as ice to this player (on their thread). */
    public static void showIce(final org.bukkit.entity.Player player, final List<FakeBlock> blocks) {
        Threads.entity(player, () -> {
            if (!player.isOnline())
                return;
            final Map<Location, BlockData> changes = new java.util.HashMap<>();
            for (final FakeBlock b : blocks)
                changes.put(b.at(), ICE);
            player.sendMultiBlockChange(changes);
        });
    }

    /** Shows them the real floor again (on their thread). */
    public static void showReal(final org.bukkit.entity.Player player, final List<FakeBlock> blocks) {
        Threads.entity(player, () -> {
            if (!player.isOnline())
                return;
            final Map<Location, BlockData> changes = new java.util.HashMap<>();
            for (final FakeBlock b : blocks)
                changes.put(b.at(), b.real());
            player.sendMultiBlockChange(changes);
        });
    }

    /**
     * MULTI Ice Rink: clicking a block (the fake ice, or one next to it) makes the server send the real block back, so
     * the fighter would see the normal floor again. Their fight's ice is shown again a tick later (and a little after).
     */
    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onClick(final org.bukkit.event.player.PlayerInteractEvent e) {
        final Block clicked = e.getClickedBlock();
        if (clicked == null || SpawnArena.arenas == null)
            return;
        final org.bukkit.entity.Player player = e.getPlayer();
        final Arena arena = SpawnArena.arenas.of(player);
        final Fight fight = arena == null ? null : arena.getFight(player);
        if (fight == null || !fight.hasFakeIceNear(clicked))
            return;
        Threads.entityLater(player, () -> fight.showFakeIce(player), 1L);
        Threads.entityLater(player, () -> fight.showFakeIce(player), 5L);
    }

    /** On startup: floors a crash left as ice are put back. */
    public static void restoreLeftovers() {
        final File[] files = folder().listFiles((dir, name) -> name.endsWith(".txt"));
        if (files == null)
            return;
        for (final File file : files) {
            try {
                final List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
                if (lines.isEmpty()) {
                    file.delete();
                    continue;
                }
                final World world = Bukkit.getWorld(lines.get(0));
                if (world == null)
                    continue;
                final List<Saved> saved = new ArrayList<>();
                for (final String line : lines.subList(1, lines.size())) {
                    final String[] p = line.split(" ", 4);
                    if (p.length == 4)
                        saved.add(new Saved(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), p[3]));
                }
                if (saved.isEmpty()) {
                    file.delete();
                    continue;
                }
                final Saved first = saved.get(0);
                Threads.location(new Location(world, first.x(), first.y(), first.z()), () -> {
                    restore(world, saved);
                    file.delete();
                    SpawnArena.INSTANCE.getLogger().info("Put back the sumo floor left as ice in " + file.getName());
                });
            } catch (final Exception e) {
                SpawnArena.INSTANCE.getLogger().log(Level.WARNING, "Couldn't put back the sumo floor from " + file, e);
            }
        }
    }
}
