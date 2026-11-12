package me.zeronull.spawnarena;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;

/**
 * For {@link CrystalView}: the matches' other entities (end crystals, pearls, arrows, potions, dropped items...) shown
 * to the watchers on spawn, as packets only. Each gets an id of its own and is sent to the watchers' games alone, so
 * nothing on spawn can touch it (a fake crystal can't be set off, a fake item can't be picked up). They move with the
 * real ones and go when they do.
 *
 * <p>What's shown of each comes from a {@link Thing}: read straight from it (Paper), or captured on the thread of the
 * region it's in (Folia, see {@link CrystalView}). Everything here runs on the global thread (the main thread on Paper).
 */
final class CrystalViewEntities {
    private static boolean broken;
    private static Constructor<?> addPacket, dataPacket, syncPacket, removePacket;
    private static Method getHandle, getType, packAll, positionOf, sendPacket;
    private static Class<?> packetClass;
    private static Object zero;

    /**
     * What's needed of a match entity to show it: read straight from it ({@link #live}: Paper), or captured all at once
     * on its own thread ({@link #capture}: Folia).
     */
    static final class Thing {
        final UUID uuid;
        private final Entity live;
        private final Location at;
        private final boolean onGround;
        private final Object type, position, data;

        private Thing(final UUID uuid, final Entity live, final Location at, final boolean onGround, final Object type,
                      final Object position, final Object data) {
            this.uuid = uuid;
            this.live = live;
            this.at = at;
            this.onGround = onGround;
            this.type = type;
            this.position = position;
            this.data = data;
        }

        static Thing live(final Entity entity) {
            return new Thing(entity.getUniqueId(), entity, null, false, null, null, null);
        }

        /** Everything about it now, or null if it isn't one that's shown (or it can't be read). On its own thread. */
        static Thing capture(final Entity entity) {
            if (!shown(entity) || !ready())
                return null;
            try {
                final Object nms = handle(entity);
                return new Thing(entity.getUniqueId(), null, entity.getLocation(), entity.isOnGround(), getType.invoke(nms),
                        positionOf.invoke(null, nms), packAll.invoke(getEntityData.invoke(nms)));
            } catch (final ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }

        Location at() {
            return this.live != null ? this.live.getLocation() : this.at;
        }

        boolean onGround() {
            return this.live != null ? this.live.isOnGround() : this.onGround;
        }

        Object type() throws ReflectiveOperationException {
            return this.live != null ? getType.invoke(handle(this.live)) : this.type;
        }

        Object position() throws ReflectiveOperationException {
            return this.live != null ? positionOf.invoke(null, handle(this.live)) : this.position;
        }

        Object data() throws ReflectiveOperationException {
            return this.live != null ? packAll.invoke(getEntityData.invoke(handle(this.live))) : this.data;
        }
    }

    /** Paper: what's in these copies now, read straight from them (on the main thread). */
    static List<Thing> liveIn(final Collection<CrystalCopy> copies) {
        final List<Thing> out = new ArrayList<>();
        for (final CrystalCopy copy : copies)
            for (final Entity entity : copy.world().getEntities())
                if (shown(entity))
                    out.add(Thing.live(entity));
        return out;
    }

    /**
     * A match entity shown on spawn: its own id there, and where it was last sent to. Its UUID is the real one's (which
     * a watcher's game never has: it's in another world), so a glowing projectile's team colours the fake too.
     */
    private static final class Fake {
        final int id = Bukkit.getUnsafe().nextEntityId();
        final UUID uuid;
        Thing real;
        double x, y, z;
        float yaw, pitch;

        Fake(final Thing real) {
            this.real = real;
            this.uuid = real.uuid;
        }
    }

    /** Real entity (in a copy) -> its fake */
    private final Map<UUID, Fake> fakes = new HashMap<>();
    /** Who has them */
    private final Set<UUID> viewers = new HashSet<>();

    static boolean ready() {
        if (addPacket != null)
            return true;
        if (broken)
            return false;
        try {
            final ClassLoader cl = Bukkit.getServer().getClass().getClassLoader();
            final Class<?> nmsEntity = Class.forName("net.minecraft.world.entity.Entity", true, cl);
            final Class<?> entityType = Class.forName("net.minecraft.world.entity.EntityType", true, cl);
            final Class<?> vec3 = Class.forName("net.minecraft.world.phys.Vec3", true, cl);
            final Class<?> pmr = Class.forName("net.minecraft.world.entity.PositionMoveRotation", true, cl);
            final Class<?> synched = Class.forName("net.minecraft.network.syncher.SynchedEntityData", true, cl);
            packetClass = Class.forName("net.minecraft.network.protocol.Packet", true, cl);
            final String game = "net.minecraft.network.protocol.game.";
            addPacket = Class.forName(game + "ClientboundAddEntityPacket", true, cl).getConstructor(int.class, UUID.class,
                    double.class, double.class, double.class, float.class, float.class, entityType, int.class, vec3, double.class);
            dataPacket = Class.forName(game + "ClientboundSetEntityDataPacket", true, cl).getConstructor(int.class, List.class);
            syncPacket = Class.forName(game + "ClientboundEntityPositionSyncPacket", true, cl).getConstructor(int.class, pmr, boolean.class);
            removePacket = Class.forName(game + "ClientboundRemoveEntitiesPacket", true, cl).getConstructor(int[].class);
            getType = nmsEntity.getMethod("getType");
            packAll = synched.getMethod("packAll");
            positionOf = pmr.getMethod("of", nmsEntity);
            zero = vec3.getField("ZERO").get(null);
            getEntityData = nmsEntity.getMethod("getEntityData");
            return true;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            broken = true;
            addPacket = null;
            SpawnArena.INSTANCE.getLogger().warning("Couldn't show crystal arena matches' crystals and projectiles on spawn (" + e + ").");
            return false;
        }
    }

