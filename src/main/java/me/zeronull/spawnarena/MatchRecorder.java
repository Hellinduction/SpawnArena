package me.zeronull.spawnarena;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Records sumo and spleef matches between players (spleef free-for-all rounds too), tick by tick, for one day training a bot on how real players play: where each
 * fighter is and looks, the keys they're holding (forward, back, left, right, jump, sneak, sprint), on the ground or
 * not, and when they swing, land a hit or are hit. With who won, and each fighter's stats there as the match started
 * (Elo, wins, losses, streaks, time spent fighting there, bots beaten), their ping and their client, to pick out
 * matches by later. A finished match (a winner, not a draw) is saved, compressed, to
 * recordings/&lt;date&gt;/ in the plugin's folder (about 10 KB for a 30-second 1v1); past recordingMaxMb, the oldest go.
 * Bot matches aren't recorded. Spleef: every floor block broken too, when, by whom and how (to be shown in replays and
 * checked). Crystal PvP too: blocks placed, broken and blown up, crystals placed, hit and gone, totems popped, and each
 * fighter's health and what they wear and hold as it changes. A crystal free-for-all is recorded in parts (people come
 * and go): see {@link #ffaJoined}. The format: see {@link #write}.
 */
public final class MatchRecorder implements Listener {
    private static final int FORMAT = 5;
    /** The most events kept for one match (a long crystal match: a few thousand) */
    private static final int MAX_EVENTS = 50_000;
    /** The most block changes kept for one match (a long spleef free-for-all breaks a few hundred) */
    private static final int MAX_BLOCKS = 20_000;
    /** Header flags (format 3): a spleef match; a free-for-all round (each fighter on a team of their own); (format 4) crystal PvP */
    static final int SPLEEF = 1, FREE_FOR_ALL = 2, CRYSTAL = 4, SESSION = 8;
    /** A plain arena: not sumo (out in the water), spleef or crystal: fought with swords and the like (flags, format 5) */
    static final int KIT = 16;

    /** Neither sumo, spleef nor crystal: the arena's own fights (kit PvP). */
    static boolean isKit(final Arena arena) {
        return !arena.isDeathOnTouchLiquid() && !arena.isSpleef() && !arena.isCrystalPvp();
    }
    /** The longest it records a match for (ten minutes); after that it stops taking frames */
    private static final int MAX_FRAMES = 20 * 60 * 10;
    private static final Map<Fight, MatchRecorder> RECORDING = new ConcurrentHashMap<>();
    /** Fighter -> their match's recording (for swings and hits) */
    private static final Map<UUID, MatchRecorder> OF = new ConcurrentHashMap<>();
    /** Crystal PvP: a crystal placed in a recorded match -> that recording */
    private static final Map<UUID, MatchRecorder> CRYSTALS = new ConcurrentHashMap<>();

    private final Fight fight;
    private final long startedAt = System.currentTimeMillis();
    private final Location centre;
    // (a free-for-all's part gets fighters as they come in, while others' frames are being taken)
    private final List<Track> tracks = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** A part of a free-for-all session (people come and go): see {@link #ffaJoined} */
    private final boolean session;
    private volatile List<Player> winners;
    /** Blocks changed during the match (spleef's floor broken), in order */
    private final List<BlockChange> blocks = new ArrayList<>();

    /** A block changed: when (ms since the start), where (from the centre's block), by which fighter (-1: none), how, to what. */
    private record BlockChange(int ms, int x, int y, int z, int by, int how, String data) {}

    /** How a block was changed: broken by hand, by a snowball landing on it, placed (by hand), blown up */
    static final int BY_HAND = 0, BY_SNOWBALL = 1, PLACED = 2, EXPLODED = 3;

    /** Something that happened to a fighter (or that they did): when, which fighter (-1: none), what, and its details. */
    private record Event(int ms, int fighter, int kind, byte[] data) {}
    private final List<Event> events = new ArrayList<>();

    /** Event kinds: see {@link #write} */
    static final int EQUIP = 1, TOTEM = 2, CRYSTAL_PLACED = 3, CRYSTAL_HIT = 4, CRYSTAL_GONE = 5, HEALTH = 6, KILL = 7, DEATH = 8;
    /** The equipment slots EQUIP numbers (0 to 5) */
    private static final org.bukkit.inventory.EquipmentSlot[] GEAR = {org.bukkit.inventory.EquipmentSlot.HAND,
            org.bukkit.inventory.EquipmentSlot.OFF_HAND, org.bukkit.inventory.EquipmentSlot.FEET, org.bukkit.inventory.EquipmentSlot.LEGS,
            org.bukkit.inventory.EquipmentSlot.CHEST, org.bukkit.inventory.EquipmentSlot.HEAD};

    private interface Details {
        void write(DataOutputStream out) throws IOException;
    }

    /** Keeps an event (any thread). */
    private void event(final int fighter, final int kind, final Details details) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(16);
        try {
            details.write(new DataOutputStream(bytes));
        } catch (final IOException ignored) {
            // (in memory: can't happen)
        }
        final Event e = new Event((int) (System.currentTimeMillis() - this.startedAt), fighter, kind, bytes.toByteArray());
        synchronized (this.events) {
            if (this.events.size() < MAX_EVENTS)
                this.events.add(e);
        }
    }

    /** One fighter's frames. */
    private static final class Track {
        /** (a free-for-all: the one who came back in, after leaving the server and joining again) */
        volatile Player player;
        /** A free-for-all: in the arena now (frames are only taken then); kills and deaths in this part */
        volatile boolean in = true;
        final java.util.concurrent.atomic.AtomicInteger kills = new java.util.concurrent.atomic.AtomicInteger(),
                deaths = new java.util.concurrent.atomic.AtomicInteger();
        final int team;
        /** Their stats there as the match started (filled in shortly after, off the main thread) */
        volatile int elo = StatsManager.START_ELO, wins, losses, winStreak, bestWinStreak, fightSeconds;
        volatile Map<String, Integer> botsBeaten = Map.of();
        /** Their ping as it started, all their pings added up (one a frame: for the average), and their client */
        final int pingAtStart;
        long pingSum;
        final String client;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(16 * 1024);
        final DataOutputStream out = new DataOutputStream(this.bytes);
        int frames;
        /** Swung, landed a hit, was hit, since the last frame */
        volatile boolean swung, landed, hurt;
        /** Since the last frame: hit by a fighter's arrow, snowball or rod; teleported (e.g. an anticheat's setback) */
        volatile boolean shot, teleported;
        /** Holding the attack button on a block (each tick of it swings the arm too) */
        volatile boolean digging;
        /** The velocity the server last sent them since the last frame (knockback), or null; whether it was knockback */
        volatile org.bukkit.util.Vector velocity;
        volatile boolean knockedBack;
        /** Which fighter (their place in the recording) they last landed a hit on since the last frame, or -1 */
        volatile int landedOn = -1;
        volatile ScheduledTask task;
        /** What they wore and held as last recorded (type, "*" if enchanted), and their health (and absorption) */
        final String[] gear = new String[6];
        float health = -1;

        Track(final Player player, final int team) {
            this.player = player;
            this.team = team;
            this.pingAtStart = player.getPing();
            final String brand = player.getClientBrandName();
            this.client = brand == null ? "" : brand;
        }
    }

    private MatchRecorder(final Fight fight, final Location centre) {
        this(fight, centre, false);
    }

    private MatchRecorder(final Fight fight, final Location centre, final boolean session) {
        this.fight = fight;
        this.centre = centre;
        this.session = session;
    }

    /** Only listens (swings and hits); recordings are made by {@link #start}. */
    public MatchRecorder() {
        this.fight = null;
        this.centre = null;
        this.session = false;
    }

    private static boolean enabled() {
        return SpawnArena.settings == null || SpawnArena.settings.recordSumoMatches;
    }

    /** A fight has just gone live: a match between players is recorded, in any arena (a free-for-all: see ffaJoined). */
    static void start(final Fight fight) {
        final Arena arena = fight.getArena();
        if (!enabled() || fight.withBots() || arena.isFfa())
            return;
        final Location a = arena.spawnPoint1, b = arena.spawnPoint2;
        if (a == null || b == null)
            return;
        final MatchRecorder rec = new MatchRecorder(fight, a.clone().add(b).multiply(0.5));
        final List<Player> fighters = fight.getFighters();
        if (fighters.isEmpty())
            return;
        final boolean round = fight.getMode() == FightMode.FREE_FOR_ALL;
        if (fighters.size() > 100)
            return; // (a fighter's number is a byte)
        final List<Player> firstTeam = fight.getTeamOf(fighters.get(0));
        for (final Player p : fighters) // (a free-for-all round: everyone on a team of their own)
            rec.tracks.add(new Track(p, round ? rec.tracks.size() : firstTeam.contains(p) ? 0 : 1));
        RECORDING.put(fight, rec);
        for (final Track t : rec.tracks) {
            OF.put(t.player.getUniqueId(), rec);
            t.task = t.player.getScheduler().runAtFixedRate(SpawnArena.INSTANCE, task -> rec.frame(t), null, 1L, 1L);
        }
        rec.loadStats(rec.tracks);
    }

    /** Their stats there as it starts, for picking out matches later (the good players', the close ones...). */
    private void loadStats(final List<Track> tracks) {
        if (SpawnArena.statsManager != null)
            Threads.async(() -> {
                final StatsManager stats = SpawnArena.statsManager;
                final String name = this.fight.getArena().getArenaName();
                for (final Track t : tracks) {
                    final UUID id = t.player.getUniqueId();
                    t.elo = stats.getElo(id, name);
                    t.wins = stats.getWins(id, name);
                    t.losses = stats.getLosses(id, name);
                    t.winStreak = stats.getWinStreak(id, name);
                    t.bestWinStreak = stats.getBestWinStreak(id, name);
                    t.fightSeconds = stats.getFightSeconds(id, name);
                    t.botsBeaten = stats.botWins(id, name);
                }
            });
    }

    // ── A crystal free-for-all: recorded in parts ───────────────────────────

    /**
     * A free-for-all session (crystal, sumo, any arena) is recorded in parts, each of crystalFfaRecordingSeconds (and one ends whenever the
     * arena's put back, and when the session ends): each has everyone who was in at the time (on a team of their own),
     * from when they came in to when they went out, again if they came back; with their kills and deaths. A part is kept
     * if at least two of them were in it for 5 seconds or more.
     */
    private static boolean recordsSession(final Fight fight) {
        final Arena arena = fight.getArena();
        return enabled() && arena.isFfa() && arena.spawnPoint1 != null && arena.spawnPoint2 != null;
    }

    private static MatchRecorder newPart(final Fight fight) {
        final Location a = fight.getArena().spawnPoint1, b = fight.getArena().spawnPoint2;
        return new MatchRecorder(fight, a.clone().add(b).multiply(0.5), true);
    }

    private static long partMillis() {
        return Math.max(30, SpawnArena.settings == null ? 180 : SpawnArena.settings.crystalFfaRecordingSeconds) * 1000L;
    }

    /** A free-for-all: {@code p} has just come in (any thread). */
    public static void ffaJoined(final Fight fight, final Player p) {
        if (!recordsSession(fight))
            return;
        RECORDING.computeIfAbsent(fight, MatchRecorder::newPart).admit(p);
    }

    private void admit(final Player p) {
        Track t = this.track(p);
        if (t == null) {
            synchronized (this.tracks) {
                if (this.tracks.size() >= 100)
                    return; // (a fighter's number is a byte)
                t = new Track(p, this.tracks.size());
                this.tracks.add(t);
            }
            this.loadStats(List.of(t));
        } else {
            t.player = p;
            t.teleported = true; // (back in: not drawn gliding over from where they went out)
        }
        t.in = true;
        OF.put(p.getUniqueId(), this);
        final Track track = t;
        final ScheduledTask running = track.task;
        if (running == null || running.isCancelled())
            track.task = p.getScheduler().runAtFixedRate(SpawnArena.INSTANCE, task -> this.frame(track), null, 1L, 1L);
    }

    /** A free-for-all: {@code p} is out (died: with whoever gets the kill), any thread. */
    public static void ffaOut(final Fight fight, final Player p, final boolean died, final List<UUID> killers) {
        final MatchRecorder rec = RECORDING.get(fight);
        final Track t = rec == null || !rec.session ? null : rec.track(p);
        if (t == null)
            return;
        t.in = false;
        final ScheduledTask task = t.task;
        if (task != null)
            task.cancel();
        t.task = null;
        OF.remove(p.getUniqueId(), rec);
        if (!died)
            return;
        t.deaths.incrementAndGet();
        final int victim = rec.tracks.indexOf(t);
        for (final UUID killer : killers) {
            Track k = null;
            for (final Track x : rec.tracks)
                if (x.player.getUniqueId().equals(killer))
                    k = x;
            if (k == null || k == t)
                continue;
            k.kills.incrementAndGet();
            rec.event(rec.tracks.indexOf(k), KILL, out -> out.writeByte(victim));
        }
        rec.event(victim, DEATH, out -> {});
    }

    /** A free-for-all, every second: a part that's run its time is finished, and the next begun. */
    public static void ffaTick(final Fight fight) {
        final MatchRecorder rec = RECORDING.get(fight);
        if (rec != null && rec.session && System.currentTimeMillis() - rec.startedAt >= partMillis())
            ffaNextPart(fight);
    }

    /** A free-for-all: this part is finished (the arena's being put back, say), and the next begun with everyone in now. */
    public static void ffaNextPart(final Fight fight) {
        final MatchRecorder old = RECORDING.get(fight);
        if (old == null || !old.session)
            return;
        final MatchRecorder next = newPart(fight);
        if (!RECORDING.replace(fight, old, next))
            return;
        old.finishPart();
        for (final Player p : fight.getFighters())
            next.admit(p);
    }

    /** Stops taking this part's frames, and keeps it if at least two were in it for 5 seconds or more. */
    private void finishPart() {
        for (final Track t : this.tracks) {
            final ScheduledTask task = t.task;
            if (task != null)
                task.cancel();
            OF.remove(t.player.getUniqueId(), this);
        }
        CRYSTALS.values().removeIf(r -> r == this);
        if (!SpawnArena.INSTANCE.isEnabled())
            return;
        int fought = 0;
        for (final Track t : this.tracks)
            synchronized (t) {
                if (t.frames >= 100)
                    fought++;
            }
        if (fought >= 2) {
            this.winners = List.of();
            Threads.async(this::save);
        }
    }

    /**
     * A block in this fight's arena changed (spleef: floor broken by {@code by}, by hand or snowball): kept with the
     * recording, if there is one. Any thread.
     */
    static void blockChanged(final Fight fight, final org.bukkit.block.Block block, final Player by, final int how,
                             final org.bukkit.block.data.BlockData now) {
        final MatchRecorder rec = RECORDING.get(fight);
        if (rec == null)
            return;
        final Track t = by == null ? null : rec.track(by);
        rec.block(block, t == null ? -1 : rec.tracks.indexOf(t), how, now.getAsString());
    }

    /** Crystal PvP: a crystal placed in this fight's arena, by {@code by} (null: not known). Any thread. */
    public static void crystalPlaced(final Fight fight, final org.bukkit.entity.Entity crystal, final Player by) {
        final MatchRecorder rec = fight == null ? null : RECORDING.get(fight);
        if (rec == null)
            return;
        final Track t = by == null ? null : rec.track(by);
        CRYSTALS.put(crystal.getUniqueId(), rec);
        final Location at = crystal.getLocation();
        final int id = crystal.getEntityId();
        rec.event(t == null ? -1 : rec.tracks.indexOf(t), CRYSTAL_PLACED, out -> {
            out.writeInt(id);
            out.writeFloat((float) (at.getX() - rec.centre.getX()));
            out.writeFloat((float) (at.getY() - rec.centre.getY()));
            out.writeFloat((float) (at.getZ() - rec.centre.getZ()));
        });
    }

    /** The recording of a crystal match whose arena this is in, or null. */
    private static MatchRecorder crystalMatchAt(final Location at) {
        if (RECORDING.isEmpty())
            return null;
        final Arena arena = CrystalPvp.arenaAt(at);
        if (arena == null)
            return null;
        for (final MatchRecorder rec : RECORDING.values())
            if (rec.fight.getArena().equals(arena))
                return rec;
        return null;
    }

    /** A block changed in a recorded match (see {@link #blockChanged}), from inside this class. */
    private void block(final org.bukkit.block.Block block, final int by, final int how, final String data) {
        final BlockChange change = new BlockChange((int) (System.currentTimeMillis() - this.startedAt),
                block.getX() - (int) Math.floor(this.centre.getX()), block.getY() - (int) Math.floor(this.centre.getY()),
                block.getZ() - (int) Math.floor(this.centre.getZ()), by, how, data);
        synchronized (this.blocks) {
            if (this.blocks.size() < MAX_BLOCKS)
                this.blocks.add(change);
        }
    }

    /** The fight has a result: it's kept once it ends. */
    static void result(final Fight fight, final List<Player> winners) {
        final MatchRecorder rec = RECORDING.get(fight);
        if (rec != null)
            rec.winners = new ArrayList<>(winners);
    }

    /** The fight is over: recording stops, and it's saved if it had a result. */
    static void stop(final Fight fight) {
        final MatchRecorder rec = RECORDING.remove(fight);
        if (rec == null)
            return;
        if (rec.session) {
            rec.finishPart();
            return;
        }
        for (final Track t : rec.tracks) {
            final ScheduledTask task = t.task;
            if (task != null)
                task.cancel();
            OF.remove(t.player.getUniqueId(), rec);
        }
        CRYSTALS.values().removeIf(r -> r == rec);
        if (rec.winners == null || !SpawnArena.INSTANCE.isEnabled())
            return; // a draw, or the server's stopping: nothing to learn from
        Threads.async(rec::save);
    }

    /** On the fighter's thread, each tick. */
    private void frame(final Track t) {
        final Player p = t.player;
        if (!t.in || !p.isOnline() || t.frames >= MAX_FRAMES || this.fight.getState() != FightState.IN_FIGHT)
            return;
        final Location at = p.getLocation();
        // What they wear and hold, and their health, as it changes (the first frame: all of it)
        final int me = this.tracks.indexOf(t);
        final org.bukkit.inventory.EntityEquipment equipment = p.getEquipment();
        for (int s = 0; s < GEAR.length; s++) {
            final org.bukkit.inventory.ItemStack item = equipment.getItem(GEAR[s]);
            final boolean enchanted = !item.getEnchantments().isEmpty();
            final String key = item.getType().name() + (enchanted ? "*" : "");
            if (!key.equals(t.gear[s])) {
                t.gear[s] = key;
                final int slot = s;
                final String type = item.getType().getKey().toString();
                this.event(me, EQUIP, out -> {
                    out.writeByte(slot);
                    out.writeUTF(type);
                    out.writeByte(enchanted ? 1 : 0);
                });
            }
        }
        final float health = (float) (p.getHealth() + p.getAbsorptionAmount());
        if (health != t.health) {
            t.health = health;
            this.event(me, HEALTH, out -> out.writeFloat(health));
        }
        final Input input = p.getCurrentInput();
        int keys = 0;
        if (input.isForward()) keys |= 1;
        if (input.isBackward()) keys |= 2;
        if (input.isLeft()) keys |= 4;
        if (input.isRight()) keys |= 8;
        if (input.isJump()) keys |= 16;
        if (input.isSneak()) keys |= 32;
        if (input.isSprint()) keys |= 64;
        int flags = 0;
        if (p.isOnGround()) flags |= 1;
        if (p.isSprinting()) flags |= 2;
        if (p.isSneaking()) flags |= 4;
        if (t.swung) flags |= 8;
        if (t.landed) flags |= 16;
        if (t.hurt) flags |= 32;
        int extra = 0;
        final org.bukkit.util.Vector velocity = t.velocity;
        if (velocity != null) extra |= 1;
        if (t.digging) extra |= 2;
        if (t.shot) extra |= 4;
        if (t.teleported) extra |= 8;
        final int landedOn = t.landedOn;
        if (landedOn >= 0) extra |= 16;
        t.swung = t.landed = t.hurt = t.shot = t.teleported = false;
        t.velocity = null;
        t.knockedBack = false;
        t.landedOn = -1;
        synchronized (t) {
            t.pingSum += p.getPing();
            try {
                t.out.writeInt((int) (System.currentTimeMillis() - this.startedAt));
                t.out.writeFloat((float) (at.getX() - this.centre.getX()));
                t.out.writeFloat((float) (at.getY() - this.centre.getY()));
                t.out.writeFloat((float) (at.getZ() - this.centre.getZ()));
                t.out.writeFloat(at.getYaw());
                t.out.writeFloat(at.getPitch());
                t.out.writeByte(keys);
                t.out.writeByte(flags);
                t.out.writeShort(Math.min(p.getPing(), Short.MAX_VALUE));
                t.out.writeByte(extra);
                if (velocity != null) {
                    t.out.writeFloat((float) velocity.getX());
                    t.out.writeFloat((float) velocity.getY());
                    t.out.writeFloat((float) velocity.getZ());
                }
                if (landedOn >= 0)
                    t.out.writeByte(landedOn);
                t.frames++;
            } catch (final IOException ignored) {
                // (in memory: can't happen)
            }
        }
    }

    private Track track(final Player p) {
        for (final Track t : this.tracks)
            if (t.player.equals(p))
                return t;
        return null;
    }

    // ── Swings and hits ─────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(final PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING)
            return;
        final MatchRecorder rec = OF.get(e.getPlayer().getUniqueId());
        final Track t = rec == null ? null : rec.track(e.getPlayer());
        if (t != null)
            t.swung = true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final EntityDamageByEntityEvent e) {
        // A crystal from a recorded match hit (popped): by which fighter, by hand or with something they shot
        if (e.getEntity() instanceof org.bukkit.entity.EnderCrystal crystal) {
            final MatchRecorder rec = CRYSTALS.get(crystal.getUniqueId());
            Player by = e.getDamager() instanceof Player p ? p : null;
            if (by == null && e.getDamager() instanceof org.bukkit.entity.Projectile projectile && projectile.getShooter() instanceof Player p)
                by = p;
            final Track t = rec == null || by == null ? null : rec.track(by);
            if (t != null) {
                final int id = crystal.getEntityId();
                rec.event(rec.tracks.indexOf(t), CRYSTAL_HIT, out -> out.writeInt(id));
            }
            return;
        }
        if (!(e.getEntity() instanceof Player victim))
            return;
        final MatchRecorder rec = OF.get(victim.getUniqueId());
        if (rec == null)
            return;
        final Track hurt = rec.track(victim);
        if (hurt == null)
            return;
        // An arrow, snowball or rod from another fighter: knocked back, but not a hit with their hand
        if (e.getDamager() instanceof org.bukkit.entity.Projectile projectile) {
            if (projectile.getShooter() instanceof Player shooter && OF.get(shooter.getUniqueId()) == rec)
                hurt.shot = true;
            return;
        }
        if (!(e.getDamager() instanceof Player attacker) || OF.get(attacker.getUniqueId()) != rec)
            return;
        final Track hitter = rec.track(attacker);
        hurt.hurt = true;
        if (hitter != null) {
            hitter.landed = true;
            hitter.landedOn = rec.tracks.indexOf(hurt);
        }
    }

    /**
     * The knockback a fighter is sent: the velocity they have plus the push, as it goes out to them (checked against
     * how they moved after). PlayerVelocityEvent can't be used for this: for a hit it has the velocity from before it.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKnockback(final io.papermc.paper.event.entity.EntityKnockbackEvent e) {
        if (!(e.getEntity() instanceof Player p))
            return;
        final Track t = trackOf(p);
        if (t != null) {
            t.velocity = p.getVelocity().add(e.getKnockback()); // a sprint hit's extra push comes after: the last one is what's sent
            t.knockedBack = true;
        }
    }

    /** Any other velocity a fighter is sent (a plugin's push): still marked, so it isn't taken for them moving. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(final org.bukkit.event.player.PlayerVelocityEvent e) {
        final Track t = trackOf(e.getPlayer());
        if (t != null && !t.knockedBack)
            t.velocity = e.getVelocity().clone();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDig(final org.bukkit.event.player.PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.LEFT_CLICK_BLOCK)
            return;
        final Track t = trackOf(e.getPlayer());
        if (t != null)
            t.digging = true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDigStop(final org.bukkit.event.block.BlockDamageAbortEvent e) {
        final Track t = trackOf(e.getPlayer());
        if (t != null)
            t.digging = false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(final org.bukkit.event.player.PlayerTeleportEvent e) {
        final Track t = trackOf(e.getPlayer());
        if (t != null)
            t.teleported = true;
    }

    // ── Crystal PvP: totems, crystals and blocks ────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTotem(final org.bukkit.event.entity.EntityResurrectEvent e) {
        if (!(e.getEntity() instanceof Player p))
            return;
        final MatchRecorder rec = OF.get(p.getUniqueId());
        final Track t = rec == null ? null : rec.track(p);
        if (t != null)
            rec.event(rec.tracks.indexOf(t), TOTEM, out -> {});
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCrystalGone(final com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent e) {
        if (!(e.getEntity() instanceof org.bukkit.entity.EnderCrystal crystal) || CRYSTALS.isEmpty())
            return;
        final MatchRecorder rec = CRYSTALS.remove(crystal.getUniqueId());
        if (rec != null) {
            final int id = crystal.getEntityId();
            rec.event(-1, CRYSTAL_GONE, out -> out.writeInt(id));
        }
    }

    /** A recorded fighter placing a block (each block of a bed or door), in any arena but spleef. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(final org.bukkit.event.block.BlockPlaceEvent e) {
        final MatchRecorder rec = OF.get(e.getPlayer().getUniqueId());
        final Track t = rec == null || rec.fight.getArena().isSpleef() ? null : rec.track(e.getPlayer());
        if (t == null)
            return;
        final int me = rec.tracks.indexOf(t);
        final List<org.bukkit.block.BlockState> placed = e instanceof org.bukkit.event.block.BlockMultiPlaceEvent multi
                ? multi.getReplacedBlockStates() : List.of(e.getBlockReplacedState());
        for (final org.bukkit.block.BlockState state : placed) {
            final org.bukkit.block.Block block = state.getBlock();
            rec.block(block, me, PLACED, block.getBlockData().getAsString());
        }
    }

    /** A recorded fighter breaking a block, in any arena but spleef (its floor is recorded by Spleef). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(final org.bukkit.event.block.BlockBreakEvent e) {
        final MatchRecorder rec = OF.get(e.getPlayer().getUniqueId());
        final Track t = rec == null || rec.fight.getArena().isSpleef() ? null : rec.track(e.getPlayer());
        if (t != null)
            rec.block(e.getBlock(), rec.tracks.indexOf(t), BY_HAND, "minecraft:air");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(final org.bukkit.event.entity.EntityExplodeEvent e) {
        if (RECORDING.isEmpty() || e.blockList().isEmpty())
            return;
        MatchRecorder rec = CRYSTALS.get(e.getEntity().getUniqueId());
        if (rec == null)
            rec = crystalMatchAt(e.getLocation());
        if (rec != null)
            for (final org.bukkit.block.Block block : e.blockList())
                rec.block(block, -1, EXPLODED, "minecraft:air");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(final org.bukkit.event.block.BlockExplodeEvent e) {
        if (RECORDING.isEmpty())
            return;
        final MatchRecorder rec = crystalMatchAt(e.getBlock().getLocation());
        if (rec == null)
            return;
        rec.block(e.getBlock(), -1, EXPLODED, "minecraft:air"); // (the anchor or bed itself)
        for (final org.bukkit.block.Block block : e.blockList())
            rec.block(block, -1, EXPLODED, "minecraft:air");
    }

    private static Track trackOf(final Player p) {
        final MatchRecorder rec = OF.get(p.getUniqueId());
        return rec == null ? null : rec.track(p);
    }

    // ── Saving ──────────────────────────────────────────────────────────────

    private void save() {
        try {
            final File dir = new File(SpawnArena.INSTANCE.getDataFolder(), "recordings/" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
            if (!dir.isDirectory() && !dir.mkdirs())
                return;
            final String name = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss")) + "_" + this.fight.getArena().getArenaName()
                    + "_" + String.join("-", this.tracks.stream().limit(4).map(t -> t.player.getName()).toList())
                    + (this.tracks.size() > 4 ? "-and" + (this.tracks.size() - 4) : "") + ".sarec.gz";
            final File file = new File(dir, name.replaceAll("[^A-Za-z0-9_.-]", ""));
            this.write(file);
            MatchIndex.added(file); // for its fighters' /matches
            prune();
        } catch (final Exception e) {
            SpawnArena.INSTANCE.getLogger().log(Level.WARNING, "Couldn't save a match recording", e);
        }
    }

    /**
     * The file, gzipped, big-endian (Java's DataOutputStream; text is modified UTF-8 with a 2-byte length):
     * <pre>
     * "SAREC" (5 bytes), format (int, 1)
     * started (long, epoch ms), arena (text), variant (text: "" for none), centre x, y, z (doubles: the middle of
     *   the two spawn points; positions below are from it), length (int, ms), a duel (byte, 0/1: not from the
     *   queue), the winning team (byte, 0 or 1; a free-for-all round: the winner's)
     * format 3 on: flags (byte: 1 spleef, 2 a free-for-all round, where each fighter is a team of their own, 0, 1, 2...;
     *   format 4: 4 crystal PvP; format 5: 8 a part of a free-for-all session, where people come and go: their frames
     *   start when they come in and stop while they're out, and "won" is more kills than deaths)
     * fighters (byte), then each: UUID (2 longs), name (text), team (byte, 0 or 1), won (byte, 0/1),
     *   their stats in that arena as it started: Elo, wins, losses, win streak, best win streak, seconds spent
     *   fighting there (ints), bots beaten there (byte: how many levels, then each: level (text), how many (int)),
     *   ping as it started and on average (ints, ms), client (text: its brand, e.g. "vanilla", "fabric"), format 5 on:
     *   kills and deaths (ints: a free-for-all's part only, 0 otherwise)
     * then each fighter's frames, in the same order: count (int), then each frame (26 bytes in format 1, 29 or more
     *   in format 2):
     *   time since the start (int, ms), x, y, z (floats), yaw, pitch (floats),
     *   keys held (byte: 1 forward, 2 back, 4 left, 8 right, 16 jump, 32 sneak, 64 sprint),
     *   state (byte: 1 on the ground, 2 sprinting, 4 sneaking, 8 swung, 16 landed a hit, 32 was hit (by a fighter's
     *   hand); the last three: since the frame before)
     *   format 2 only: ping (short, ms), more (byte, since the frame before: 1 sent a velocity (knockback), 2 holding
     *   the attack button on a block (each tick of it swings the arm), 4 hit by a fighter's arrow, snowball or rod,
     *   8 teleported, 16 landed a hit on a fighter (their number, below)), then if 1: the velocity x, y, z (floats),
     *   then if 16: which fighter (byte: their place in the list above, from 0)
     * </pre>
     * format 3 on, after the frames: blocks changed (int), then each: time since the start (int, ms), x, y, z (ints:
     *   from the centre's block, its coordinates rounded down), by (byte: which fighter, -1 none), how (byte: 0 broken by
     *   hand, 1 a snowball landing on it, 2 placed, 3 blown up), what it became (text: block data, e.g. "minecraft:air")
     * format 4 on, after the blocks: events (int), then each: time since the start (int, ms), fighter (byte, -1 none),
     *   kind (byte), how many bytes of details follow (unsigned short), the details (a reader skips kinds it doesn't know):
     *   1 what they wear or hold changed: slot (byte: 0 main hand, 1 off hand, 2 feet, 3 legs, 4 chest, 5 head), item
     *     (text: e.g. "minecraft:end_crystal"), enchanted (byte, 0/1); every slot in their first frame
     *   2 popped a totem (no details)
     *   3 placed a crystal (fighter -1: not known): its entity id (int), x, y, z (floats, from the centre, as positions)
     *   4 hit (popped) a crystal: its entity id (int)
     *   5 a crystal gone (blown up): its entity id (int)
     *   6 their health changed: health and absorption together (float); also in their first frame
     *   7 (a free-for-all) got a kill: whom (byte: their place in the list)
     *   8 (a free-for-all) died (no details)
     * A frame a tick (20 a second) while they're fighting. Read back by {@link Recording}.
     */
    private void write(final File file) throws IOException {
        final List<Player> won = this.winners;
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(file))))) {
            out.writeBytes("SAREC");
            out.writeInt(FORMAT);
            out.writeLong(this.startedAt);
            out.writeUTF(this.fight.getArena().getArenaName());
            final SumoVariant variant = this.fight.getVariant();
            out.writeUTF(variant == null ? "" : variant.name());
            out.writeDouble(this.centre.getX());
            out.writeDouble(this.centre.getY());
            out.writeDouble(this.centre.getZ());
            out.writeInt((int) (System.currentTimeMillis() - this.startedAt));
            out.writeByte(this.fight.isDuel() ? 1 : 0);
            int winningTeam = 0;
            for (final Track t : this.tracks)
                if (this.won(t, won))
                    winningTeam = t.team;
            out.writeByte(winningTeam);
            out.writeByte((this.fight.getArena().isSpleef() ? SPLEEF : 0) | (this.fight.getMode() == FightMode.FREE_FOR_ALL || this.session ? FREE_FOR_ALL : 0)
                    | (this.fight.getArena().isCrystalPvp() ? CRYSTAL : 0) | (this.session ? SESSION : 0)
                    | (isKit(this.fight.getArena()) ? KIT : 0));
            out.writeByte(this.tracks.size());
            for (final Track t : this.tracks) {
                out.writeLong(t.player.getUniqueId().getMostSignificantBits());
                out.writeLong(t.player.getUniqueId().getLeastSignificantBits());
                out.writeUTF(t.player.getName());
                out.writeByte(t.team);
                out.writeByte(this.won(t, won) ? 1 : 0);
                for (final int stat : new int[]{t.elo, t.wins, t.losses, t.winStreak, t.bestWinStreak, t.fightSeconds})
                    out.writeInt(stat);
                final Map<String, Integer> bots = t.botsBeaten;
                out.writeByte(bots.size());
                for (final Map.Entry<String, Integer> e : bots.entrySet()) {
                    out.writeUTF(e.getKey());
                    out.writeInt(e.getValue());
                }
                out.writeInt(t.pingAtStart);
                synchronized (t) {
                    out.writeInt(t.frames == 0 ? t.pingAtStart : (int) (t.pingSum / t.frames));
                }
                out.writeUTF(t.client);
                out.writeInt(t.kills.get());
                out.writeInt(t.deaths.get());
            }
            for (final Track t : this.tracks)
                synchronized (t) {
                    out.writeInt(t.frames);
                    t.bytes.writeTo(out);
                }
            synchronized (this.blocks) {
                out.writeInt(this.blocks.size());
                for (final BlockChange b : this.blocks) {
                    out.writeInt(b.ms());
                    out.writeInt(b.x());
                    out.writeInt(b.y());
                    out.writeInt(b.z());
                    out.writeByte(b.by());
                    out.writeByte(b.how());
                    out.writeUTF(b.data());
                }
            }
            synchronized (this.events) {
                out.writeInt(this.events.size());
                for (final Event e : this.events) {
                    out.writeInt(e.ms());
                    out.writeByte(e.fighter());
                    out.writeByte(e.kind());
                    out.writeShort(e.data().length);
                    out.write(e.data());
                }
            }
        }
    }

    /** Whether they won: in a free-for-all's part, more kills than deaths. */
    private boolean won(final Track t, final List<Player> winners) {
        return this.session ? t.kills.get() > t.deaths.get() : winners != null && winners.contains(t.player);
    }

    /** Past recordingMaxMb in all, the oldest recordings go (and any day folder left empty), but never a favourited one. */
    private static void prune() throws IOException {
        final long max = (SpawnArena.settings == null ? 2048L : SpawnArena.settings.recordingMaxMb) * 1024L * 1024L;
        if (max <= 0)
            return;
        final File root = new File(SpawnArena.INSTANCE.getDataFolder(), "recordings");
        final List<File> files;
        try (Stream<java.nio.file.Path> walk = Files.walk(root.toPath())) {
            files = new ArrayList<>(walk.map(java.nio.file.Path::toFile).filter(f -> f.isFile() && f.getName().endsWith(".sarec.gz")).toList());
        }
        long total = files.stream().mapToLong(File::length).sum();
        if (total <= max)
            return;
        files.sort(Comparator.comparingLong(File::lastModified));
        final java.util.Set<String> favourites = MatchFavourites.all(); // kept: someone favourited them in /matches
        for (final File f : files) {
            if (total <= max)
                break;
            if (favourites.contains(MatchFavourites.key(f)))
                continue;
            final long size = f.length();
            if (f.delete())
                total -= size;
            final File day = f.getParentFile();
            final String[] left = day.list();
            if (left != null && left.length == 0)
                day.delete();
        }
    }
}
