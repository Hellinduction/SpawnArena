package me.zeronull.spawnarena;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.lang.reflect.Method;

/**
 * In a MULTI arena the matches can't see each other, but sounds are played to everyone nearby: a fighter would hear
 * the other matches' bows, hits and footsteps. A small handler in each player's connection drops a sound when its
 * receiver is fighting in a MULTI arena and the sound comes from (right by) a fighter of another match there.
 */
public final class MatchSounds implements Listener {
    private static final String HANDLER = "spawnarena_sounds";
    /** How close to a fighter a sound has to be to count as theirs. */
    private static final double NEAR = 4;
    private static volatile boolean broken;

    private static volatile Method getX, getY, getZ;

    private static final class Filter extends ChannelDuplexHandler {
        private final Player player;

        Filter(final Player player) {
            this.player = player;
        }

        @Override
        public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
            if (msg != null && msg.getClass().getSimpleName().equals("ClientboundSoundPacket") && fromOtherMatch(this.player, msg)) {
                promise.setSuccess();
                return;
            }
            super.write(ctx, msg, promise);
        }
    }

    /** Whether this sound comes from a fighter of another match in the MULTI arena the receiver is fighting in. */
    private static boolean fromOtherMatch(final Player receiver, final Object packet) {
        try {
            if (SpawnArena.arenas == null || !SpawnArena.arenas.hasActiveFight())
                return false;
            final Arena arena = SpawnArena.arenas.of(receiver);
            if (arena == null || arena.getArenaMode() != ArenaMode.MULTI)
                return false;
            final Fight own = arena.getFight(receiver);
            if (own == null)
                return false;
            if (getX == null) {
                getX = packet.getClass().getMethod("getX");
                getY = packet.getClass().getMethod("getY");
                getZ = packet.getClass().getMethod("getZ");
            }
            final double x = (double) getX.invoke(packet), y = (double) getY.invoke(packet), z = (double) getZ.invoke(packet);
            final Location at = new Location(receiver.getWorld(), x, y, z);

            // Whose is it: the nearest fighter in this arena, if any is right by it
            Fight nearest = null;
            double best = NEAR * NEAR;
            for (final Fight fight : arena.getFights())
                for (final Player p : fight.getMembers()) {
                    if (p.getWorld() != at.getWorld())
                        continue;
                    final double d = p.getLocation().distanceSquared(at);
                    if (d <= best) {
                        best = d;
                        nearest = fight;
                    }
                }
            return nearest != null && nearest != own;
        } catch (final Throwable t) {
            return false; // never let a sound check break someone's connection
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent e) {
        install(e.getPlayer());
    }

    public static void installOnline() {
        for (final Player player : Bukkit.getOnlinePlayers())
            install(player);
    }

    public static void uninstallAll() {
        for (final Player player : Bukkit.getOnlinePlayers()) {
            try {
                final Channel channel = LagOuts.channelOf(player);
                if (channel != null)
                    channel.eventLoop().execute(() -> {
                        if (channel.pipeline().get(HANDLER) != null)
                            channel.pipeline().remove(HANDLER);
                    });
            } catch (final ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // going away with the server anyway
            }
        }
    }

    private static void install(final Player player) {
        if (broken)
            return;
        try {
            final Channel channel = LagOuts.channelOf(player);
            if (channel == null)
                return;
            channel.eventLoop().execute(() -> {
                try {
                    if (channel.pipeline().get(HANDLER) != null)
                        channel.pipeline().remove(HANDLER);
                    // Between the game's own handler and the encoder: it sees the packets themselves
                    if (channel.pipeline().get("packet_handler") != null)
                        channel.pipeline().addBefore("packet_handler", HANDLER, new Filter(player));
                } catch (final RuntimeException ignored) {
                    // the connection closed meanwhile
                }
            });
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            broken = true;
            SpawnArena.INSTANCE.getLogger().warning("Can't filter match sounds (" + e + "); in MULTI arenas, fighters will hear other matches.");
        }
    }
}
