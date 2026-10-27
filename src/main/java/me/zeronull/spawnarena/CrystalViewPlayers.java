package me.zeronull.spawnarena;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * For {@link CrystalView}: players shown where they aren't (fighters on spawn, watchers in the copies), as packets only.
 * Each is a player entity with the real player's own UUID, so the viewer's game takes their skin and name from its tab
 * list and their name tag from the teams that name is on (TAB's rank prefix and suffix, the fight outline colour), and
 * it swings, gets hurt and pops totems like a player. Nothing exists on the server: it can't be hit, pushed or shot.
 * A fake goes before the real player can arrive in that world (two entities with one UUID wouldn't both show).
 */
final class CrystalViewPlayers {
    private static boolean broken;
    private static Constructor<?> addPacket, dataPacket, syncPacket, removePacket, equipmentPacket, hurtPacket, animatePacket, headPacket, eventPacket;
    private static Method getHandle, packAll, getEntityData, positionOf, asNmsCopy, pairOf, sendPacket;
    private static Field animateId, headId, eventId;
    private static Class<?> packetClass;
    private static Object playerType, zero;
    private static Object[] slots; // NMS equipment slots, in the order of GEAR

    private static final org.bukkit.inventory.EquipmentSlot[] GEAR = {org.bukkit.inventory.EquipmentSlot.HAND,
            org.bukkit.inventory.EquipmentSlot.OFF_HAND, org.bukkit.inventory.EquipmentSlot.FEET,
            org.bukkit.inventory.EquipmentSlot.LEGS, org.bukkit.inventory.EquipmentSlot.CHEST, org.bukkit.inventory.EquipmentSlot.HEAD};
    private static final String[] NMS_SLOTS = {"MAINHAND", "OFFHAND", "FEET", "LEGS", "CHEST", "HEAD"};

    private static final class Fake {
        final int id = Bukkit.getUnsafe().nextEntityId();
        double x, y, z;
        float yaw, pitch;
        ItemStack[] gear = new ItemStack[GEAR.length];
    }

    /** Real player -> their fake here */
    private final Map<UUID, Fake> fakes = new HashMap<>();
    private final Set<UUID> viewers = new HashSet<>();
    private long ticks;

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
            final Class<?> slot = Class.forName("net.minecraft.world.entity.EquipmentSlot", true, cl);
            packetClass = Class.forName("net.minecraft.network.protocol.Packet", true, cl);
            final String game = "net.minecraft.network.protocol.game.";
            addPacket = Class.forName(game + "ClientboundAddEntityPacket", true, cl).getConstructor(int.class, UUID.class,
                    double.class, double.class, double.class, float.class, float.class, entityType, int.class, vec3, double.class);
            dataPacket = Class.forName(game + "ClientboundSetEntityDataPacket", true, cl).getConstructor(int.class, List.class);
            syncPacket = Class.forName(game + "ClientboundEntityPositionSyncPacket", true, cl).getConstructor(int.class, pmr, boolean.class);
            removePacket = Class.forName(game + "ClientboundRemoveEntitiesPacket", true, cl).getConstructor(int[].class);
            equipmentPacket = Class.forName(game + "ClientboundSetEquipmentPacket", true, cl).getConstructor(int.class, List.class);
            hurtPacket = Class.forName(game + "ClientboundHurtAnimationPacket", true, cl).getConstructor(int.class, float.class);
            // These only take the entity they're about: made for the real player, then pointed at the fake
            final Class<?> animate = Class.forName(game + "ClientboundAnimatePacket", true, cl);
            animatePacket = animate.getConstructor(nmsEntity, int.class);
            animateId = field(animate, "id");
            final Class<?> head = Class.forName(game + "ClientboundRotateHeadPacket", true, cl);
            headPacket = head.getConstructor(nmsEntity, byte.class);
            headId = field(head, "entityId");
            final Class<?> event = Class.forName(game + "ClientboundEntityEventPacket", true, cl);
            eventPacket = event.getConstructor(nmsEntity, byte.class);
            eventId = field(event, "entityId");
            packAll = synched.getMethod("packAll");
            getEntityData = nmsEntity.getMethod("getEntityData");
            positionOf = pmr.getMethod("of", nmsEntity);
            asNmsCopy = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack", true, cl).getMethod("asNMSCopy", ItemStack.class);
            pairOf = Class.forName("com.mojang.datafixers.util.Pair", true, cl).getMethod("of", Object.class, Object.class);
            playerType = entityType.getField("PLAYER").get(null);
            zero = vec3.getField("ZERO").get(null);
            slots = new Object[NMS_SLOTS.length];
            for (int i = 0; i < NMS_SLOTS.length; i++)
                slots[i] = slot.getField(NMS_SLOTS[i]).get(null);
            return true;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            broken = true;
            addPacket = null;
            SpawnArena.INSTANCE.getLogger().warning("Couldn't show crystal arena players in other worlds (" + e + ").");
            return false;
        }
    }

    private static Field field(final Class<?> type, final String name) throws NoSuchFieldException {
        final Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static Object handle(final Object bukkit) throws ReflectiveOperationException {
        if (getHandle == null || !getHandle.getDeclaringClass().isInstance(bukkit))
            getHandle = bukkit.getClass().getMethod("getHandle");
        return getHandle.invoke(bukkit);
    }

    private static void send(final Player viewer, final Object packet) throws ReflectiveOperationException {
        final Object player = handle(viewer);
        final Object connection = player.getClass().getField("connection").get(player);
        if (sendPacket == null)
            sendPacket = connection.getClass().getMethod("send", packetClass);
        sendPacket.invoke(connection, packet);
    }

    private void sendAll(final Object packet) throws ReflectiveOperationException {
        for (final UUID id : this.viewers) {
            final Player viewer = Bukkit.getPlayer(id);
            if (viewer != null)
                send(viewer, packet);
        }
    }

    /** Every tick: {@code shown} appear (at the same coordinates) to {@code to}, and only them. */
    void update(final List<Player> shown, final List<Player> to) {
        if (!ready())
            return;
        this.ticks++;
        try {
            final Map<UUID, Player> now = new HashMap<>();
            for (final Player player : shown)
                now.put(player.getUniqueId(), player);
            // Gone: away from everyone who has them
            final List<Integer> gone = new ArrayList<>();
            this.fakes.entrySet().removeIf(e -> {
                if (now.containsKey(e.getKey()))
                    return false;
                gone.add(e.getValue().id);
                return true;
            });
            if (!gone.isEmpty())
                this.sendAll(removal(gone));

            // Viewers who left: theirs taken away; new ones get everyone
            final Set<UUID> viewing = new HashSet<>();
            for (final Player viewer : to)
                viewing.add(viewer.getUniqueId());
            for (final UUID id : this.viewers)
                if (!viewing.contains(id)) {
                    final Player player = Bukkit.getPlayer(id);
                    if (player != null && !this.fakes.isEmpty())
                        send(player, removal(this.fakes.values().stream().map(f -> f.id).toList()));
                }
            final Set<UUID> fresh = new HashSet<>(viewing);
            fresh.removeAll(this.viewers);
            this.viewers.clear();
            this.viewers.addAll(viewing);

            for (final Player player : shown) {
                Fake fake = this.fakes.get(player.getUniqueId());
                final boolean isNew = fake == null;
                if (isNew) {
                    fake = new Fake();
                    this.fakes.put(player.getUniqueId(), fake);
                }
                final Location at = player.getLocation();
                final boolean moved = at.getX() != fake.x || at.getY() != fake.y || at.getZ() != fake.z || at.getYaw() != fake.yaw || at.getPitch() != fake.pitch;
                fake.x = at.getX();
                fake.y = at.getY();
                fake.z = at.getZ();
                fake.yaw = at.getYaw();
                fake.pitch = at.getPitch();
                final boolean gearChanged = this.gearChanged(player, fake);
                for (final Player viewer : to) {
                    if (viewer.getUniqueId().equals(player.getUniqueId()))
                        continue; // never yourself
                    if (isNew || fresh.contains(viewer.getUniqueId())) {
                        for (final Object packet : this.spawnPackets(player, fake))
                            send(viewer, packet);
                        continue;
                    }
                    if (moved) {
                        send(viewer, this.sync(player, fake));
                        send(viewer, this.head(player, fake));
                    }
                    if (gearChanged)
                        send(viewer, this.equipment(fake));
                    if (this.ticks % 5 == 0) // sneaking, glowing, pose...
                        send(viewer, this.data(player, fake));
                }
            }
        } catch (final ReflectiveOperationException | RuntimeException e) {
            broken = true;
            SpawnArena.INSTANCE.getLogger().warning("Stopped showing crystal arena players in other worlds: " + e);
            this.close();
        }
    }

    private boolean gearChanged(final Player player, final Fake fake) {
        final EntityEquipment equipment = player.getEquipment();
        boolean changed = false;
        for (int i = 0; i < GEAR.length; i++) {
            final ItemStack item = equipment.getItem(GEAR[i]);
            if (!Objects.equals(item, fake.gear[i])) {
                fake.gear[i] = item.clone();
                changed = true;
            }
        }
        return changed;
    }

    private List<Object> spawnPackets(final Player player, final Fake fake) throws ReflectiveOperationException {
        final Location at = player.getLocation();
        return List.of(
                addPacket.newInstance(fake.id, player.getUniqueId(), at.getX(), at.getY(), at.getZ(), at.getPitch(), at.getYaw(),
                        playerType, 0, zero, (double) at.getYaw()),
                this.data(player, fake),
                this.equipment(fake),
                this.head(player, fake));
    }

    private Object data(final Player player, final Fake fake) throws ReflectiveOperationException {
        return dataPacket.newInstance(fake.id, packAll.invoke(getEntityData.invoke(handle(player))));
    }

    private Object sync(final Player player, final Fake fake) throws ReflectiveOperationException {
        return syncPacket.newInstance(fake.id, positionOf.invoke(null, handle(player)), player.isOnGround());
    }

    private Object head(final Player player, final Fake fake) throws ReflectiveOperationException {
        final Object packet = headPacket.newInstance(handle(player), (byte) Math.floor(player.getLocation().getYaw() * 256f / 360f));
        headId.setInt(packet, fake.id);
        return packet;
    }

    private Object equipment(final Fake fake) throws ReflectiveOperationException {
        final List<Object> list = new ArrayList<>(GEAR.length);
        for (int i = 0; i < GEAR.length; i++)
            list.add(pairOf.invoke(null, slots[i], asNmsCopy.invoke(null, fake.gear[i] == null ? new ItemStack(org.bukkit.Material.AIR) : fake.gear[i])));
        return equipmentPacket.newInstance(fake.id, list);
    }

    private static Object removal(final Collection<Integer> ids) throws ReflectiveOperationException {
        return removePacket.newInstance((Object) ids.stream().mapToInt(Integer::intValue).toArray());
    }

    /** Their arm swing (main or off hand), to everyone who sees their fake here. */
    void swing(final Player player, final boolean offHand) {
        final Fake fake = this.fakes.get(player.getUniqueId());
        if (fake == null || addPacket == null)
            return;
        try {
            final Object packet = animatePacket.newInstance(handle(player), offHand ? 3 : 0);
            animateId.setInt(packet, fake.id);
            this.sendAll(packet);
        } catch (final ReflectiveOperationException | RuntimeException ignored) {
            // just no swing
        }
    }

    void hurt(final Player player) {
        final Fake fake = this.fakes.get(player.getUniqueId());
        if (fake == null || addPacket == null)
            return;
        try {
            this.sendAll(hurtPacket.newInstance(fake.id, player.getLocation().getYaw()));
        } catch (final ReflectiveOperationException | RuntimeException ignored) {
            // just no red flash
        }
    }

    /** The totem animation (entity event 35) on their fake. */
    void totem(final Player player) {
        final Fake fake = this.fakes.get(player.getUniqueId());
        if (fake == null || addPacket == null)
            return;
        try {
            final Object packet = eventPacket.newInstance(handle(player), (byte) 35);
            eventId.setInt(packet, fake.id);
            this.sendAll(packet);
        } catch (final ReflectiveOperationException | RuntimeException ignored) {
            // just no totem
        }
    }

    /** This player's fake goes now (they're about to arrive where it is shown, or leaving the server). */
    void forget(final UUID player) {
        final Fake fake = this.fakes.remove(player);
        if (fake == null || addPacket == null)
            return;
        try {
            this.sendAll(removal(List.of(fake.id)));
        } catch (final ReflectiveOperationException | RuntimeException ignored) {
            // their game drops it with the chunk
        }
    }

    void close() {
        if (addPacket != null && !this.fakes.isEmpty())
            try {
                this.sendAll(removal(this.fakes.values().stream().map(f -> f.id).toList()));
            } catch (final ReflectiveOperationException | RuntimeException ignored) {
                // their games drop them with the chunks
            }
        this.fakes.clear();
        this.viewers.clear();
    }
}
