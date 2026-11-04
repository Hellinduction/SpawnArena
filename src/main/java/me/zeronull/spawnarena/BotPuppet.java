package me.zeronull.spawnarena;

import org.bukkit.Bukkit;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a sumo bot's opponent sees of it: a player, as packets only, that follows the bot's (hidden) Mannequin every
 * tick. A Mannequin's arm never moves on the client (only a player's swing animates), so the bot is drawn as a player:
 * its borrowed skin, what it holds, its crouch, its swings and its hurt flash. Hits on it are passed on to the
 * Mannequin by {@link SumoBotListener}. Its own name tag is hidden (with it, the line a below-name scoreboard puts
 * under every player, which a bot has no score for, so it would be blank): "Bot (Medium)" is a text display above it.
 */
final class BotPuppet {
    private static boolean broken;
    private static Constructor<?> addPacket, dataPacket, syncPacket, removePacket, equipmentPacket, hurtPacket, animatePacket, headPacket,
            infoPacket, infoEntry, infoRemovePacket, teamCtor, scoreboardCtor;
    private static Method getHandle, packAll, getEntityData, positionOf, asNmsCopy, pairOf, sendPacket, dataId, authlibCopy,
            asVanilla, teamPacket, teamRemovePacket, setSuffix, setColour, teamPlayers, formattingByName, setNameTagVisibility, dataValue;
    private static Constructor<?> positionCtor, vec3Ctor;
    private static Object textDisplayType, hiddenTag, textData, billboardData, interpolationData;
    private static Field animateId, headId;
    private static Class<?> packetClass;
    private static Object playerType, zero, addPlayer, survival;
    private static Object[] slots;
    /** The highest entity data id a player and a Mannequin share (Avatar's); the Mannequin's own ones would break a player. */
    private static int lastShared;

    /** How far above its feet its name is shown */
    private static final double LABEL_Y = 2.1;
    private static final EquipmentSlot[] GEAR = {EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.FEET,
            EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD};
    private static final String[] NMS_SLOTS = {"MAINHAND", "OFFHAND", "FEET", "LEGS", "CHEST", "HEAD"};
    /** Fake entity id -> its bot, so a hit on it reaches the bot. */
    private static final Map<Integer, SumoBot> BY_ID = new ConcurrentHashMap<>();
    /** For team names, one per puppet. */
    private static final java.util.concurrent.atomic.AtomicInteger TEAMS = new java.util.concurrent.atomic.AtomicInteger();

    final int id = Bukkit.getUnsafe().nextEntityId();
    /** Its name, a text display above its head */
    private final int labelId = Bukkit.getUnsafe().nextEntityId();
    private boolean labelLow;
    private final UUID uuid = UUID.randomUUID();
    private final Player viewer;
    private final SumoBot bot;
    private final Object team;
    private double x, y, z;
    private float yaw, pitch;
    private final ItemStack[] gear = new ItemStack[GEAR.length];
    private long ticks;
    private volatile boolean shown;

    private BotPuppet(final Player viewer, final SumoBot bot, final Object team) {
        this.viewer = viewer;
        this.bot = bot;
        this.team = team;
    }

    static SumoBot botOf(final int entityId) {
        return BY_ID.get(entityId);
    }

