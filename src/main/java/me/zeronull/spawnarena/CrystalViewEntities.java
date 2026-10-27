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
 */
final class CrystalViewEntities {
    private static boolean broken;
    private static Constructor<?> addPacket, dataPacket, syncPacket, removePacket;
    private static Method getHandle, getType, packAll, positionOf, sendPacket;
    private static Class<?> packetClass;
    private static Object zero;

    /** A match entity shown on spawn: its own id there, and where it was last sent to. */
    private static final class Fake {
        final int id = Bukkit.getUnsafe().nextEntityId();
        final UUID uuid = UUID.randomUUID();
        final Entity real;
        double x, y, z;
        float yaw, pitch;

        Fake(final Entity real) {
            this.real = real;
        }
    }

    /** Real entity (in a copy) -> its fake */
    private final Map<UUID, Fake> fakes = new HashMap<>();
    /** Who has them */
    private final Set<UUID> viewers = new HashSet<>();

    private static boolean ready() {
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

    private static boolean shown(final Entity entity) {
        return !(entity instanceof Player) && !(entity instanceof Mannequin) && entity.isValid();
    }

    /** Every tick: what's in the live copies now, sent to the watchers. */
    void tick(final Collection<CrystalCopy> live, final List<Player> watchers) {
        if (!ready())
            return;
        try {
            final List<Object> spawn = new ArrayList<>(), move = new ArrayList<>();
            final List<Integer> gone = new ArrayList<>();
            final Set<UUID> present = new HashSet<>();
            for (final CrystalCopy copy : live)
                for (final Entity entity : copy.world().getEntities()) {
                    if (!shown(entity))
                        continue;
                    present.add(entity.getUniqueId());
                    Fake fake = this.fakes.get(entity.getUniqueId());
                    final Location at = entity.getLocation();
                    if (fake == null) {
                        fake = new Fake(entity);
                        this.fakes.put(entity.getUniqueId(), fake);
                        spawn.addAll(spawnPackets(fake));
                    } else if (at.getX() != fake.x || at.getY() != fake.y || at.getZ() != fake.z || at.getYaw() != fake.yaw || at.getPitch() != fake.pitch) {
                        move.add(syncPacket.newInstance(fake.id, positionOf.invoke(null, handle(entity)), entity.isOnGround()));
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
        final Object nms = handle(fake.real);
        final Location at = fake.real.getLocation();
        final List<Object> out = new ArrayList<>(3);
        out.add(addPacket.newInstance(fake.id, fake.uuid, at.getX(), at.getY(), at.getZ(), at.getPitch(), at.getYaw(),
                getType.invoke(nms), 0, zero, (double) at.getYaw()));
        final Object data = packAll.invoke(getEntityData.invoke(nms));
        if (data instanceof List<?> list && !list.isEmpty())
            out.add(dataPacket.newInstance(fake.id, list));
        out.add(syncPacket.newInstance(fake.id, positionOf.invoke(null, nms), fake.real.isOnGround()));
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
