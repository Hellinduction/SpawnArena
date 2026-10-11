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
import java.util.logging.Level;

/**
 * Uses only the standard WorldEdit API, so it works with WorldEdit and FastAsyncWorldEdit.
 * FAWE can safely paste off the main thread; plain WorldEdit must paste on the thread that owns the region.
 */
public final class WorldEditUtils {
    public static final boolean FAWE = Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit") != null;

    private WorldEditUtils() {}

    public static void paste(final Location loc, final File schematic) {
        // Reading the file is thread-safe for both implementations
        Bukkit.getAsyncScheduler().runNow(SpawnArena.INSTANCE, task -> {
            final Clipboard clipboard;
            try {
                clipboard = load(schematic);
            } catch (final Exception e) {
                SpawnArena.INSTANCE.getLogger().log(Level.SEVERE, "Failed to load schematic " + schematic, e);
                return;
            }

            if (FAWE)
                pasteNow(clipboard, loc);
            else
                Bukkit.getRegionScheduler().execute(SpawnArena.INSTANCE, loc, () -> pasteNow(clipboard, loc));
        });
    }

    private static Clipboard load(final File schematic) throws Exception {
        final ClipboardFormat format = ClipboardFormats.findByFile(schematic);
        if (format == null)
            throw new IllegalArgumentException("Unknown schematic format: " + schematic.getName());

        try (ClipboardReader reader = format.getReader(new FileInputStream(schematic))) {
            return reader.read();
        }
    }

    private static void pasteNow(final Clipboard clipboard, final Location loc) {
        try (EditSession session = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(loc.getWorld()))) {
            Operations.complete(new ClipboardHolder(clipboard)
                    .createPaste(session)
                    .to(BlockVector3.at(loc.getX(), loc.getY(), loc.getZ()))
                    .ignoreAirBlocks(false)
                    .build());
        } catch (final Exception e) {
            SpawnArena.INSTANCE.getLogger().log(Level.SEVERE, "Failed to paste arena schematic at " + loc, e);
        }
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