    private static Method getEntityData;

    private static Object handle(final Object bukkit) throws ReflectiveOperationException {
        if (getHandle == null || !getHandle.getDeclaringClass().isInstance(bukkit))
            getHandle = bukkit.getClass().getMethod("getHandle");
        return getHandle.invoke(bukkit);
    }

    private static void send(final Player viewer, final Object packet) throws ReflectiveOperationException {
        final Object player = viewer.getClass().getMethod("getHandle").invoke(viewer);
        final Object connection = player.getClass().getField("connection").get(player);
        if (sendPacket == null)
            sendPacket = connection.getClass().getMethod("send", packetClass);
        sendPacket.invoke(connection, packet);
    }

    static boolean shown(final Entity entity) {
        return !(entity instanceof Player) && !(entity instanceof Mannequin) && entity.isValid();
    }

    /** Every tick: what's in the live copies now ({@code things}), sent to the watchers. */
    void tick(final List<Thing> things, final List<Player> watchers) {
        if (!ready())
            return;
        try {
            final List<Object> spawn = new ArrayList<>(), move = new ArrayList<>();
            final List<Integer> gone = new ArrayList<>();
            final Set<UUID> present = new HashSet<>();
            for (final Thing thing : things) {
                if (!present.add(thing.uuid))
                    continue; // (captured twice: once is enough)
                Fake fake = this.fakes.get(thing.uuid);
                final Location at = thing.at();
                if (fake == null) {
                    fake = new Fake(thing);
                    this.fakes.put(thing.uuid, fake);
                    spawn.addAll(spawnPackets(fake));
                } else {
                    fake.real = thing;
                    if (at.getX() != fake.x || at.getY() != fake.y || at.getZ() != fake.z || at.getYaw() != fake.yaw || at.getPitch() != fake.pitch)
                        move.add(syncPacket.newInstance(fake.id, thing.position(), thing.onGround()));
                }
                fake.x = at.getX();
                fake.y = at.getY();
                fake.z = at.getZ();
                fake.yaw = at.getYaw();
                fake.pitch = at.getPitch();
            }
            for (final Iterator<Map.Entry<UUID, Fake>> it = this.fakes.entrySet().iterator(); it.hasNext(); ) {
                final Map.Entry<UUID, Fake> e = it.next();
                if (!present.contains(e.getKey())) {
                    gone.add(e.getValue().id);
                    it.remove();
                }
            }
            final Object removal = gone.isEmpty() ? null : removePacket.newInstance((Object) gone.stream().mapToInt(Integer::intValue).toArray());

            final Set<UUID> now = new HashSet<>();
            for (final Player watcher : watchers) {
                now.add(watcher.getUniqueId());
                if (!this.viewers.contains(watcher.getUniqueId())) {
                    // New here: everything there is
                    for (final Fake fake : this.fakes.values())
                        for (final Object packet : spawnPackets(fake))
                            send(watcher, packet);
                    continue;
                }
                if (removal != null)
                    send(watcher, removal);
                for (final Object packet : spawn)
                    send(watcher, packet);
                for (final Object packet : move)
                    send(watcher, packet);
            }
            // Gone from here: theirs are taken away
            for (final UUID id : this.viewers) {
                if (now.contains(id))
                    continue;
                final Player player = Bukkit.getPlayer(id);
                if (player != null)
                    this.removeAllFor(player, gone);
            }
            this.viewers.clear();
            this.viewers.addAll(now);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            broken = true;
            SpawnArena.INSTANCE.getLogger().warning("Stopped showing crystal arena matches' crystals and projectiles on spawn: " + e);
            this.close();
        }
    }

    private List<Object> spawnPackets(final Fake fake) throws ReflectiveOperationException {
        final Location at = fake.real.at();
        final List<Object> out = new ArrayList<>(3);
        out.add(addPacket.newInstance(fake.id, fake.uuid, at.getX(), at.getY(), at.getZ(), at.getPitch(), at.getYaw(),
                fake.real.type(), 0, zero, (double) at.getYaw()));
        final Object data = fake.real.data();
        if (data instanceof List<?> list && !list.isEmpty())
            out.add(dataPacket.newInstance(fake.id, list));
        out.add(syncPacket.newInstance(fake.id, fake.real.position(), fake.real.onGround()));
        return out;
    }

    private void removeAllFor(final Player player, final List<Integer> alsoGone) {
        if (addPacket == null)
            return;
        try {
            final List<Integer> ids = new ArrayList<>(alsoGone);
            for (final Fake fake : this.fakes.values())
                ids.add(fake.id);
            if (!ids.isEmpty())
                send(player, removePacket.newInstance((Object) ids.stream().mapToInt(Integer::intValue).toArray()));
        } catch (final ReflectiveOperationException | RuntimeException ignored) {
            // they'll go when the chunk does
        }
    }

    /** Nothing shown any more (the arena's view closes, or the server stops). */
    void close() {
        for (final UUID id : this.viewers) {
            final Player player = Bukkit.getPlayer(id);
            if (player != null)
                this.removeAllFor(player, List.of());
        }
        this.viewers.clear();
        this.fakes.clear();
    }
}