    /** A puppet of {@code body} for {@code viewer}, or null if it can't be drawn (they're then shown the Mannequin itself). */
    static BotPuppet show(final Player viewer, final SumoBot bot, final Mannequin body, final com.destroystokyo.paper.profile.PlayerProfile skin,
                          final BotDifficulty level) {
        if (!ready())
            return null;
        try {
            final String name = "Bot";
            final Object team = teamCtor.newInstance(scoreboardCtor.newInstance(), "sa_bot_" + Integer.toHexString(TEAMS.incrementAndGet()));
            final BotPuppet puppet = new BotPuppet(viewer, bot, team);
            final com.destroystokyo.paper.profile.PlayerProfile profile = Bukkit.createProfile(puppet.uuid, name);
            if (skin != null)
                profile.setProperties(skin.getProperties());
            final Object gameProfile = authlibCopy.invoke(null, profile);
            // Outlined like an opponent player (the name takes the outline's colour); otherwise named in its level's
            final net.kyori.adventure.text.format.NamedTextColor outline = FightGlow.botOutline(bot.getFight());
            if (outline != null)
                body.setGlowing(true); // passed on with the rest of its entity data
            final Object colour = formattingByName.invoke(null, outline != null ? outline.toString() : level.colour.replaceAll("[<>]", ""));
            if (colour != null)
                setColour.invoke(team, colour);
            setNameTagVisibility.invoke(team, hiddenTag);
            final net.kyori.adventure.text.Component label = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                    (outline != null ? "<" + outline + ">" : level.colour) + "Bot <gray>(" + level.coloured() + "<gray>)");
            @SuppressWarnings("unchecked") final java.util.Collection<String> players = (java.util.Collection<String>) teamPlayers.invoke(team);
            players.add(name);

            final org.bukkit.Location at = body.getLocation();
            send(viewer, infoPacket.newInstance(EnumSet.of((Enum) addPlayer), infoEntry.newInstance(puppet.uuid, gameProfile, false, 0, survival,
                    null, true, 0, null)));
            send(viewer, teamPacket.invoke(null, team, true));
            send(viewer, addPacket.newInstance(puppet.id, puppet.uuid, at.getX(), at.getY(), at.getZ(), at.getPitch(), at.getYaw(),
                    playerType, 0, zero, (double) at.getYaw()));
            puppet.x = at.getX();
            puppet.y = at.getY();
            puppet.z = at.getZ();
            puppet.yaw = at.getYaw();
            puppet.pitch = at.getPitch();
            send(viewer, puppet.data(body));
            puppet.gearChanged(body);
            send(viewer, puppet.equipment());
            send(viewer, puppet.head(body));
            send(viewer, addPacket.newInstance(puppet.labelId, UUID.randomUUID(), at.getX(), at.getY() + LABEL_Y, at.getZ(), 0f, 0f,
                    textDisplayType, 0, zero, 0d));
            send(viewer, dataPacket.newInstance(puppet.labelId, List.of(dataValue.invoke(null, textData, asVanilla.invoke(null, label)),
                    dataValue.invoke(null, billboardData, (byte) 3), dataValue.invoke(null, interpolationData, 2))));
            puppet.shown = true;
            BY_ID.put(puppet.id, bot);
            return puppet;
        } catch (final ReflectiveOperationException | RuntimeException e) {
            broken = true;
            SpawnArena.INSTANCE.getLogger().warning("Couldn't draw sumo bots as players (" + e + "); they're shown as mannequins.");
            return null;
        }
    }

    /** Every tick, on the bot's thread: where it is, how it's standing, what it holds. */
    void update(final Mannequin body) {
        if (!this.shown)
            return;
        this.ticks++;
        try {
            final org.bukkit.Location at = body.getLocation();
            if (at.getX() != this.x || at.getY() != this.y || at.getZ() != this.z || at.getYaw() != this.yaw || at.getPitch() != this.pitch) {
                this.x = at.getX();
                this.y = at.getY();
                this.z = at.getZ();
                this.yaw = at.getYaw();
                this.pitch = at.getPitch();
                send(this.viewer, syncPacket.newInstance(this.id, positionOf.invoke(null, handle(body)), body.isOnGround()));
                send(this.viewer, this.head(body));
                this.moveLabel(body);
            } else if ((body.getPose() == org.bukkit.entity.Pose.SNEAKING) != this.labelLow) {
                this.moveLabel(body);
            }
            if (this.gearChanged(body))
                send(this.viewer, this.equipment());
            if (this.ticks % 2 == 0) // crouching, sprinting...
                send(this.viewer, this.data(body));
        } catch (final ReflectiveOperationException | RuntimeException e) {
            SpawnArena.INSTANCE.getLogger().warning("Stopped drawing a sumo bot: " + e);
            this.remove();
        }
    }

    /** Above its head, as a name tag is (a little lower while it crouches). */
    private void moveLabel(final Mannequin body) throws ReflectiveOperationException {
        this.labelLow = body.getPose() == org.bukkit.entity.Pose.SNEAKING;
        final Object at = vec3Ctor.newInstance(this.x, this.y + (this.labelLow ? LABEL_Y - 0.3 : LABEL_Y), this.z);
        send(this.viewer, syncPacket.newInstance(this.labelId, positionCtor.newInstance(at, zero, 0f, 0f), false));
    }

    void swing(final Mannequin body) {
        if (!this.shown)
            return;
        try {
            final Object packet = animatePacket.newInstance(handle(body), 0);
            animateId.setInt(packet, this.id);
            send(this.viewer, packet);
        } catch (final ReflectiveOperationException | RuntimeException ignored) {
            // just no swing
        }
    }

    /** The red flash, from {@code yaw} (degrees, as the hurt animation takes it). */
    void hurt(final float yaw) {
        if (!this.shown)
            return;
        try {
            send(this.viewer, hurtPacket.newInstance(this.id, yaw));
        } catch (final ReflectiveOperationException | RuntimeException ignored) {
            // just no flash
        }
    }

    void remove() {
        if (!this.shown)
            return;
        this.shown = false;
        BY_ID.remove(this.id);
        try {
            send(this.viewer, removePacket.newInstance((Object) new int[]{this.id, this.labelId}));
            send(this.viewer, teamRemovePacket.invoke(null, this.team));
            send(this.viewer, infoRemovePacket.newInstance(List.of(this.uuid)));
        } catch (final ReflectiveOperationException | RuntimeException ignored) {
            // they've left, or it goes with the chunk
        }
    }

    private Object data(final Mannequin body) throws ReflectiveOperationException {
        @SuppressWarnings("unchecked") final List<Object> all = (List<Object>) packAll.invoke(getEntityData.invoke(handle(body)));
        final List<Object> shared = new ArrayList<>(all.size());
        for (final Object value : all)
            if ((int) dataId.invoke(value) <= lastShared)
                shared.add(value);
        return dataPacket.newInstance(this.id, shared);
    }

    private Object head(final Mannequin body) throws ReflectiveOperationException {
        final Object packet = headPacket.newInstance(handle(body), (byte) Math.floor(body.getLocation().getYaw() * 256f / 360f));
        headId.setInt(packet, this.id);
        return packet;
    }

    private boolean gearChanged(final Mannequin body) {
        final org.bukkit.inventory.EntityEquipment equipment = body.getEquipment();
        boolean changed = false;
        for (int i = 0; i < GEAR.length; i++) {
            final ItemStack item = equipment.getItem(GEAR[i]);
            if (!Objects.equals(item, this.gear[i])) {
                this.gear[i] = item.clone();
                changed = true;
            }
        }
        return changed;
    }

    private Object equipment() throws ReflectiveOperationException {
        final List<Object> list = new ArrayList<>(GEAR.length);
        for (int i = 0; i < GEAR.length; i++)
            list.add(pairOf.invoke(null, slots[i], asNmsCopy.invoke(null, this.gear[i] == null ? new ItemStack(org.bukkit.Material.AIR) : this.gear[i])));
        return equipmentPacket.newInstance(this.id, list);
    }

    // ── Reflection ──────────────────────────────────────────────────────────

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
            final Class<?> slot = Class.forName("net.minecraft.world.entity.EquipmentSlot", true, cl);
            final Class<?> scoreboard = Class.forName("net.minecraft.world.scores.Scoreboard", true, cl);
            final Class<?> playerTeam = Class.forName("net.minecraft.world.scores.PlayerTeam", true, cl);
            final Class<?> component = Class.forName("net.minecraft.network.chat.Component", true, cl);
            final Class<?> formatting = Class.forName("net.minecraft.ChatFormatting", true, cl);
            packetClass = Class.forName("net.minecraft.network.protocol.Packet", true, cl);
            final String game = "net.minecraft.network.protocol.game.";
            final Class<?> info = Class.forName(game + "ClientboundPlayerInfoUpdatePacket", true, cl);
            final Class<?> entry = Class.forName(game + "ClientboundPlayerInfoUpdatePacket$Entry", true, cl);
            final Class<?> action = Class.forName(game + "ClientboundPlayerInfoUpdatePacket$Action", true, cl);
            infoPacket = info.getConstructor(EnumSet.class, entry);
            infoEntry = entry.getDeclaredConstructors()[0];
            addPlayer = action.getField("ADD_PLAYER").get(null);
            survival = Class.forName("net.minecraft.world.level.GameType", true, cl).getField("SURVIVAL").get(null);
            infoRemovePacket = Class.forName(game + "ClientboundPlayerInfoRemovePacket", true, cl).getConstructor(List.class);
            final Class<?> teamPacketClass = Class.forName(game + "ClientboundSetPlayerTeamPacket", true, cl);
            teamPacket = teamPacketClass.getMethod("createAddOrModifyPacket", playerTeam, boolean.class);
            teamRemovePacket = teamPacketClass.getMethod("createRemovePacket", playerTeam);
            scoreboardCtor = scoreboard.getConstructor();
            teamCtor = playerTeam.getConstructor(scoreboard, String.class);
            setSuffix = playerTeam.getMethod("setPlayerSuffix", component);
            setColour = playerTeam.getMethod("setColor", formatting);
            teamPlayers = playerTeam.getMethod("getPlayers");
            formattingByName = formatting.getMethod("getByName", String.class);
            authlibCopy = Class.forName("com.destroystokyo.paper.profile.CraftPlayerProfile", true, cl)
                    .getMethod("asAuthlibCopy", com.destroystokyo.paper.profile.PlayerProfile.class);
            asVanilla = Class.forName("io.papermc.paper.adventure.PaperAdventure", true, cl).getMethod("asVanilla", net.kyori.adventure.text.Component.class);
            addPacket = Class.forName(game + "ClientboundAddEntityPacket", true, cl).getConstructor(int.class, UUID.class,
                    double.class, double.class, double.class, float.class, float.class, entityType, int.class, vec3, double.class);
            dataPacket = Class.forName(game + "ClientboundSetEntityDataPacket", true, cl).getConstructor(int.class, List.class);
            syncPacket = Class.forName(game + "ClientboundEntityPositionSyncPacket", true, cl).getConstructor(int.class, pmr, boolean.class);
            removePacket = Class.forName(game + "ClientboundRemoveEntitiesPacket", true, cl).getConstructor(int[].class);
            equipmentPacket = Class.forName(game + "ClientboundSetEquipmentPacket", true, cl).getConstructor(int.class, List.class);
            hurtPacket = Class.forName(game + "ClientboundHurtAnimationPacket", true, cl).getConstructor(int.class, float.class);
            final Class<?> animate = Class.forName(game + "ClientboundAnimatePacket", true, cl);
            animatePacket = animate.getConstructor(nmsEntity, int.class);
            animateId = field(animate, "id");
            final Class<?> head = Class.forName(game + "ClientboundRotateHeadPacket", true, cl);
            headPacket = head.getConstructor(nmsEntity, byte.class);
            headId = field(head, "entityId");
            packAll = synched.getMethod("packAll");
            dataId = Class.forName("net.minecraft.network.syncher.SynchedEntityData$DataValue", true, cl).getMethod("id");
            getEntityData = nmsEntity.getMethod("getEntityData");
            positionOf = pmr.getMethod("of", nmsEntity);
            asNmsCopy = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack", true, cl).getMethod("asNMSCopy", ItemStack.class);
            pairOf = Class.forName("com.mojang.datafixers.util.Pair", true, cl).getMethod("of", Object.class, Object.class);
            playerType = entityType.getField("PLAYER").get(null);
            zero = vec3.getField("ZERO").get(null);
            slots = new Object[NMS_SLOTS.length];
            for (int i = 0; i < NMS_SLOTS.length; i++)
                slots[i] = slot.getField(NMS_SLOTS[i]).get(null);
            textDisplayType = entityType.getField("TEXT_DISPLAY").get(null);
            hiddenTag = Class.forName("net.minecraft.world.scores.Team$Visibility", true, cl).getField("NEVER").get(null);
            setNameTagVisibility = playerTeam.getMethod("setNameTagVisibility", hiddenTag.getClass());
            final Class<?> display = Class.forName("net.minecraft.world.entity.Display", true, cl);
            textData = field(Class.forName("net.minecraft.world.entity.Display$TextDisplay", true, cl), "DATA_TEXT_ID").get(null);
            billboardData = field(display, "DATA_BILLBOARD_RENDER_CONSTRAINTS_ID").get(null);
            interpolationData = field(display, "DATA_POS_ROT_INTERPOLATION_DURATION_ID").get(null);
            final Class<?> accessorClass = Class.forName("net.minecraft.network.syncher.EntityDataAccessor", true, cl);
            dataValue = Class.forName("net.minecraft.network.syncher.SynchedEntityData$DataValue", true, cl).getMethod("create", accessorClass, Object.class);
            positionCtor = pmr.getConstructor(vec3, vec3, float.class, float.class);
            vec3Ctor = vec3.getConstructor(double.class, double.class, double.class);
            final Class<?> avatar = Class.forName("net.minecraft.world.entity.Avatar", true, cl);
            final Class<?> accessor = Class.forName("net.minecraft.network.syncher.EntityDataAccessor", true, cl);
            final Method accessorId = accessor.getMethod("id");
            lastShared = Math.max((int) accessorId.invoke(field(avatar, "DATA_PLAYER_MODE_CUSTOMISATION").get(null)),
                    (int) accessorId.invoke(field(avatar, "DATA_PLAYER_MAIN_HAND").get(null)));
            return true;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            broken = true;
            addPacket = null;
            SpawnArena.INSTANCE.getLogger().warning("Couldn't draw sumo bots as players (" + e + "); they're shown as mannequins.");
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
        if (!viewer.isOnline())
            return;
        final Object player = handle(viewer);
        final Object connection = player.getClass().getField("connection").get(player);
        if (sendPacket == null)
            sendPacket = connection.getClass().getMethod("send", packetClass);
        sendPacket.invoke(connection, packet);
    }
}
