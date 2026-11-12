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
 * Records sumo matches between players, tick by tick, for one day training a bot on how real players play: where each
 * fighter is and looks, the keys they're holding (forward, back, left, right, jump, sneak, sprint), on the ground or
 * not, and when they swing, land a hit or are hit. With who won, and each fighter's stats there as the match started
 * (Elo, wins, losses, streaks, time spent fighting there, bots beaten), their ping and their client, to pick out
 * matches by later. A finished match (a winner, not a draw) is saved, compressed, to
 * recordings/&lt;date&gt;/ in the plugin's folder (about 10 KB for a 30-second 1v1); past recordingMaxMb, the oldest go.
 * Bot matches aren't recorded. The format: see {@link #write}.
 */
public final class MatchRecorder implements Listener {
    private static final int FORMAT = 2;
    /** The longest it records a match for (ten minutes); after that it stops taking frames */
    private static final int MAX_FRAMES = 20 * 60 * 10;
    private static final Map<Fight, MatchRecorder> RECORDING = new ConcurrentHashMap<>();
    /** Fighter -> their match's recording (for swings and hits) */
    private static final Map<UUID, MatchRecorder> OF = new ConcurrentHashMap<>();

    private final Fight fight;
    private final long startedAt = System.currentTimeMillis();
    private final Location centre;
    private final List<Track> tracks = new ArrayList<>();
    private volatile List<Player> winners;

    /** One fighter's frames. */
    private static final class Track {
        final Player player;
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

        Track(final Player player, final int team) {
            this.player = player;
            this.team = team;
            this.pingAtStart = player.getPing();
            final String brand = player.getClientBrandName();
            this.client = brand == null ? "" : brand;
        }
    }

    private MatchRecorder(final Fight fight, final Location centre) {
        this.fight = fight;
        this.centre = centre;
    }

    /** Only listens (swings and hits); recordings are made by {@link #start}. */
    public MatchRecorder() {
        this.fight = null;
        this.centre = null;
    }

    private static boolean enabled() {
        return SpawnArena.settings == null || SpawnArena.settings.recordSumoMatches;
    }

    /** A fight has just gone live: a sumo match between players is recorded. */
    static void start(final Fight fight) {
        if (fight.getMode() == FightMode.FREE_FOR_ALL)
            return; // (rounds of more than two aren't recorded yet)
        final Arena arena = fight.getArena();
        if (!enabled() || fight.withBots() || !arena.isDeathOnTouchLiquid() || arena.isFfa())
            return;
        final Location a = arena.spawnPoint1, b = arena.spawnPoint2;
        if (a == null || b == null)
            return;
        final MatchRecorder rec = new MatchRecorder(fight, a.clone().add(b).multiply(0.5));
        final List<Player> fighters = fight.getFighters();
        if (fighters.isEmpty())
            return;
        final List<Player> firstTeam = fight.getTeamOf(fighters.get(0));
        for (final Player p : fighters)
            rec.tracks.add(new Track(p, firstTeam.contains(p) ? 0 : 1));
        RECORDING.put(fight, rec);
        for (final Track t : rec.tracks) {
            OF.put(t.player.getUniqueId(), rec);
            t.task = t.player.getScheduler().runAtFixedRate(SpawnArena.INSTANCE, task -> rec.frame(t), null, 1L, 1L);
        }
        // Their stats there as it starts, for picking out matches later (the good players', the close ones...)
        if (SpawnArena.statsManager != null)
            Threads.async(() -> {
                final StatsManager stats = SpawnArena.statsManager;
                final String name = arena.getArenaName();
                for (final Track t : rec.tracks) {
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
        for (final Track t : rec.tracks) {
            final ScheduledTask task = t.task;
            if (task != null)
                task.cancel();
            OF.remove(t.player.getUniqueId(), rec);
        }
        if (rec.winners == null || !SpawnArena.INSTANCE.isEnabled())
            return; // a draw, or the server's stopping: nothing to learn from
        Threads.async(rec::save);
    }

    /** On the fighter's thread, each tick. */
    private void frame(final Track t) {
        final Player p = t.player;
        if (!p.isOnline() || t.frames >= MAX_FRAMES || this.fight.getState() != FightState.IN_FIGHT)
            return;
        final Location at = p.getLocation();
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
                    + "_" + String.join("-", this.tracks.stream().map(t -> t.player.getName()).toList()) + ".sarec.gz";
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
     *   queue), the winning team (byte, 0 or 1)
     * fighters (byte), then each: UUID (2 longs), name (text), team (byte, 0 or 1), won (byte, 0/1),
     *   their stats in that arena as it started: Elo, wins, losses, win streak, best win streak, seconds spent
     *   fighting there (ints), bots beaten there (byte: how many levels, then each: level (text), how many (int)),
     *   ping as it started and on average (ints, ms), client (text: its brand, e.g. "vanilla", "fabric")
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
                if (won != null && won.contains(t.player))
                    winningTeam = t.team;
            out.writeByte(winningTeam);
            out.writeByte(this.tracks.size());
            for (final Track t : this.tracks) {
                out.writeLong(t.player.getUniqueId().getMostSignificantBits());
                out.writeLong(t.player.getUniqueId().getLeastSignificantBits());
                out.writeUTF(t.player.getName());
                out.writeByte(t.team);
                out.writeByte(won != null && won.contains(t.player) ? 1 : 0);
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
            }
            for (final Track t : this.tracks)
                synchronized (t) {
                    out.writeInt(t.frames);
                    t.bytes.writeTo(out);
                }
        }
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
