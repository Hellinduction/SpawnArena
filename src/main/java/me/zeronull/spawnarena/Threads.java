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

    /**
     * Runs on the global thread and waits for the result: for a background thread calling something that must be called
     * there (like the economy). Runs straight away if already there or shutting down. It runs exactly once: if the plugin
     * starts shutting down while it waits (the global thread then never gets to it), the waiting thread runs it instead,
     * and only if the global thread hasn't started it.
     */
    public static <T> T callGlobal(final Supplier<T> task) {
        if (shuttingDown() || Bukkit.isGlobalTickThread())
            return task.get();
        final java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean();
        final CompletableFuture<T> result = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().execute(SpawnArena.INSTANCE, () -> {
            if (!claimed.compareAndSet(false, true))
                return;
            try {
                result.complete(task.get());
            } catch (final Throwable t) {
                result.completeExceptionally(t);
            }
        });
        while (true) {
            try {
                return result.get(100, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (final java.util.concurrent.TimeoutException e) {
                if (shuttingDown() && claimed.compareAndSet(false, true))
                    return task.get();
            } catch (final InterruptedException e) {
                // Keep waiting: giving up here could let it run after we've moved on
            } catch (final java.util.concurrent.ExecutionException e) {
                throw e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause());
            }
        }
    }

    /**
     * Match results are recorded one at a time, in the order the matches ended, on a thread of their own: so one player's
     * results (streaks, rewards) never overlap, however slow the database is.
     */
    private static final java.util.concurrent.ExecutorService RESULTS = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "SpawnArena results");
        t.setDaemon(true);
        return t;
    });

    public static void result(final Runnable task) {
        try {
            RESULTS.execute(task);
        } catch (final java.util.concurrent.RejectedExecutionException e) {
            task.run(); // shutting down: record it here
        }
    }

    /** On disable, before the database closes: lets the results still queued finish (up to 30 seconds). */
    public static void finishResults() {
        RESULTS.shutdown();
        try {
            if (!RESULTS.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS))
                SpawnArena.INSTANCE.getLogger().severe("Match results were still being recorded after 30 seconds; the rest weren't saved.");
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
