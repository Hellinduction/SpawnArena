package me.zeronull.spawnarena;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells a dropped connection apart from a player quitting. It's a lag-out when the server timed them out
 * (no keep-alive answer), or when they sent nothing at all for lagOutQuietSeconds and then disconnected:
 * a small handler in each player's netty pipeline notes when anything last arrived from them.
 *
 * <p>Abuse is limited by lagOutsPerDay: past it, a dropped connection counts as quitting again. A fighter
 * who is killed while frozen, before their connection actually drops, still loses — that fight was
 * already decided.
 */
public final class LagOuts implements Listener {
    private static final String HANDLER = "spawnarena_watch";
    private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;

    private static final Map<UUID, Watcher> WATCHERS = new ConcurrentHashMap<>();
    /** When each player's recent lag-outs happened, for the daily allowance */
    private static final Map<UUID, Deque<Long>> RECENT = new ConcurrentHashMap<>();
    private static volatile boolean watchingBroken;

    /** Notes when the player last sent anything. Runs on their connection's netty thread. */
    private static final class Watcher extends ChannelInboundHandlerAdapter {
        volatile long lastReadNanos = System.nanoTime();

        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
            this.lastReadNanos = System.nanoTime();
            super.channelRead(ctx, msg);
        }
    }

    // ── Deciding ────────────────────────────────────────────────────────────

    private static boolean enabled() {
        return SpawnArena.settings == null || SpawnArena.settings.lagOutsPerDay > 0;
    }

    /** Whether this quit looks like their connection dropped rather than them leaving. */
    public static boolean looksLikeLagOut(final PlayerQuitEvent e) {
        if (!enabled())
            return false;
        if (e.getReason() == PlayerQuitEvent.QuitReason.TIMED_OUT)
            return true;
        // A dropped connection shows up as a disconnect or, when it's reset, an "erroneous state".
        // Kicked (anticheat, staff...) is never lag.
        if (e.getReason() != PlayerQuitEvent.QuitReason.DISCONNECTED && e.getReason() != PlayerQuitEvent.QuitReason.ERRONEOUS_STATE)
            return false;
        final double seconds = SpawnArena.settings == null ? 3 : SpawnArena.settings.lagOutQuietSeconds;
        final long quiet = quietMillis(e.getPlayer());
        return seconds > 0 && quiet >= 0 && quiet >= seconds * 1000;
    }

    /**
     * Uses up one of the player's lag-outs for today, if they have one left. Duels don't use one up
     * (nothing's at stake in them).
     */
    public static boolean takeAllowance(final Player player, final Fight fight) {
        if (!enabled())
            return false;
        if (fight.isDuel())
            return true;
        final int perDay = SpawnArena.settings == null ? 2 : SpawnArena.settings.lagOutsPerDay;
        final long now = System.currentTimeMillis();
        final Deque<Long> recent = RECENT.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
        synchronized (recent) {
            while (!recent.isEmpty() && now - recent.peekFirst() >= DAY_MILLIS)
                recent.pollFirst();
            if (recent.size() >= perDay)
                return false;
            recent.addLast(now);
            return true;
        }
    }

    /** Milliseconds since this player last sent anything, or -1 if we can't tell. */
    public static long quietMillis(final Player player) {
        final Watcher watcher = WATCHERS.get(player.getUniqueId());
        return watcher == null ? -1 : (System.nanoTime() - watcher.lastReadNanos) / 1_000_000;
    }

    // ── Watching connections ────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(final PlayerJoinEvent e) {
        watch(e.getPlayer());
    }

    // MONITOR: after ArenaPlayerLeave (NORMAL) has asked how long they'd been quiet
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent e) {
        WATCHERS.remove(e.getPlayer().getUniqueId());
    }

    /** On startup (or a reload): watches everyone already online. */
    public static void watchOnline() {
        for (final Player player : Bukkit.getOnlinePlayers())
            watch(player);
    }

    /** On shutdown: takes our handler back out of every connection. */
    public static void unwatchAll() {
        for (final Player player : Bukkit.getOnlinePlayers()) {
            try {
                final Channel channel = channelOf(player);
                if (channel != null)
                    channel.eventLoop().execute(() -> {
                        if (channel.pipeline().get(HANDLER) != null)
                            channel.pipeline().remove(HANDLER);
                    });
            } catch (final ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // their connection is going away with the server anyway
            }
        }
        WATCHERS.clear();
    }

    private static void watch(final Player player) {
        if (watchingBroken || !enabled())
            return;
        try {
            final Channel channel = channelOf(player);
            if (channel == null)
                return;
            final Watcher watcher = new Watcher();
            WATCHERS.put(player.getUniqueId(), watcher);
            channel.eventLoop().execute(() -> {
                try {
                    final ChannelPipeline pipeline = channel.pipeline();
                    if (pipeline.get(HANDLER) != null)
                        pipeline.remove(HANDLER);
                    pipeline.addFirst(HANDLER, watcher);
                } catch (final RuntimeException ignored) {
                    // the connection closed meanwhile
                }
            });
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            watchingBroken = true;
            WATCHERS.clear();
            SpawnArena.INSTANCE.getLogger().warning("Can't watch player connections (" + e
                    + "); lag-outs will only be recognised when the server times a player out.");
        }
    }

    /** The player's netty channel, found by type (server internals, so not by name). */
    private static Channel channelOf(final Player player) throws ReflectiveOperationException {
        final Object handle = player.getClass().getMethod("getHandle").invoke(player);
        final Object listener = fieldOfType(handle, "net.minecraft.server.network.ServerGamePacketListenerImpl");
        if (listener == null)
            return null;
        final Object connection = fieldOfType(listener, "net.minecraft.network.Connection");
        if (connection == null)
            return null;
        return fieldOfType(connection, Channel.class.getName()) instanceof Channel channel ? channel : null;
    }

    private static Object fieldOfType(final Object owner, final String typeName) throws ReflectiveOperationException {
        final Class<?> type = Class.forName(typeName, false, owner.getClass().getClassLoader());
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass())
            for (final Field field : c.getDeclaredFields())
                if (!Modifier.isStatic(field.getModifiers()) && type.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    return field.get(owner);
                }
        return null;
    }
}
