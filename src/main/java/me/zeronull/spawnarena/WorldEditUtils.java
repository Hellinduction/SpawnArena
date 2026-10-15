package me.zeronull.spawnarena;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.io.File;
import java.io.FileInputStream;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Uses only the standard WorldEdit API, so it works with WorldEdit and FastAsyncWorldEdit.
 * FAWE can safely paste off the main thread; plain WorldEdit must paste on the thread that owns the region.
 */
public final class WorldEditUtils {
    public static final boolean FAWE = Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit") != null;

    private WorldEditUtils() {}

    /**
     * Pastes a schematic at a location; with {@code ignoreAir} its air doesn't overwrite anything (like
     * //paste -a). The future completes once the paste is done, failed or not (failures are logged).
     */
    public static CompletableFuture<Void> paste(final Location loc, final File schematic, final boolean ignoreAir) {
        final CompletableFuture<Void> done = new CompletableFuture<>();
        // Reading the file is thread-safe for both implementations
        Bukkit.getAsyncScheduler().runNow(SpawnArena.INSTANCE, task -> {
            final Clipboard clipboard;
            try {
                clipboard = load(schematic);
            } catch (final Exception e) {
                SpawnArena.INSTANCE.getLogger().log(Level.SEVERE, "Failed to load schematic " + schematic, e);
                done.complete(null);
                return;
            }

            if (FAWE) {
                pasteNow(clipboard, loc, ignoreAir);
                done.complete(null);
            } else {
                Bukkit.getRegionScheduler().execute(SpawnArena.INSTANCE, loc, () -> {
                    pasteNow(clipboard, loc, ignoreAir);
                    done.complete(null);
                });
            }
        });
        return done;
    }

    private static Clipboard load(final File schematic) throws Exception {
        final ClipboardFormat format = ClipboardFormats.findByFile(schematic);
        if (format == null)
            throw new IllegalArgumentException("Unknown schematic format: " + schematic.getName());

        try (ClipboardReader reader = format.getReader(new FileInputStream(schematic))) {
            return reader.read();
        }
    }

    private static void pasteNow(final Clipboard clipboard, final Location loc, final boolean ignoreAir) {
        try (EditSession session = newSession(loc.getWorld())) {
            Operations.complete(new ClipboardHolder(clipboard)
                    .createPaste(session)
                    .to(BlockVector3.at(loc.getX(), loc.getY(), loc.getZ()))
                    .ignoreAirBlocks(ignoreAir)
                    .build());
        } catch (final Exception e) {
            SpawnArena.INSTANCE.getLogger().log(Level.SEVERE, "Failed to paste arena schematic at " + loc, e);
        }
    }

