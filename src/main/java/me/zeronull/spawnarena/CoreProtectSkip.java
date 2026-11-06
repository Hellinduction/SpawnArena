package me.zeronull.spawnarena;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CoreProtect logs nothing inside crystal arenas (their WorldGuard regions, and each MULTI copy's): the blocks and
 * crystals placed and blown up there, all put back by the arena's own reset, would only fill its database. Done through
 * CoreProtect's own CoreProtectPreLogEvent, which it asks before logging (from its logging thread). Settings:
 * coreProtectSkipsCrystalArenas.
 */
public final class CoreProtectSkip {
    /** World name -> the crystal arenas' regions in it (looked at again every 30 seconds) */
    private static volatile Map<String, List<BoundingBox>> regions = Map.of();
    private static Method getLocation;

    private CoreProtectSkip() {
    }

    /** On enable: if CoreProtect is here (with its API on), its logging in crystal arenas is cancelled. */
    public static void install(final Plugin plugin) {
        final Plugin coreProtect = Bukkit.getPluginManager().getPlugin("CoreProtect");
        if (coreProtect == null || !coreProtect.isEnabled())
            return;
        try {
            @SuppressWarnings("unchecked") final Class<? extends Event> type = (Class<? extends Event>)
                    Class.forName("net.coreprotect.event.CoreProtectPreLogEvent", true, coreProtect.getClass().getClassLoader());
            getLocation = type.getMethod("getLocation");
            Bukkit.getPluginManager().registerEvent(type, new Listener() {
            }, EventPriority.HIGHEST, (listener, event) -> {
                if (type.isInstance(event) && skip(event))
                    ((Cancellable) event).setCancelled(true);
            }, plugin, true);
        } catch (final ReflectiveOperationException | LinkageError | ClassCastException e) {
            plugin.getLogger().warning("Couldn't keep CoreProtect out of crystal arenas (" + e + ").");
            return;
        }
        Threads.globalTimer(task -> refresh(), 100L, 600L);
    }

    private static boolean skip(final Event event) {
        if (SpawnArena.settings != null && !SpawnArena.settings.coreProtectSkipsCrystalArenas)
            return false;
        try {
            final Location at = (Location) getLocation.invoke(event);
            final World world = at == null ? null : at.getWorld();
            if (world == null)
                return false;
            final List<BoundingBox> boxes = regions.get(world.getName());
            if (boxes == null)
                return false;
            final double x = at.getBlockX() + 0.5, y = at.getBlockY() + 0.5, z = at.getBlockZ() + 0.5;
            for (final BoundingBox box : boxes)
                if (box.contains(x, y, z))
                    return true;
        } catch (final ReflectiveOperationException | RuntimeException ignored) {
            // logged as usual
        }
        return false;
    }

    /** The crystal arenas' regions now (arenas can be added, and copies made, while the server runs). */
    static void refresh() {
        if (!Arena.ArenaUtils.WORLD_EDIT_SUPPORT || SpawnArena.arenas == null)
            return;
        final Map<String, List<BoundingBox>> found = new java.util.HashMap<>();
        final List<Arena> all = new ArrayList<>(SpawnArena.arenas.toList());
        all.addAll(CrystalCopies.all());
        for (final Arena arena : all) {
            if (!arena.isCrystalPvp())
                continue;
            final World world = arena instanceof CrystalCopy copy ? copy.world()
                    : arena.spawnPoint1 != null ? arena.spawnPoint1.getWorld() : null;
            if (world == null)
                continue;
            try {
                final com.sk89q.worldguard.protection.managers.RegionManager manager =
                        Arena.ArenaUtils.REGION_CONTAINER.get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(world));
                final com.sk89q.worldguard.protection.regions.ProtectedRegion region = manager == null ? null : manager.getRegion(arena.getArenaName());
                if (region == null)
                    continue;
                final com.sk89q.worldedit.math.BlockVector3 min = region.getMinimumPoint(), max = region.getMaximumPoint();
                found.computeIfAbsent(world.getName(), w -> new ArrayList<>())
                        .add(new BoundingBox(min.x(), min.y(), min.z(), max.x() + 1, max.y() + 1, max.z() + 1));
            } catch (final RuntimeException ignored) {
                // that arena's logged as usual
            }
        }
        regions = Map.copyOf(found);
    }
}
