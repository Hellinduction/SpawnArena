package me.zeronull.spawnarena;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a replay's viewer is sent, sifted while they watch (see {@link ArenaReplay}): in the arena (its region and a few
 * blocks round it), live games' sounds, particles, explosions, block cracks and block events are dropped, and so are
 * live block changes where a live game has a snapshot to say what the arena really is (the replay shows that instead,
 * told of each spot through {@link Owner#liveChanged}). The replay's own sounds, particles and blocks get through: it says
 * where each goes just before sending it ({@link #own}, {@link #ownBlock}). Anything it can't read is let through.
 */
final class ReplayFilter extends ChannelDuplexHandler {
    static final String NAME = "spawnarena_replay";

    /** The replay this sifts for. */
    interface Owner {
        /** Whether this spot is in the arena (blocks, or near enough for sounds and particles). */
        boolean inArena(double x, double y, double z);

        /** A live game changed this block: dropped, so the replay should show what it wants there. True if it can. */
        boolean liveChanged(int x, int y, int z);
    }

    private final Owner owner;
    /** Where the replay is about to play a sound or particle, and until when (ms) that's let through */
    private final Map<Long, Long> ownEffects = new ConcurrentHashMap<>();
    /** Blocks the replay is about to send, and until when (ms) changes there are let through */
    private final Map<Long, Long> ownBlocks = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method[]> POSITIONS = new ConcurrentHashMap<>();

    private ReplayFilter(final Owner owner) {
        this.owner = owner;
    }

    private static long key(final int x, final int y, final int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** The replay is about to play a sound or particle here. */
    void own(final double x, final double y, final double z) {
        this.ownEffects.put(key((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)), System.currentTimeMillis() + 2000);
    }

    /** The replay is about to send this block. */
    void ownBlock(final int x, final int y, final int z) {
        this.ownBlocks.put(key(x, y, z), System.currentTimeMillis() + 2000);
    }

    private static boolean live(final Map<Long, Long> own, final long key) {
        final Long until = own.get(key);
        return until != null && until >= System.currentTimeMillis();
    }

    /** Puts a filter on the viewer's connection (any before it taken off). Null if it can't be. */
    static ReplayFilter install(final Player viewer, final Owner owner) {
        try {
            final Channel channel = LagOuts.channelOf(viewer);
            if (channel == null)
                return null;
            final ReplayFilter filter = new ReplayFilter(owner);
            channel.eventLoop().execute(() -> {
                try {
                    if (channel.pipeline().get(NAME) != null)
                        channel.pipeline().remove(NAME);
                    if (channel.pipeline().get("packet_handler") != null)
                        channel.pipeline().addBefore("packet_handler", NAME, filter);
                } catch (final RuntimeException ignored) {
                    // the connection closed meanwhile
                }
            });
            return filter;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Takes it off again (then whatever's sent goes straight through). */
    static void remove(final Player viewer) {
        try {
            final Channel channel = LagOuts.channelOf(viewer);
            if (channel != null)
                channel.eventLoop().execute(() -> {
                    try {
                        if (channel.pipeline().get(NAME) != null)
                            channel.pipeline().remove(NAME);
                    } catch (final RuntimeException ignored) {
                        // closed
                    }
                });
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // gone
        }
    }

    @Override
    public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
        boolean drop = false;
        try {
            drop = this.drop(msg);
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // can't read it: let it through
        }
        if (drop)
            promise.trySuccess();
        else
            super.write(ctx, msg, promise);
    }

    private boolean drop(final Object msg) throws ReflectiveOperationException {
        switch (msg.getClass().getSimpleName()) {
            case "ClientboundSoundPacket", "ClientboundLevelParticlesPacket" -> {
                final Method[] m = positions(msg.getClass(), "getX", "getY", "getZ");
                final double x = ((Number) m[0].invoke(msg)).doubleValue(), y = ((Number) m[1].invoke(msg)).doubleValue(),
                        z = ((Number) m[2].invoke(msg)).doubleValue();
                return this.owner.inArena(x, y, z) && !live(this.ownEffects, key((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
            }
            case "ClientboundExplodePacket" -> {
                final Object centre = positions(msg.getClass(), "center")[0].invoke(msg);
                final Method[] v = positions(centre.getClass(), "x", "y", "z");
                return this.owner.inArena(((Number) v[0].invoke(centre)).doubleValue(), ((Number) v[1].invoke(centre)).doubleValue(),
                        ((Number) v[2].invoke(centre)).doubleValue());
            }
            case "ClientboundLevelEventPacket", "ClientboundBlockDestructionPacket" -> {
                final int[] p = blockPos(positions(msg.getClass(), "getPos")[0].invoke(msg));
                return this.owner.inArena(p[0] + 0.5, p[1] + 0.5, p[2] + 0.5);
            }
            case "ClientboundBlockUpdatePacket" -> {
                final int[] p = blockPos(positions(msg.getClass(), "getPos")[0].invoke(msg));
                return this.liveBlock(p[0], p[1], p[2]);
            }
            case "ClientboundSectionBlocksUpdatePacket" -> {
                // Dropped if any of it is a live change the replay takes care of
                final boolean[] dropIt = {false};
                final Method run = positions(msg.getClass(), "runUpdates")[0];
                run.invoke(msg, (java.util.function.BiConsumer<Object, Object>) (pos, state) -> {
                    try {
                        final int[] p = blockPos(pos);
                        if (this.liveBlock(p[0], p[1], p[2]))
                            dropIt[0] = true;
                    } catch (final ReflectiveOperationException ignored) {
                        // can't read it
                    }
                });
                return dropIt[0];
            }
            default -> {
                return false;
            }
        }
    }

    /** A live game's change to a block in the arena, that the replay shows its own way instead. */
    private boolean liveBlock(final int x, final int y, final int z) {
        if (!this.owner.inArena(x + 0.5, y + 0.5, z + 0.5) || live(this.ownBlocks, key(x, y, z)))
            return false;
        return this.owner.liveChanged(x, y, z);
    }

    private static int[] blockPos(final Object pos) throws ReflectiveOperationException {
        final Method[] m = positions(pos.getClass(), "getX", "getY", "getZ");
        return new int[]{(int) m[0].invoke(pos), (int) m[1].invoke(pos), (int) m[2].invoke(pos)};
    }

    /** These methods of a class (no arguments, or for runUpdates its one), found once. */
    private static Method[] positions(final Class<?> type, final String... names) throws ReflectiveOperationException {
        Method[] found = POSITIONS.get(type);
        if (found == null || found.length != names.length || !found[0].getName().equals(names[0])) {
            found = new Method[names.length];
            for (int i = 0; i < names.length; i++)
                found[i] = method(type, names[i]);
            POSITIONS.put(type, found);
        }
        return found;
    }

    private static Method method(final Class<?> type, final String name) throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass())
            for (final Method m : c.getDeclaredMethods())
                if (m.getName().equals(name) && (m.getParameterCount() == 0 || name.equals("runUpdates"))) {
                    m.setAccessible(true);
                    return m;
                }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
}