    /**
     * An edit session for pasting arenas. On plain WorldEdit (Folia, or Paper without FAWE) it skips neighbour
     * updates, physics and block-state fixing: the schematic is put back exactly as saved, and those updates
     * are most of the time a paste takes. Lighting stays on so the arena isn't left dark. FAWE is left as is.
     */
    private static EditSession newSession(final org.bukkit.World world) {
        final EditSession session = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world));
        if (!FAWE)
            session.setSideEffectApplier(com.sk89q.worldedit.util.SideEffectSet.defaults()
                    .with(com.sk89q.worldedit.util.SideEffect.NEIGHBORS, com.sk89q.worldedit.util.SideEffect.State.OFF)
                    .with(com.sk89q.worldedit.util.SideEffect.UPDATE, com.sk89q.worldedit.util.SideEffect.State.OFF)
                    .with(com.sk89q.worldedit.util.SideEffect.VALIDATION, com.sk89q.worldedit.util.SideEffect.State.OFF));
        return session;
    }

    /** A paste that can be undone, and the box it covers in the world. */
    public record Paste(EditSession session, BlockVector3 min, BlockVector3 max) {}

    /**
     * Pastes like //paste (air included, the schematic's origin at {@code loc}) and keeps the edit so it can
     * be undone with {@link #undo}. Fails the future if the schematic can't be read or pasted.
     */
    public static CompletableFuture<Paste> pasteUndoable(final Location loc, final File schematic, final File backup) {
        final CompletableFuture<Paste> done = new CompletableFuture<>();
        Bukkit.getAsyncScheduler().runNow(SpawnArena.INSTANCE, task -> {
            final Clipboard clipboard;
            try {
                clipboard = load(schematic);
            } catch (final Exception e) {
                done.completeExceptionally(e);
                return;
            }
            final Runnable paste = () -> {
                try {
                    final BlockVector3 to = BlockVector3.at(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
                    final BlockVector3 shift = to.subtract(clipboard.getOrigin());
                    final BlockVector3 min = clipboard.getRegion().getMinimumPoint().add(shift);
                    final BlockVector3 max = clipboard.getRegion().getMaximumPoint().add(shift);
                    // What's there now, kept on disk: if the server stops before the setup is finished, it's put back
                    if (backup != null)
                        copyToFile(loc.getWorld(), min, max, loc, backup);
                    final EditSession session = newSession(loc.getWorld());
                    try {
                        Operations.complete(new ClipboardHolder(clipboard).createPaste(session).to(to).ignoreAirBlocks(false).build());
                    } finally {
                        session.close();
                    }
                    done.complete(new Paste(session, min, max));
                } catch (final Exception e) {
                    done.completeExceptionally(e);
                }
            };
            if (FAWE)
                paste.run();
            else
                Bukkit.getRegionScheduler().execute(SpawnArena.INSTANCE, loc, paste);
        });
        return done;
    }

    /** Undoes a paste from {@link #pasteUndoable}, putting back what was there before. */
    public static CompletableFuture<Void> undo(final Location at, final Paste paste) {
        final CompletableFuture<Void> done = new CompletableFuture<>();
        final Runnable undo = () -> {
            try (EditSession undoSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(at.getWorld()))) {
                paste.session().undo(undoSession);
                done.complete(null);
            } catch (final Exception e) {
                done.completeExceptionally(e);
            }
        };
        if (FAWE)
            Bukkit.getAsyncScheduler().runNow(SpawnArena.INSTANCE, t -> undo.run());
        else
            Bukkit.getRegionScheduler().execute(SpawnArena.INSTANCE, at, undo);
        return done;
    }

    /**
     * Saves the blocks between {@code min} and {@code max} as a schematic file, with its origin at
     * {@code origin} (so pasting it at {@code origin} puts everything back exactly where it is now).
     */
    public static CompletableFuture<Void> saveSchematic(final org.bukkit.World world, final BlockVector3 min, final BlockVector3 max,
                                                        final Location origin, final File file) {
        final CompletableFuture<Void> done = new CompletableFuture<>();
        final Runnable save = () -> {
            try {
                copyToFile(world, min, max, origin, file);
                done.complete(null);
            } catch (final Exception e) {
                done.completeExceptionally(e);
            }
        };
        if (FAWE)
            Bukkit.getAsyncScheduler().runNow(SpawnArena.INSTANCE, t -> save.run());
        else
            Bukkit.getRegionScheduler().execute(SpawnArena.INSTANCE, origin, save);
        return done;
    }

    /** Copies a box of blocks into a schematic file (origin at {@code origin}). Call on the right thread. */
    private static void copyToFile(final org.bukkit.World world, final BlockVector3 min, final BlockVector3 max,
                                   final Location origin, final File file) throws Exception {
        final com.sk89q.worldedit.regions.CuboidRegion region =
                new com.sk89q.worldedit.regions.CuboidRegion(BukkitAdapter.adapt(world), min, max);
        final com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard clipboard =
                new com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard(region);
        clipboard.setOrigin(BlockVector3.at(origin.getBlockX(), origin.getBlockY(), origin.getBlockZ()));
        try (EditSession session = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
            final com.sk89q.worldedit.function.operation.ForwardExtentCopy copy =
                    new com.sk89q.worldedit.function.operation.ForwardExtentCopy(session, region, clipboard, region.getMinimumPoint());
            copy.setCopyingEntities(false);
            Operations.complete(copy);
        }
        file.getParentFile().mkdirs();
        final ClipboardFormat format = com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC;
        try (com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter writer = format.getWriter(new java.io.FileOutputStream(file))) {
            writer.write(clipboard);
        }
    }

    /** Where new schematics go: FAWE's folder if it's installed, else WorldEdit's. */
    public static File schematicsFolder() {
        return new File(FAWE ? "./plugins/FastAsyncWorldEdit/schematics/" : "./plugins/WorldEdit/schematics/");
    }

    /** Schematic files in the WorldEdit and FAWE schematics folders, sorted by name. */
    public static java.util.List<File> listSchematics() {
        final java.util.List<File> files = new java.util.ArrayList<>();
        for (final String dir : new String[]{"./plugins/FastAsyncWorldEdit/schematics/", "./plugins/WorldEdit/schematics/"}) {
            final File[] found = new File(dir).listFiles((d, n) -> n.endsWith(".schem") || n.endsWith(".schematic"));
            if (found != null)
                files.addAll(java.util.List.of(found));
        }
        files.sort(java.util.Comparator.comparing(f -> f.getName().toLowerCase()));
        return files;
    }

    public static File getSchematic(final String name) {
        for (final String dir : new String[]{"./plugins/FastAsyncWorldEdit/schematics/", "./plugins/WorldEdit/schematics/"}) {
            for (final String ext : new String[]{".schem", ".schematic"}) {
                final File file = new File(dir, name + ext);
                if (file.exists())
                    return file;
            }
        }
        return new File("./plugins/WorldEdit/schematics/", name + ".schem");
    }
}
