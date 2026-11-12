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
 * ticks), skipped through; an Ice Rink match shows them the ice, a spleef match the floor being broken as it was (and
 * put back as they skip back). A crystal match: the blocks placed and blown up, the crystals, totems popping, each
 * fighter in what they wore and held, flashing red as their health drops. Nothing in the world changes, and nobody
 * else sees it.
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
    /** Spleef: the blocks changed during the match (in order), where they are now and what they became */
    private final List<Change> changes;
    /** The real blocks at those spots (read as it started), to show again */
    private final Map<Location, org.bukkit.block.data.BlockData> realBlocks;
    /** What the viewer is shown instead of the real block, now */
    private final Map<Location, org.bukkit.block.data.BlockData> shownBlocks = new java.util.HashMap<>();
    /** The time the blocks were last shown for (-1: not yet) */
    private double blocksAt = -1;

    /** One block change, placed in the arena as it is now. */
    private record Change(int ms, Location at, org.bukkit.block.data.BlockData now) {}
    /** Where they were before being brought here (null: they were close enough already) */
    final Location cameFrom;
    private final long endMs;
    /** The last frame anyone has (the match's end): the replay runs on a little past it, for the last one going in */
    private final long lastFrameMs;
    private double clock;
    private double speed = 1;
    private boolean paused;
    private boolean over;
    /** Ticks it's been over, or paused, for: it closes itself after a while (the players it shows would otherwise stand
     * there frozen, the winner of a finished one especially, until they closed it) */
    private int overTicks, pausedTicks;
    /** A finished replay closes itself after this long (10 seconds); a paused one after 5 minutes */
    private static final int CLOSE_WHEN_OVER = 200, CLOSE_WHEN_PAUSED = 6000;
    /** Further than this from the arena, the replay closes */
    private static final double MAX_AWAY = 100;
    private ScheduledTask task;
    /**
     * Live fighters in this arena hidden from the viewer while they watch (so the replay isn't mixed up with matches being
     * played): looked at again every few ticks, and shown again as they stop fighting or the replay ends. On the viewer's
     * thread only.
     */
    private final java.util.Set<UUID> hiddenFighters = new java.util.HashSet<>();
    private int ticks;
    private static Object sharedFlags, poseData, customisation, crouching, standing, gliding, endCrystalType;
    private static java.lang.reflect.Constructor<?> entityEventPacket;
    private static Field entityEventId;
    /** Crystal matches: the crystals placed, and those shown now (crystal's id in the match -> its entity id for the viewer) */
    private final List<Recording.Crystal> crystals;
    private final Map<Integer, Integer> shownCrystals = new java.util.HashMap<>();
    /** The time totems, health and crystals going off were last shown for (-1: not yet) */
    private double eventsAt = -1;

    /** One fighter, as drawn for the viewer. */
    private final class Puppet {
        final Recording.Fighter f;
        final int id = Bukkit.getUnsafe().nextEntityId();
        final UUID uuid = UUID.randomUUID();
        final Object profile;
        final ItemStack hand;
        /** Its name tag, as a team of the viewer's (null: none of ours; an online fighter's comes from TAB) */
        Object team;
        int frame = -1;
        boolean shown, crouched, sprinting, flying;
        float lastYaw = Float.NaN;
        /** What they wore and held as it changed (ms, slot, item), and what they're shown in now (format 4) */
        final List<Object[]> gearChanges = new ArrayList<>();
        ItemStack[] shownGear;
        /** Their place in the recording's list of fighters */
        int index;

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
                        final Location cameFrom, final List<Change> changes, final Map<Location, org.bukkit.block.data.BlockData> realBlocks) {
        this.viewer = viewer;
        this.rec = rec;
        this.world = world;
        this.cx = centre[0];
        this.cy = centre[1];
        this.cz = centre[2];
        this.ice = ice;
        this.cameFrom = cameFrom;
        this.changes = changes;
        this.realBlocks = realBlocks;
        this.crystals = rec.crystal ? rec.crystals() : List.of();
        long end = 0;
        for (final Recording.Fighter f : rec.fighters)
            if (f.frames > 0)
                end = Math.max(end, f.time[f.frames - 1]);
        this.lastFrameMs = end;
        this.endMs = end + 600;
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
                looks.add(new Object[]{f, skin, nameTag(f)});
            }
            final ItemStack hand = handItem(rec.variant(), arena);
            final Location a = arena.spawnPoint1, b = arena.spawnPoint2;
            final double[] centre = {(a.getX() + b.getX()) / 2, (a.getY() + b.getY()) / 2, (a.getZ() + b.getZ()) / 2};
            final List<SumoIce.FakeBlock> ice = rec.variant() == SumoVariant.ICE ? SumoIce.findForFake(arena).join() : List.of();
            // Spleef: the blocks broken, where they are in the arena now, and the real ones there (read on their thread)
            final List<Change> changes = new ArrayList<>();
            for (final Recording.BlockChange c : rec.blocks) {
                try {
                    changes.add(new Change(c.ms(), new Location(a.getWorld(), Math.floor(centre[0]) + c.x(), Math.floor(centre[1]) + c.y(),
                            Math.floor(centre[2]) + c.z()), Bukkit.createBlockData(c.data())));
                } catch (final IllegalArgumentException ignored) {
                    // a block this version doesn't know
                }
            }
            final Map<Location, org.bukkit.block.data.BlockData> realBlocks = changes.isEmpty() ? Map.of() : Threads.supplyAt(a, () -> {
                final Map<Location, org.bukkit.block.data.BlockData> real = new java.util.HashMap<>();
                for (final Change c : changes)
                    real.computeIfAbsent(c.at(), at -> at.getBlock().getBlockData().clone());
                return real;
            }).join();
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
                        begin(viewer, rec, a.getWorld(), centre, ice, from, looks, hand, moved, changes, realBlocks);
                }, moved ? 20L : 1L));
            });
        });
    }

    private static void begin(final Player viewer, final Recording rec, final World world, final double[] centre, final List<SumoIce.FakeBlock> ice,
                              final Location cameFrom, final List<Object[]> looks, final ItemStack hand, final boolean moved,
                              final List<Change> changes, final Map<Location, org.bukkit.block.data.BlockData> realBlocks) {
        final ArenaReplay old = WATCHING.get(viewer.getUniqueId());
        if (old != null)
            old.stop(false);
        final ArenaReplay replay = new ArenaReplay(viewer, rec, world, centre, ice, cameFrom, changes, realBlocks);
        try {
            for (final Object[] look : looks) {
                final Recording.Fighter f = (Recording.Fighter) look[0];
                final com.destroystokyo.paper.profile.PlayerProfile shown = Bukkit.createProfile(UUID.randomUUID(), f.name.length() > 16 ? f.name.substring(0, 16) : f.name);
                if (look[1] != null)
                    shown.setProperties(((com.destroystokyo.paper.profile.PlayerProfile) look[1]).getProperties());
                final Puppet puppet = replay.new Puppet(f, BotPuppet.authlibCopy.invoke(null, shown), hand);
                puppet.team = look.length > 2 ? look[2] : null;
                puppet.index = rec.fighters.indexOf(f);
                for (final Recording.Event e : rec.events)
                    if (e.kind() == Recording.EQUIP && e.fighter() == puppet.index)
                        try {
                            final java.io.DataInputStream in = e.details();
                            final int slot = in.readByte();
                            final Material type = Material.matchMaterial(in.readUTF());
                            final boolean enchanted = in.readByte() != 0;
                            final ItemStack item = new ItemStack(type == null || !type.isItem() ? Material.AIR : type);
                            if (enchanted && !item.getType().isAir())
                                item.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
                            if (slot >= 0 && slot < 6)
                                puppet.gearChanges.add(new Object[]{e.ms(), slot, item});
                        } catch (final java.io.IOException ignored) {
                            // a short one: skipped
                        }
                replay.puppets.add(puppet);
            }
        } catch (final ReflectiveOperationException | RuntimeException e) {
            viewer.sendMessage("§cCouldn't draw the players: " + e);
            return;
        }
        WATCHING.put(viewer.getUniqueId(), replay);
        replay.begin(moved);
    }

    /** What the match's fighters held: the spleef shovel, the random sumo variant's item, or the Slap Fish (fists in a combo arena). */
    private static ItemStack handItem(final SumoVariant v, final Arena arena) {
        if (arena.isSpleef())
            return Spleef.shovel();
        final ItemStack held = v.heldItem(); // (enchanted as they had it)
        if (held != null)
            return held;
        return v == SumoVariant.COMBO || arena.isCombo() ? new ItemStack(Material.AIR) : SlapFish.create();
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
        // Its command, to copy or put in chat (and to favourite it, if it's one of theirs)
        if (this.rec.file != null)
            MatchHistoryMenu.sendCommand(this.viewer, this.rec.file, this.rec.fighter(this.viewer.getUniqueId()) != null);
        this.controls();
        this.task = this.viewer.getScheduler().runAtFixedRate(SpawnArena.INSTANCE, t -> this.tick(), () -> this.stop(false), 1L, 1L);
    }

    private String versus() {
        return this.rec.versus();
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
        // Gone off elsewhere, finished a while ago, or left paused: closed (nothing of it left standing about)
        final Location here = this.viewer.getLocation();
        final double dx = here.getX() - this.cx, dz = here.getZ() - this.cz;
        final String why = dx * dx + dz * dz > MAX_AWAY * MAX_AWAY ? "you went too far from the arena"
                : this.over && ++this.overTicks > CLOSE_WHEN_OVER ? null
                : this.paused && !this.over && ++this.pausedTicks > CLOSE_WHEN_PAUSED ? "it was paused for 5 minutes" : "";
        if (why == null || !why.isEmpty()) {
            if (why != null)
                this.viewer.sendMessage(MiniMessage.miniMessage().deserialize("<gray>Replay closed: " + why + "."));
            this.stop(false);
            return;
        }
        if (!this.over)
            this.overTicks = 0;
        if (!this.paused)
            this.pausedTicks = 0;
        if (this.ticks++ % 5 == 0)
            this.hideFighters();
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
            if (!this.changes.isEmpty())
                this.blocks();
            if (this.rec.crystal || this.rec.session || this.rec.kit || !this.rec.events.isEmpty())
                this.events();
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
        // Out of the match (in the water, or eliminated): gone a moment after their last frame. Everyone's frames end with
        // the match, so at the end only the losers go: the winners stay standing
        final long last = f.time[f.frames - 1];
        // (a free-for-all's part: only those who'd gone by the end go; everyone in at the end stays)
        final boolean out = (!f.won && !this.rec.session) || last < this.lastFrameMs - 1000;
        final double[] at = p.at(this.clock);
        // A free-for-all: out for a while (died or left, then back in), gone in between
        final int at0 = (int) at[0];
        final boolean away = at0 + 1 < f.frames && f.time[at0 + 1] - f.time[at0] > 1000 && this.clock > f.time[at0] + 400;
        final boolean gone = (out && this.clock > last + 400) || this.clock < f.time[0] - 50 || away;
        if (gone) {
            if (p.shown) {
                // Out at the end of their frames (playing on): into the water's splash, or (a crystal match) dying
                if ((this.clock > last && p.frame >= f.frames - 3) || (away && p.frame >= at0 - 2))
                    this.sound(p, this.rec.crystal || this.rec.kit ? org.bukkit.Sound.ENTITY_PLAYER_DEATH : org.bukkit.Sound.ENTITY_PLAYER_SPLASH);
                this.despawn(p);
            }
            return;
        }
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
                // (a crystal match: the flash comes from their health dropping, explosions included; see events)
                if ((f.has(i, Recording.HURT) || f.hasMore(i, Recording.SHOT)) && !this.rec.crystal) {
                    BotPuppet.send(this.viewer, BotPuppet.hurtPacket.newInstance(p.id, 0f));
                    this.sound(p, f.hasMore(i, Recording.SHOT) ? org.bukkit.Sound.ENTITY_ARROW_HIT_PLAYER : org.bukkit.Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK);
                    this.sound(p, org.bukkit.Sound.ENTITY_PLAYER_HURT);
                }
            }
        p.frame = frame;
        if (!p.gearChanges.isEmpty())
            this.gear(p);
        final boolean onGround = f.has(frame, Recording.GROUND);
        BotPuppet.send(this.viewer, BotPuppet.syncPacket.newInstance(p.id,
                BotPuppet.positionCtor.newInstance(BotPuppet.vec3Ctor.newInstance(x, y, z), BotPuppet.zero, yaw, pitch), onGround));
        if (yaw != p.lastYaw) {
            p.lastYaw = yaw;
            this.head(p, yaw);
        }
        final boolean crouched = f.has(frame, Recording.SNEAKING), sprinting = f.has(frame, Recording.SPRINTING),
                flying = f.has(frame, Recording.GLIDING);
        if (crouched != p.crouched || sprinting != p.sprinting || flying != p.flying) {
            p.crouched = crouched;
            p.sprinting = sprinting;
            p.flying = flying;
            this.data(p);
        }
    }

    /**
     * Spleef: the blocks as they were at this point of the match, shown to the viewer (what changed since the last tick;
     * all of it again every second, as clicking a block makes the server send the real one). Playing on, each block
     * broken makes its sound.
     */
    private void blocks() {
        final double now = this.clock;
        if (now == this.blocksAt && this.ticks % 20 != 0)
            return;
        final double before = this.blocksAt;
        final Map<Location, org.bukkit.block.data.BlockData> wanted = new java.util.HashMap<>();
        int sounds = 0;
        for (final Change c : this.changes) {
            if (c.ms() > now)
                break;
            final org.bukkit.block.data.BlockData was = wanted.containsKey(c.at()) ? wanted.get(c.at()) : this.realBlocks.get(c.at());
            wanted.put(c.at(), c.now());
            // Playing on: broken or placed, its sound (a few a tick at most: an explosion takes many at once)
            if (before >= 0 && c.ms() > before && now - before <= 500 && sounds < 3) {
                final org.bukkit.Sound sound = c.now().getMaterial().isAir()
                        ? (was == null || was.getMaterial().isAir() ? null : was.getSoundGroup().getBreakSound())
                        : c.now().getSoundGroup().getPlaceSound();
                if (sound != null) {
                    sounds++;
                    this.viewer.playSound(c.at().clone().add(0.5, 0.5, 0.5), sound, org.bukkit.SoundCategory.BLOCKS, 1f, 0.8f);
                }
            }
        }
        final boolean all = this.ticks % 20 == 0;
        final Map<Location, org.bukkit.block.data.BlockData> send = new java.util.HashMap<>();
        for (final Map.Entry<Location, org.bukkit.block.data.BlockData> e : wanted.entrySet())
            if (all || !e.getValue().equals(this.shownBlocks.get(e.getKey())))
                send.put(e.getKey(), e.getValue());
        for (final Location at : this.shownBlocks.keySet())
            if (!wanted.containsKey(at)) {
                final org.bukkit.block.data.BlockData real = this.realBlocks.get(at);
                if (real != null)
                    send.put(at, real);
            }
        this.shownBlocks.clear();
        this.shownBlocks.putAll(wanted);
        this.blocksAt = now;
        if (!send.isEmpty())
            this.viewer.sendMultiBlockChange(send);
    }

    /** What they wore and held at this point (format 4; before that, the match's item in hand), sent if it's changed. */
    private void gear(final Puppet p) throws ReflectiveOperationException {
        final ItemStack[] now = new ItemStack[6];
        java.util.Arrays.fill(now, new ItemStack(Material.AIR));
        if (p.gearChanges.isEmpty())
            now[0] = p.hand;
        for (final Object[] c : p.gearChanges) {
            if ((int) c[0] > this.clock)
                break;
            now[(int) c[1]] = (ItemStack) c[2];
        }
        if (p.shownGear != null && java.util.Arrays.equals(now, p.shownGear))
            return;
        p.shownGear = now;
        final List<Object> gear = new ArrayList<>();
        for (int s = 0; s < 6; s++)
            gear.add(BotPuppet.pairOf.invoke(null, BotPuppet.slots[s], BotPuppet.asNmsCopy.invoke(null, now[s])));
        BotPuppet.send(this.viewer, BotPuppet.equipmentPacket.newInstance(p.id, gear));
    }

    /**
     * A crystal match: the crystals there at this point (each blowing up as it goes, playing on), and, playing on, totems
     * popping and fighters flashing red as their health drops.
     */
    private void events() throws ReflectiveOperationException {
        final double now = this.clock, before = this.eventsAt;
        if (now == before)
            return;
        final boolean playing = before >= 0 && now > before && now - before <= 500;
        // Crystals
        for (final Recording.Crystal c : this.crystals) {
            final boolean there = c.placedMs() <= now && now < c.goneMs();
            final Integer shown = this.shownCrystals.get(c.id());
            final double x = this.cx + c.x(), y = this.cy + c.y(), z = this.cz + c.z();
            if (there && shown == null) {
                final int id = Bukkit.getUnsafe().nextEntityId();
                BotPuppet.send(this.viewer, BotPuppet.addPacket.newInstance(id, UUID.randomUUID(), x, y, z, 0f, 0f, endCrystalType, 0, BotPuppet.zero, 0.0));
                this.shownCrystals.put(c.id(), id);
            } else if (!there && shown != null) {
                BotPuppet.send(this.viewer, BotPuppet.removePacket.newInstance((Object) new int[]{shown}));
                this.shownCrystals.remove(c.id());
                if (playing && c.goneMs() > before && c.goneMs() <= now) {
                    final Location at = new Location(this.world, x, y, z);
                    this.viewer.spawnParticle(org.bukkit.Particle.EXPLOSION_EMITTER, at, 1);
                    this.viewer.playSound(at, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, org.bukkit.SoundCategory.BLOCKS, 4f, 0.9f);
                }
            }
        }
        // Totems and health, playing on
        if (playing) {
            final Map<Integer, Float> health = new java.util.HashMap<>();
            for (final Recording.Event e : this.rec.events) {
                if (e.ms() > now)
                    break;
                if (e.kind() == Recording.HEALTH) {
                    float value = 0;
                    try {
                        value = e.details().readFloat();
                    } catch (final java.io.IOException ignored) {
                        // a short one
                    }
                    final Float was = health.put(e.fighter(), value);
                    // (crystal only: elsewhere the recorded hit already flashes them, see draw)
                    if (this.rec.crystal && e.ms() > before && was != null && value < was - 0.01f)
                        this.hurt(e.fighter());
                } else if (e.kind() == Recording.KILL && e.ms() > before) {
                    // A free-for-all: who killed whom, in chat
                    try {
                        final int victim = e.details().readByte();
                        if (e.fighter() >= 0 && e.fighter() < this.rec.fighters.size() && victim >= 0 && victim < this.rec.fighters.size())
                            this.viewer.sendMessage(MiniMessage.miniMessage().deserialize("<dark_gray>[Replay]</dark_gray> <gray><killer> killed <victim>",
                                    Placeholder.unparsed("killer", this.rec.fighters.get(e.fighter()).name),
                                    Placeholder.unparsed("victim", this.rec.fighters.get(victim).name)));
                    } catch (final java.io.IOException ignored) {
                        // a short one
                    }
                } else if (e.kind() == Recording.ROCKET && e.ms() > before) {
                    // A firework rocket boosting their elytra flight: its sound and sparks
                    final Puppet p = this.puppet(e.fighter());
                    if (p != null && p.shown) {
                        final double[] at = p.at(this.clock);
                        final Location where = new Location(this.world, this.cx + at[1], this.cy + at[2], this.cz + at[3]);
                        this.viewer.playSound(where, org.bukkit.Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, org.bukkit.SoundCategory.PLAYERS, 1f, 1f);
                        this.viewer.spawnParticle(org.bukkit.Particle.FIREWORK, where, 8, 0.2, 0.2, 0.2, 0.05);
                    }
                } else if (e.kind() == Recording.TOTEM && e.ms() > before) {
                    final Puppet p = this.puppet(e.fighter());
                    if (p != null && p.shown) {
                        final Object packet = entityEventPacket.newInstance(BotPuppet.handle(this.viewer), (byte) 35);
                        entityEventId.setInt(packet, p.id);
                        BotPuppet.send(this.viewer, packet);
                    }
                }
            }
        }
        this.eventsAt = now;
    }

    private Puppet puppet(final int index) {
        for (final Puppet p : this.puppets)
            if (p.index == index)
                return p;
        return null;
    }

    /** A fighter flashing red, with the hurt sound. */
    private void hurt(final int index) throws ReflectiveOperationException {
        final Puppet p = this.puppet(index);
        if (p == null || !p.shown)
            return;
        BotPuppet.send(this.viewer, BotPuppet.hurtPacket.newInstance(p.id, 0f));
        this.sound(p, org.bukkit.Sound.ENTITY_PLAYER_HURT);
    }

    private void spawn(final Puppet p, final double x, final double y, final double z, final float yaw, final float pitch) throws ReflectiveOperationException {
        BotPuppet.send(this.viewer, BotPuppet.infoPacket.newInstance(EnumSet.of((Enum) BotPuppet.addPlayer),
                BotPuppet.infoEntry.newInstance(p.uuid, p.profile, false, 0, BotPuppet.survival, null, true, 0, null)));
        BotPuppet.send(this.viewer, BotPuppet.addPacket.newInstance(p.id, p.uuid, x, y, z, pitch, yaw, BotPuppet.playerType, 0, BotPuppet.zero, (double) yaw));
        if (p.team != null)
            BotPuppet.send(this.viewer, BotPuppet.teamPacket.invoke(null, p.team, true));
        p.shown = true;
        p.crouched = p.f.has(Math.max(0, p.frame), Recording.SNEAKING);
        this.data(p);
        p.shownGear = null;
        this.gear(p);
        p.lastYaw = yaw;
        this.head(p, yaw);
    }

    private void despawn(final Puppet p) {
        p.shown = false;
        p.frame = -1;
        try {
            BotPuppet.send(this.viewer, BotPuppet.removePacket.newInstance((Object) new int[]{p.id}));
            BotPuppet.send(this.viewer, BotPuppet.infoRemovePacket.newInstance(List.of(p.uuid)));
            if (p.team != null)
                BotPuppet.send(this.viewer, BotPuppet.teamRemovePacket.invoke(null, p.team));
        } catch (final ReflectiveOperationException | RuntimeException ignored) {
            // they've left
        }
    }

    /** A sound where this fighter is drawn now, to the viewer only. */
    private void sound(final Puppet p, final org.bukkit.Sound sound) {
        final double[] at = p.at(this.clock);
        this.viewer.playSound(new org.bukkit.Location(this.world, this.cx + at[1], this.cy + at[2] + 1, this.cz + at[3]), sound,
                org.bukkit.SoundCategory.PLAYERS, 1f, 0.9f + (float) Math.random() * 0.2f);
    }

    /** Legacy colour codes (&a...) to the game's colour names, for a name tag's colour. */
    private static final String CODES = "0123456789abcdef";
    private static final String[] COLOURS = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"};

    /**
     * An offline fighter's name tag (their LuckPerms prefix and suffix, as TAB would show them), as a team for the viewer's
     * game; null for one who's online (TAB's own team, which has their name, already shows on the replay) or without
     * LuckPerms. Off the main thread: it may load them from LuckPerms' storage.
     */
    private static Object nameTag(final Recording.Fighter f) {
        if (Bukkit.getPlayer(f.uuid) != null || !Bukkit.getPluginManager().isPluginEnabled("LuckPerms"))
            return null;
        try {
            final String[] fixes = LuckPermsTags.of(f.uuid);
            if (fixes == null || (fixes[0] == null && fixes[1] == null))
                return null;
            final net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer legacy =
                    net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.builder().character('&').hexColors().build();
            final String name = f.name.length() > 16 ? f.name.substring(0, 16) : f.name;
            final Object team = BotPuppet.teamCtor.newInstance(BotPuppet.scoreboardCtor.newInstance(), "sa_rp_" + Integer.toString(Math.abs(f.uuid.hashCode()), 36));
            if (fixes[0] != null) {
                BotPuppet.setPrefix.invoke(team, BotPuppet.asVanilla.invoke(null, legacy.deserialize(fixes[0])));
                // The name takes the prefix's last colour, as it would in chat
                final java.util.regex.Matcher m = java.util.regex.Pattern.compile("[&\u00a7]([0-9a-fA-F])").matcher(fixes[0]);
                String last = null;
                while (m.find())
                    last = COLOURS[CODES.indexOf(Character.toLowerCase(m.group(1).charAt(0)))];
                final Object colour = last == null ? null : BotPuppet.formattingByName.invoke(null, last);
                if (colour != null)
                    BotPuppet.setColour.invoke(team, colour);
            }
            if (fixes[1] != null)
                BotPuppet.setSuffix.invoke(team, BotPuppet.asVanilla.invoke(null, legacy.deserialize(fixes[1])));
            @SuppressWarnings("unchecked") final java.util.Collection<String> members = (java.util.Collection<String>) BotPuppet.teamPlayers.invoke(team);
            members.add(name);
            return team;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Everything that touches LuckPerms' classes, only loaded once LuckPerms is known to be there. */
    private static final class LuckPermsTags {
        /** {prefix, suffix} (either may be null), or null if they can't be loaded. */
        static String[] of(final UUID uuid) {
            final net.luckperms.api.LuckPerms lp = net.luckperms.api.LuckPermsProvider.get();
            final net.luckperms.api.model.user.User user = lp.getUserManager().loadUser(uuid).join();
            if (user == null)
                return null;
            final net.luckperms.api.cacheddata.CachedMetaData meta = user.getCachedData().getMetaData();
            return new String[]{meta.getPrefix(), meta.getSuffix()};
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
        // (gliding with an elytra: its flag and pose)
        final byte flags = (byte) ((p.crouched ? 0x02 : 0) | (p.sprinting ? 0x08 : 0) | (p.flying ? 0x80 : 0));
        BotPuppet.send(this.viewer, BotPuppet.dataPacket.newInstance(p.id, List.of(
                BotPuppet.dataValue.invoke(null, sharedFlags, flags),
                BotPuppet.dataValue.invoke(null, poseData, p.flying && gliding != null ? gliding : p.crouched ? crouching : standing),
                BotPuppet.dataValue.invoke(null, customisation, (byte) 0x7F))));
    }

    private void finished() {
        this.over = true;
        if (this.rec.session) {
            Recording.Fighter best = null;
            for (final Recording.Fighter f : this.rec.fighters)
                if (f.kills > 0 && (best == null || f.kills > best.kills))
                    best = f;
            this.viewer.sendMessage(MiniMessage.miniMessage().deserialize("<gold>Replay over:</gold> <gray><most> "
                            + "<click:run_command:/arenareplay file " + this.fileArg() + "><aqua>[Watch again]</aqua></click> <click:run_command:/arenareplay stop><red>[Close]</red></click>",
                    Placeholder.unparsed("most", best == null ? "nobody got a kill." : "most kills: " + best.name + " (" + best.kills + ").")));
            return;
        }
        final List<String> won = new ArrayList<>();
        for (final Recording.Fighter f : this.rec.fighters)
            if (f.won)
                won.add(f.name);
        this.viewer.sendMessage(MiniMessage.miniMessage().deserialize("<gold>Replay over:</gold> <white><won></white> <gray>won. "
                        + "<click:run_command:/arenareplay file " + this.fileArg() + "><aqua>[Watch again]</aqua></click> <click:run_command:/arenareplay stop><red>[Close]</red></click>",
                Placeholder.unparsed("won", won.isEmpty() ? "nobody" : String.join(" & ", won))));
    }

    /** This recording as /arenareplay file takes it: its day folder and name. */
    private String fileArg() {
        final java.io.File f = this.rec.file;
        return f == null ? "" : f.getParentFile().getName() + "/" + f.getName();
    }

    /** The players fighting in the replay's arena now (any match there). */
    private java.util.Set<Player> liveFighters() {
        final java.util.Set<Player> out = new java.util.HashSet<>();
        final Arena arena = SpawnArena.arenas == null ? null : SpawnArena.arenas.of(this.rec.arena);
        if (arena != null)
            for (final Fight fight : arena.getFights())
                out.addAll(fight.getFighters());
        out.remove(this.viewer);
        return out;
    }

    /** Hides the arena's live fighters from the viewer, and shows again those that have stopped fighting. */
    private void hideFighters() {
        final java.util.Set<Player> live = this.liveFighters();
        for (final java.util.Iterator<UUID> it = this.hiddenFighters.iterator(); it.hasNext(); ) {
            final Player p = Bukkit.getPlayer(it.next());
            if (p == null) {
                it.remove();
            } else if (!live.contains(p)) {
                it.remove();
                this.showAgain(p);
            }
        }
        for (final Player p : live)
            if (this.viewer.canSee(p) && p.getWorld() == this.viewer.getWorld()) {
                this.viewer.hidePlayer(SpawnArena.INSTANCE, p);
                this.hiddenFighters.add(p.getUniqueId());
            }
    }

    /** Every fighter the replay hid, shown again. */
    private void showFighters() {
        for (final UUID id : this.hiddenFighters) {
            final Player p = Bukkit.getPlayer(id);
            if (p != null)
                this.showAgain(p);
        }
        this.hiddenFighters.clear();
    }

    /**
     * Shows {@code p} to the viewer again, unless a match hides them from each other (the viewer has since started
     * fighting in another match in the same arena: MULTI arenas hide other matches, with the same hiding).
     */
    private void showAgain(final Player p) {
        if (!p.isOnline())
            return;
        final Arena mine = SpawnArena.arenas == null ? null : SpawnArena.arenas.of(this.viewer);
        final Fight myFight = mine == null ? null : mine.getFight(this.viewer);
        if (myFight != null) {
            final Arena theirs = SpawnArena.arenas.of(p);
            final Fight theirFight = theirs == null ? null : theirs.getFight(p);
            if (theirFight != null && theirFight != myFight && theirs.getArenaName().equalsIgnoreCase(mine.getArenaName()))
                return;
        }
        this.viewer.showPlayer(SpawnArena.INSTANCE, p);
    }

    /** Whether {@code p} is watching a replay of a match in this arena (its live bots aren't drawn for them). */
    public static boolean watchingIn(final Player p, final Arena arena) {
        final ArenaReplay r = WATCHING.get(p.getUniqueId());
        return r != null && arena != null && r.rec.arena.equalsIgnoreCase(arena.getArenaName());
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
        this.showFighters();
        for (final Puppet p : this.puppets)
            if (p.shown)
                this.despawn(p);
        if (!this.ice.isEmpty())
            SumoIce.showReal(this.viewer, this.ice);
        if (!this.shownCrystals.isEmpty()) {
            try {
                final int[] ids = this.shownCrystals.values().stream().mapToInt(Integer::intValue).toArray();
                BotPuppet.send(this.viewer, BotPuppet.removePacket.newInstance((Object) ids));
            } catch (final ReflectiveOperationException | RuntimeException ignored) {
                // they've left
            }
            this.shownCrystals.clear();
        }
        if (!this.shownBlocks.isEmpty()) {
            final Map<Location, org.bukkit.block.data.BlockData> real = new java.util.HashMap<>();
            for (final Location at : this.shownBlocks.keySet())
                if (this.realBlocks.containsKey(at))
                    real.put(at, this.realBlocks.get(at));
            this.shownBlocks.clear();
            this.viewer.sendMultiBlockChange(real);
        }
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
            gliding = pose.getField("FALL_FLYING").get(null);
            standing = pose.getField("STANDING").get(null);
            customisation = field(avatar, "DATA_PLAYER_MODE_CUSTOMISATION");
            endCrystalType = Class.forName("net.minecraft.world.entity.EntityType", true, cl).getField("END_CRYSTAL").get(null);
            final Class<?> event = Class.forName("net.minecraft.network.protocol.game.ClientboundEntityEventPacket", true, cl);
            entityEventPacket = event.getConstructor(entity, byte.class);
            entityEventId = BotPuppet.field(event, "entityId");
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
