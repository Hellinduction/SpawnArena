package me.zeronull.spawnarena;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plays a recorded sumo match back (see {@link MatchRecorder}) to one player, in the arena it was fought in: each
 * fighter is a player drawn by packets for them alone (their skin, name, the match's item in hand), moving and looking
 * as they did tick by tick, crouching, swinging and flashing red when hit. Paused, sped up, slowed down (smoothed between
 * ticks), skipped through; an Ice Rink match shows them the ice. Nothing in the world changes, and nobody else sees it.
 */
public final class ArenaReplay {
    private static final Map<UUID, ArenaReplay> WATCHING = new ConcurrentHashMap<>();
    public static final double[] SPEEDS = {0.25, 0.5, 1, 2, 4};

    private final Player viewer;
    public final Recording rec;
    private final World world;
    private final double cx, cy, cz;
    private final List<Puppet> puppets = new ArrayList<>();
    private final List<SumoIce.FakeBlock> ice;
    /** Where they were before being brought here (null: they were close enough already) */
    final Location cameFrom;
    private final long endMs;
    private double clock;
    private double speed = 1;
    private boolean paused;
    private boolean over;
    private ScheduledTask task;
    private static Object sharedFlags, poseData, customisation, crouching, standing;

    /** One fighter, as drawn for the viewer. */
    private final class Puppet {
        final Recording.Fighter f;
        final int id = Bukkit.getUnsafe().nextEntityId();
        final UUID uuid = UUID.randomUUID();
        final Object profile;
        final ItemStack hand;
        int frame = -1;
        boolean shown, crouched, sprinting;
        float lastYaw = Float.NaN;

        Puppet(final Recording.Fighter f, final Object profile, final ItemStack hand) {
            this.f = f;
            this.profile = profile;
            this.hand = hand;
        }

        double[] at(final double ms) {
            final Recording.Fighter f = this.f;
            int i = java.util.Arrays.binarySearch(f.time, (int) Math.floor(ms));
            if (i < 0)
                i = -i - 2;
            i = Math.max(0, Math.min(i, f.frames - 1));
            double x = f.x[i], y = f.y[i], z = f.z[i];
            float yaw = f.yaw[i], pitch = f.pitch[i];
            if (i + 1 < f.frames && f.time[i + 1] > f.time[i] && ms > f.time[i] && !CheatChecks.teleported(f, i + 1)) {
                final double t = Math.min(1, (ms - f.time[i]) / (f.time[i + 1] - f.time[i]));
                x += (f.x[i + 1] - x) * t;
                y += (f.y[i + 1] - y) * t;
                z += (f.z[i + 1] - z) * t;
                float dYaw = f.yaw[i + 1] - yaw;
                dYaw = ((dYaw % 360) + 540) % 360 - 180;
                yaw += (float) (dYaw * t);
                pitch += (float) ((f.pitch[i + 1] - pitch) * t);
            }
            return new double[]{i, x, y, z, yaw, pitch};
        }
    }

    private ArenaReplay(final Player viewer, final Recording rec, final World world, final double[] centre, final List<SumoIce.FakeBlock> ice,
                        final Location cameFrom) {
        this.viewer = viewer;
        this.rec = rec;
        this.world = world;
        this.cx = centre[0];
        this.cy = centre[1];
        this.cz = centre[2];
        this.ice = ice;
        this.cameFrom = cameFrom;
        long end = 0;
        for (final Recording.Fighter f : rec.fighters)
            if (f.frames > 0)
                end = Math.max(end, f.time[f.frames - 1]);
        this.endMs = end;
    }

    public static ArenaReplay of(final Player p) {
        return WATCHING.get(p.getUniqueId());
    }

    /** Watching a replay: may stay in an arena while a fight's going on (nothing of theirs is in it). */
    public static boolean isWatching(final Player p) {
        return WATCHING.containsKey(p.getUniqueId());
    }

    /**
     * Starts {@code rec} for {@code viewer} (any thread): their skins are looked up first, off the main thread. Says
     * why not if it can't be (the arena's gone, players can't be drawn on this server).
     */
    public static void start(final Player viewer, final Recording rec) {
        if (!BotPuppet.ready() || !loadData()) {
            viewer.sendMessage("§cReplays can't be shown on this server (drawing players by packets isn't working).");
            return;
        }
        final Arena arena = SpawnArena.arenas.of(rec.arena);
        if (arena == null || arena.spawnPoint1 == null || arena.spawnPoint2 == null || arena.spawnPoint1.getWorld() == null) {
            viewer.sendMessage("§cThe arena it was fought in (" + rec.arena + ") isn't there any more.");
            return;
        }
        Threads.async(() -> {
            // Skins (looked up by who they are), and what they held
            final List<Object[]> looks = new ArrayList<>();
            for (final Recording.Fighter f : rec.fighters) {
                if (f.frames == 0)
                    continue;
                com.destroystokyo.paper.profile.PlayerProfile skin = null;
                try {
                    final Player online = Bukkit.getPlayer(f.uuid);
                    skin = online != null ? online.getPlayerProfile() : Bukkit.createProfile(f.uuid, f.name);
                    if (!skin.hasTextures())
                        skin.complete(true);
                } catch (final RuntimeException ignored) {
                    // no skin (a Bedrock player, or the lookup failed): the default one
                }
                looks.add(new Object[]{f, skin});
            }
            final ItemStack hand = handItem(rec.variant(), arena);
            final Location a = arena.spawnPoint1, b = arena.spawnPoint2;
            final double[] centre = {(a.getX() + b.getX()) / 2, (a.getY() + b.getY()) / 2, (a.getZ() + b.getZ()) / 2};
            final List<SumoIce.FakeBlock> ice = rec.variant() == SumoVariant.ICE ? SumoIce.findForFake(arena).join() : List.of();
            Threads.entity(viewer, () -> {
                if (!viewer.isOnline())
                    return;
                final ArenaReplay old = WATCHING.get(viewer.getUniqueId());
                Location cameFrom = old == null ? null : old.cameFrom; // (already brought here by the one before)
                if (old != null)
                    old.stop(false);
                // Brought to the arena if they're not near it (and sent back after), then started once they've arrived
                final Location here = viewer.getLocation();
                final Location spot = new Location(a.getWorld(), centre[0], centre[1], centre[2]);
                java.util.concurrent.CompletableFuture<Boolean> arrived = java.util.concurrent.CompletableFuture.completedFuture(true);
                if (here.getWorld() != a.getWorld() || here.distanceSquared(spot) > 48 * 48) {
                    if (cameFrom == null)
                        cameFrom = here.clone();
                    final Location exit = arena.exitFor(viewer);
                    final Location to = exit != null && exit.getWorld() == a.getWorld() && exit.distanceSquared(spot) < 64 * 64 ? exit.clone() : a.clone();
                    to.setDirection(spot.toVector().subtract(to.toVector()));
                    arrived = Threads.teleport(viewer, to);
                }
                final Location from = cameFrom;
                final boolean moved = !arrived.isDone() || from != null && old == null;
                arrived.thenAccept(ok -> Threads.entityLater(viewer, () -> {
                    if (viewer.isOnline() && viewer.getWorld() == a.getWorld())
                        begin(viewer, rec, a.getWorld(), centre, ice, from, looks, hand, moved);
                }, moved ? 20L : 1L));
            });
        });
    }

    private static void begin(final Player viewer, final Recording rec, final World world, final double[] centre, final List<SumoIce.FakeBlock> ice,
                              final Location cameFrom, final List<Object[]> looks, final ItemStack hand, final boolean moved) {
        final ArenaReplay old = WATCHING.get(viewer.getUniqueId());
        if (old != null)
            old.stop(false);
        final ArenaReplay replay = new ArenaReplay(viewer, rec, world, centre, ice, cameFrom);
        try {
            for (final Object[] look : looks) {
                final Recording.Fighter f = (Recording.Fighter) look[0];
                final com.destroystokyo.paper.profile.PlayerProfile shown = Bukkit.createProfile(UUID.randomUUID(), f.name.length() > 16 ? f.name.substring(0, 16) : f.name);
                if (look[1] != null)
                    shown.setProperties(((com.destroystokyo.paper.profile.PlayerProfile) look[1]).getProperties());
                replay.puppets.add(replay.new Puppet(f, BotPuppet.authlibCopy.invoke(null, shown), hand));
            }
        } catch (final ReflectiveOperationException | RuntimeException e) {
            viewer.sendMessage("§cCouldn't draw the players: " + e);
            return;
        }
        WATCHING.put(viewer.getUniqueId(), replay);
        replay.begin(moved);
    }

    /** What the match's fighters held: the random sumo variant's item, or the Slap Fish (fists in a combo arena). */
    private static ItemStack handItem(final SumoVariant v, final Arena arena) {
        return switch (v) {
            case ARCHER -> new ItemStack(Material.BOW);
            case SNOWBALL -> new ItemStack(Material.SNOWBALL);
            case ROD -> new ItemStack(Material.FISHING_ROD);
            case SPEAR -> new ItemStack(Material.IRON_SPEAR);
            case COMBO -> new ItemStack(Material.AIR);
            default -> arena.isCombo() ? new ItemStack(Material.AIR) : SlapFish.create();
        };
    }

    private void begin(final boolean moved) {
        if (!this.ice.isEmpty())
            SumoIce.showIce(this.viewer, this.ice);
        final java.text.SimpleDateFormat when = new java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.ENGLISH);
        final SumoVariant v = this.rec.variant();
        this.viewer.sendMessage(MiniMessage.miniMessage().deserialize("<gold><bold>Replay:</bold> <white><who></white> <gray>in <arena>, <when><variant> (<length>)",
                Placeholder.unparsed("who", this.versus()), Placeholder.unparsed("arena", Arena.displayName(this.rec.arena)),
                Placeholder.unparsed("when", when.format(new java.util.Date(this.rec.started))),
                Placeholder.unparsed("variant", v == SumoVariant.CLASSIC ? "" : ", " + v.title),
                Placeholder.unparsed("length", time(this.endMs))));
        if (moved)
            this.viewer.sendMessage(MiniMessage.miniMessage().deserialize("<gray>Brought you to the arena: <click:run_command:/arenareplay back><aqua>[back]</aqua></click> takes you back."));
        this.controls();
        this.task = this.viewer.getScheduler().runAtFixedRate(SpawnArena.INSTANCE, t -> this.tick(), () -> this.stop(false), 1L, 1L);
    }

    private String versus() {
        final List<String> a = new ArrayList<>(), b = new ArrayList<>();
        for (final Recording.Fighter f : this.rec.fighters)
            (f.team == 0 ? a : b).add(f.name);
        return String.join(" & ", a) + " vs " + String.join(" & ", b);
    }

    /** The buttons, in chat. */
    public void controls() {
        final StringBuilder speeds = new StringBuilder();
        for (final double s : SPEEDS)
            speeds.append(" <click:run_command:/arenareplay speed ").append(fmt(s)).append(">")
                    .append(s == this.speed ? "<yellow>[" : "<gray>[").append(fmt(s)).append("x]</click>");
        this.viewer.sendMessage(MiniMessage.miniMessage().deserialize(
                "<click:run_command:/arenareplay skip -5><aqua>[« 5s]</aqua></click> "
                        + (this.paused ? "<click:run_command:/arenareplay play><green>[▶ Play]</green></click>" : "<click:run_command:/arenareplay pause><yellow>[⏸ Pause]</yellow></click>")
                        + " <click:run_command:/arenareplay skip 5><aqua>[5s »]</aqua></click> <click:run_command:/arenareplay restart><aqua>[⟲]</aqua></click>"
                        + speeds + " <click:run_command:/arenareplay stop><red>[■ Stop]</red></click>"));
    }

    private static String fmt(final double s) {
        return s == Math.floor(s) ? String.valueOf((int) s) : String.valueOf(s);
    }

    private static String time(final double ms) {
        final int s = (int) Math.max(0, ms / 1000);
        return s / 60 + ":" + String.format("%02d", s % 60);
    }

    // ── Controls ────────────────────────────────────────────────────────────

    public void setPaused(final boolean paused) {
        if (this.over && !paused) {
            this.restart();
            return;
        }
        this.paused = paused;
    }

    public boolean isPaused() {
        return this.paused;
    }

    public void setSpeed(final double speed) {
        this.speed = speed;
    }

    public void skip(final double seconds) {
        this.seek(this.clock + seconds * 1000);
    }

    public void restart() {
        this.over = false;
        this.paused = false;
        this.seek(0);
    }

    private void seek(final double ms) {
        this.clock = Math.max(0, Math.min(this.endMs, ms));
        if (this.clock < this.endMs)
            this.over = false;
        for (final Puppet p : this.puppets)
            p.frame = -1; // drawn where they are then, without the swings and hits skipped over
    }

    // ── Each tick ───────────────────────────────────────────────────────────

    private void tick() {
        if (!this.viewer.isOnline() || this.viewer.getWorld() != this.world || SpawnArena.arenas.hasFighter(this.viewer)) {
            this.stop(false);
            return;
        }
        try {
            if (!this.paused && !this.over) {
                this.clock += 50 * this.speed;
                if (this.clock >= this.endMs) {
                    this.clock = this.endMs;
                    this.finished();
                }
            }
            for (final Puppet p : this.puppets)
                this.draw(p);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            SpawnArena.INSTANCE.getLogger().warning("Stopped a replay: " + e);
            this.stop(false);
            return;
        }
        this.viewer.sendActionBar(MiniMessage.miniMessage().deserialize("<gold>" + (this.over ? "■" : this.paused ? "⏸" : "▶") + " Replay</gold> <white>"
                + "<who></white> <gray><at> / <length>" + (this.speed == 1 ? "" : " <yellow>" + fmt(this.speed) + "x"),
                Placeholder.unparsed("who", this.versus()), Placeholder.unparsed("at", time(this.clock)), Placeholder.unparsed("length", time(this.endMs))));
    }

    private void draw(final Puppet p) throws ReflectiveOperationException {
        final Recording.Fighter f = p.f;
        // Out of the match (in the water, or eliminated): gone a moment after their last frame
        final boolean gone = this.clock > f.time[f.frames - 1] + 400 || this.clock < f.time[0] - 50;
        if (gone) {
            if (p.shown)
                this.despawn(p);
            return;
        }
        final double[] at = p.at(this.clock);
        final int frame = (int) at[0];
        final double x = this.cx + at[1], y = this.cy + at[2], z = this.cz + at[3];
        final float yaw = (float) at[4], pitch = (float) at[5];
        if (!p.shown)
            this.spawn(p, x, y, z, yaw, pitch);
        // Swings and hits since the last frame drawn (playing on, not skipped to)
        if (p.frame >= 0 && frame > p.frame && frame - p.frame <= 10)
            for (int i = p.frame + 1; i <= frame; i++) {
                if (f.has(i, Recording.SWUNG))
                    this.swing(p);
                if (f.has(i, Recording.HURT) || f.hasMore(i, Recording.SHOT))
                    BotPuppet.send(this.viewer, BotPuppet.hurtPacket.newInstance(p.id, 0f));
            }
        p.frame = frame;
        final boolean onGround = f.has(frame, Recording.GROUND);
        BotPuppet.send(this.viewer, BotPuppet.syncPacket.newInstance(p.id,
                BotPuppet.positionCtor.newInstance(BotPuppet.vec3Ctor.newInstance(x, y, z), BotPuppet.zero, yaw, pitch), onGround));
        if (yaw != p.lastYaw) {
            p.lastYaw = yaw;
            this.head(p, yaw);
        }
        final boolean crouched = f.has(frame, Recording.SNEAKING), sprinting = f.has(frame, Recording.SPRINTING);
        if (crouched != p.crouched || sprinting != p.sprinting) {
            p.crouched = crouched;
            p.sprinting = sprinting;
            this.data(p);
        }
    }

    private void spawn(final Puppet p, final double x, final double y, final double z, final float yaw, final float pitch) throws ReflectiveOperationException {
        BotPuppet.send(this.viewer, BotPuppet.infoPacket.newInstance(EnumSet.of((Enum) BotPuppet.addPlayer),
                BotPuppet.infoEntry.newInstance(p.uuid, p.profile, false, 0, BotPuppet.survival, null, true, 0, null)));
        BotPuppet.send(this.viewer, BotPuppet.addPacket.newInstance(p.id, p.uuid, x, y, z, pitch, yaw, BotPuppet.playerType, 0, BotPuppet.zero, (double) yaw));
        p.shown = true;
        p.crouched = p.f.has(Math.max(0, p.frame), Recording.SNEAKING);
        this.data(p);
        final List<Object> gear = new ArrayList<>();
        gear.add(BotPuppet.pairOf.invoke(null, BotPuppet.slots[0], BotPuppet.asNmsCopy.invoke(null, p.hand)));
        BotPuppet.send(this.viewer, BotPuppet.equipmentPacket.newInstance(p.id, gear));
        p.lastYaw = yaw;
        this.head(p, yaw);
    }

    private void despawn(final Puppet p) {
        p.shown = false;
        p.frame = -1;
        try {
            BotPuppet.send(this.viewer, BotPuppet.removePacket.newInstance((Object) new int[]{p.id}));
            BotPuppet.send(this.viewer, BotPuppet.infoRemovePacket.newInstance(List.of(p.uuid)));
        } catch (final ReflectiveOperationException | RuntimeException ignored) {
            // they've left
        }
    }

    private void swing(final Puppet p) throws ReflectiveOperationException {
        final Object packet = BotPuppet.animatePacket.newInstance(BotPuppet.handle(this.viewer), 0);
        BotPuppet.animateId.setInt(packet, p.id);
        BotPuppet.send(this.viewer, packet);
    }

    private void head(final Puppet p, final float yaw) throws ReflectiveOperationException {
        final Object packet = BotPuppet.headPacket.newInstance(BotPuppet.handle(this.viewer), (byte) Math.floor(yaw * 256f / 360f));
        BotPuppet.headId.setInt(packet, p.id);
        BotPuppet.send(this.viewer, packet);
    }

    /** Crouching or not, sprinting or not, and every layer of their skin shown. */
    private void data(final Puppet p) throws ReflectiveOperationException {
        final byte flags = (byte) ((p.crouched ? 0x02 : 0) | (p.sprinting ? 0x08 : 0));
        BotPuppet.send(this.viewer, BotPuppet.dataPacket.newInstance(p.id, List.of(
                BotPuppet.dataValue.invoke(null, sharedFlags, flags),
                BotPuppet.dataValue.invoke(null, poseData, p.crouched ? crouching : standing),
                BotPuppet.dataValue.invoke(null, customisation, (byte) 0x7F))));
    }

    private void finished() {
        this.over = true;
        final List<String> won = new ArrayList<>();
        for (final Recording.Fighter f : this.rec.fighters)
            if (f.won)
                won.add(f.name);
        this.viewer.sendMessage(MiniMessage.miniMessage().deserialize("<gold>Replay over:</gold> <white><won></white> <gray>won. "
                        + "<click:run_command:/arenareplay restart><aqua>[Watch again]</aqua></click> <click:run_command:/arenareplay stop><red>[Close]</red></click>",
                Placeholder.unparsed("won", won.isEmpty() ? "nobody" : String.join(" & ", won))));
    }

    /** Takes the players away (and the ice), and, if asked and they were brought here, sends the viewer back. */
    public void stop(final boolean sendBack) {
        if (!WATCHING.remove(this.viewer.getUniqueId(), this))
            return;
        final ScheduledTask t = this.task;
        if (t != null)
            t.cancel();
        if (!this.viewer.isOnline())
            return;
        for (final Puppet p : this.puppets)
            if (p.shown)
                this.despawn(p);
        if (!this.ice.isEmpty())
            SumoIce.showReal(this.viewer, this.ice);
        this.viewer.sendActionBar(Component.empty());
        if (sendBack && this.cameFrom != null)
            Threads.teleport(this.viewer, this.cameFrom);
    }

    public static void stopAll() {
        for (final ArenaReplay r : new ArrayList<>(WATCHING.values()))
            try {
                r.stop(false);
            } catch (final RuntimeException ignored) {
                // shutting down
            }
    }

    private static boolean loadData() {
        if (customisation != null)
            return true;
        try {
            final ClassLoader cl = Bukkit.getServer().getClass().getClassLoader();
            final Class<?> entity = Class.forName("net.minecraft.world.entity.Entity", true, cl);
            final Class<?> avatar = Class.forName("net.minecraft.world.entity.Avatar", true, cl);
            final Class<?> pose = Class.forName("net.minecraft.world.entity.Pose", true, cl);
            sharedFlags = field(entity, "DATA_SHARED_FLAGS_ID");
            poseData = field(entity, "DATA_POSE");
            crouching = pose.getField("CROUCHING").get(null);
            standing = pose.getField("STANDING").get(null);
            customisation = field(avatar, "DATA_PLAYER_MODE_CUSTOMISATION");
            return true;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            SpawnArena.INSTANCE.getLogger().warning("Replays can't be shown: " + e);
            return false;
        }
    }

    private static Object field(final Class<?> type, final String name) throws ReflectiveOperationException {
        final Field f = BotPuppet.field(type, name);
        return f.get(null);
    }

    /** Stops a viewer's replay when they leave or change world. */
    public static final class Events implements Listener {
        @EventHandler
        public void onQuit(final PlayerQuitEvent e) {
            final ArenaReplay r = WATCHING.get(e.getPlayer().getUniqueId());
            if (r != null)
                r.stop(false);
        }

        @EventHandler
        public void onWorld(final PlayerChangedWorldEvent e) {
            final ArenaReplay r = WATCHING.get(e.getPlayer().getUniqueId());
            if (r != null)
                r.stop(false);
        }
    }
}
