package me.zeronull.spawnarena;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs work on the thread allowed to do it. On Paper there's one main thread, so everything runs
 * straight away when called from it. On Folia every part of the world ticks on its own thread: a
 * player, entity or block can only be changed from the thread that owns it, so work is handed over
 * to that thread when needed (running on its next tick).
 *
 * <p>Uses Paper's own scheduler API, which exists on Paper and Folia alike.
 */
public final class Threads {
    private Threads() {}

    public static final boolean FOLIA = classExists("io.papermc.paper.threadedregions.RegionizedServer");

    private static boolean classExists(final String name) {
        try {
            Class.forName(name);
            return true;
        } catch (final ClassNotFoundException e) {
            return false;
        }
    }

    /** Runs on this entity's thread: now if we're already on it, otherwise on its next tick. Dropped if it's gone. */
    public static void entity(final Entity entity, final Runnable task) {
        if (entity == null)
            return;
        if (shuttingDown() || Bukkit.isOwnedByCurrentRegion(entity)) {
            task.run();
            return;
        }
        entity.getScheduler().execute(SpawnArena.INSTANCE, task, null, 1L);
    }

    /** Runs on the thread that owns this location's chunk. */
    public static void location(final Location location, final Runnable task) {
        if (location == null || location.getWorld() == null)
            return;
        if (shuttingDown() || Bukkit.isOwnedByCurrentRegion(location)) {
            task.run();
            return;
        }
        Bukkit.getRegionScheduler().execute(SpawnArena.INSTANCE, location, task);
    }

    /** Like {@link #location}, with a result. */
    public static <T> CompletableFuture<T> supplyAt(final Location location, final Supplier<T> task) {
        final CompletableFuture<T> future = new CompletableFuture<>();
        if (location == null || location.getWorld() == null) {
            future.complete(null); // nowhere to run it
            return future;
        }
        location(location, () -> {
            try {
                future.complete(task.get());
            } catch (final Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /** Runs on the global thread (things that aren't tied to a place, like console commands). */
    public static void global(final Runnable task) {
        if (shuttingDown() || Bukkit.isGlobalTickThread()) {
            task.run();
            return;
        }
        Bukkit.getGlobalRegionScheduler().execute(SpawnArena.INSTANCE, task);
    }

    public static ScheduledTask globalTimer(final Consumer<ScheduledTask> task, final long delayTicks, final long periodTicks) {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(SpawnArena.INSTANCE, task, Math.max(1, delayTicks), periodTicks);
    }

    /** Repeats on the thread that owns this location's chunk (the main thread on Paper, its region's on Folia). */
    public static ScheduledTask locationTimer(final Location location, final Consumer<ScheduledTask> task, final long delayTicks, final long periodTicks) {
        return Bukkit.getRegionScheduler().runAtFixedRate(SpawnArena.INSTANCE, location, task, Math.max(1, delayTicks), periodTicks);
    }

    public static void globalLater(final Runnable task, final long delayTicks) {
        Bukkit.getGlobalRegionScheduler().runDelayed(SpawnArena.INSTANCE, t -> task.run(), Math.max(1, delayTicks));
    }

    public static void entityLater(final Entity entity, final Runnable task, final long delayTicks) {
        entity.getScheduler().execute(SpawnArena.INSTANCE, task, null, Math.max(1, delayTicks));
    }

    public static void async(final Runnable task) {
        if (shuttingDown()) {
            task.run();
            return;
        }
        Bukkit.getAsyncScheduler().runNow(SpawnArena.INSTANCE, t -> task.run());
    }

    /**
     * While the plugin is disabling, nothing can be scheduled any more, so work runs where it's called
     * (Folia lets the shutdown thread touch everything).
     */
    private static boolean shuttingDown() {
        return SpawnArena.INSTANCE == null || !SpawnArena.INSTANCE.isEnabled();
    }

    /**
     * Teleports a player from any thread. On Paper it happens right away (when called from the main
     * thread); on Folia it's done on the player's thread with Folia's async teleport. The future says
     * whether it worked, once it has.
     */
    public static CompletableFuture<Boolean> teleport(final Player player, final Location to) {
        if (player == null || to == null)
            return CompletableFuture.completedFuture(false);

        final CompletableFuture<Boolean> result = new CompletableFuture<>();
        final Runnable teleport = () -> {
            // Whatever they were eating or drinking (or a bow they were drawing) stops: it mustn't finish after the
            // teleport, when their inventory may have changed
            player.clearActiveItem();
            if (!FOLIA) {
                result.complete(player.teleport(to)); // Paper: instant, as it's always been
                return;
            }
            player.teleportAsync(to).whenComplete((ok, error) -> result.complete(error == null && Boolean.TRUE.equals(ok)));
        };

        if (shuttingDown() || Bukkit.isOwnedByCurrentRegion(player))
            teleport.run();
        else if (!player.getScheduler().execute(SpawnArena.INSTANCE, teleport, () -> result.complete(false), 1L))
            result.complete(false); // player is gone
        return result;
    }
}
