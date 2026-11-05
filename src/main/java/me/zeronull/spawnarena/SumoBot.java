package me.zeronull.spawnarena;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A sumo opponent: a Mannequin (player model, real player knockback and movement physics on the server) steered tick by
 * tick like a player would. It walks, sprints and jumps with a player's own acceleration numbers, aims with a limited
 * turn speed and some wobble, reacts a few ticks late, clicks at a human rate, w-taps, strafes, jump-resets and keeps
 * away from the edge, each as well as its {@link BotDifficulty} allows. Its hits on its opponent are a player's sprint
 * hit: the base knockback, plus the sprint and Knockback-enchantment bonuses, and the hit delay.
 * Only its opponent can see it, hit it, or be hit by it.
 */
public final class SumoBot {
    /** Marks bot bodies, so a leftover one (a crash) is recognised and removed. */
    public static final NamespacedKey KEY = new NamespacedKey(SpawnArena.INSTANCE, "sumo_bot");
    /** Every bot there is now, by its body's UUID. */
    private static final Map<UUID, SumoBot> BOTS = new ConcurrentHashMap<>();

    // A player's movement, per tick (vanilla): ground acceleration walking/sprinting, and in the air
    private static final double WALK = 0.1, SPRINT = 0.13, AIR_WALK = 0.02, AIR_SPRINT = 0.026;
    private static final double JUMP = 0.42, SPRINT_JUMP_BOOST = 0.2;
    private static final double EYE = 1.62;
    /** Chance a losing Superior Gaming Chair bot starts rage cheating, and how far it reaches then. */
    private static final double RAGE_CHANCE = 0.5, RAGE_REACH = 4.0;
    /** A match it's still in after this long, it rage cheats anyway: 12 to 25 seconds, picked for each match */
    private final long rageAfterTicks = 20L * (12 + ThreadLocalRandom.current().nextInt(14));
    /**
     * Times it's been hit near the edge (close calls), and how many it takes this match (2 to 4) before it'll rage cheat
     * at all: only once it's really been in trouble, whether it's losing or the timer's up.
     */
    private volatile int closeCalls;
    private final int closeCallsToRage = 2 + ThreadLocalRandom.current().nextInt(3);
    /** A speed hack's bhop speed (blocks a tick), steady: a sprint-jump peaks at about 0.36. */
    private static final double BHOP_SPEED = 0.42;

    /** The match it's in: a practice match against a player, or a show match against another bot */
    private final BotMatch match;
    /** Who it fights: a player, or another bot (set once both are made) */
    private volatile Foe foe;
    private final BotDifficulty level;
    private final Location centre;
    private volatile Mannequin body;
    /** How its opponent sees it (a player); null when it's shown as the Mannequin itself. */
    private volatile BotPuppet puppet;
    private volatile ScheduledTask task;

    private long tick;
    private float yaw, pitch;
    private double noiseYaw, noisePitch;
    /** How fast its aim is turning (degrees a tick): it speeds up and settles like a hand does, rather than snapping. */
    private double yawSpeed, pitchSpeed;
    /** How far from where it means to aim its aim has wandered (blocks: up, and to the side) */
    private double driftUp, driftSide;
    /**
     * Reading its opponent's strafe: which way they've been strafing (1 or -1, as it sees it: to its side vector), and
     * how well it's got it (0 to 1). Strafing the same way for a while, it learns to aim a little ahead and misses
     * less; a change of direction and it has to read them again.
     */
    private int strafeWay;
    private double strafeRead;
    private boolean sprinting = true;
    private long sprintAgainAt, nextSwingAt, strafeUntil, jumpCooldownUntil, startAt;
    private int strafeDir = 1;
    private volatile boolean jumpResetNext, hitNext;
    /** Crouching (after being knocked near the edge) until this tick. */
    private long sneakUntil;
    private boolean sneaking;
    /** In the air from a sprint-jump in, and whether it was on the ground last tick (to notice it landing). */
    private boolean jumpedIn, wasOnGround = true;
    /** Hits it took and landed this match: a Superior Gaming Chair bot that's losing may start rage cheating. */
    private volatile int hitsTaken, hitsLanded;
    private volatile boolean rageRolled, rage;
    /** Rage bhop: its speed in the air (a speed hack's, set every tick); after a hit it's knocked about for a moment first. */
    private double bhopSpeed;
    private long rageStrafeFlipAt;
    private Vector bhopDir;
    private volatile long knockedUntil;
    /** Moving backwards until this tick (an S-tap after a hit). */
    private long sTapUntil;
    /**
     * Comboing (bots that sneak-tap): it's landed hits in a row without being hit back, so it presses on in a pattern,
     * straight at them, each swing timed for when they can next be hit, alternating a sneak-tap and a W-tap so each hit
     * is a fresh sprint hit; until it's hit back, misses twice, or they're knocked out of reach.
     */
    private volatile boolean comboing;
    private volatile int hitsInARow;
    private int comboMisses, comboStep;
    /** Where its opponent was, the last few ticks: it acts on where they were reactionTicks ago. */
    private final Deque<Vector> seen = new ArrayDeque<>();

    SumoBot(final BotFight fight, final Player target, final BotDifficulty level, final Location centre) {
        this.match = fight;
        this.foe = new PlayerFoe(target);
        this.level = level;
        this.centre = centre;
    }

    /** For a show match: its foe, another bot, is given once both are made ({@link #fights}). */
    SumoBot(final BotMatch match, final BotDifficulty level, final Location centre) {
        this.match = match;
        this.level = level;
        this.centre = centre;
    }

    /** A show match: these two fight each other; {@code otherSpawn} is where the other comes in (until it's there). */
    static void fights(final SumoBot a, final SumoBot b, final Location aSpawn, final Location bSpawn) {
        a.foe = new BotFoe(b, bSpawn);
        b.foe = new BotFoe(a, aSpawn);
    }

    // ── Who it fights ───────────────────────────────────────────────────────

    /** The match a bot is in: what it needs to know of it, and tells it. */
    interface BotMatch {
        FightState state();

        /** Its foe is still in it (online and fighting, or not out) */
        boolean inPlay();

        /** This bot is out (in the water or lava) */
        void out(SumoBot bot);

        Arena arena();

        /** The real fight, for a practice match (null for a show) */
        Fight fight();

        /** Something everyone it concerns hears (a rage message) */
        void tell(net.kyori.adventure.text.Component message);
    }

    /** Who a bot fights. */
    private interface Foe {
        Location location();

        org.bukkit.util.BoundingBox box();

        /** The entity, for line of sight and its thread */
        org.bukkit.entity.LivingEntity entity();

        /** Can be hurt now (not creative, spectator, out...) */
        boolean hittable();

        int maxNoDamageTicks();

        /** Whose skin it mustn't borrow */
        java.util.UUID skinOf();

        /** On the foe's thread: {@code by}'s hit lands, as a player's hit would. */
        void hit(SumoBot by, Location from, float yaw, boolean sprint, int knockbackLevel);
    }

    private final class PlayerFoe implements Foe {
        final Player player;

        PlayerFoe(final Player player) {
            this.player = player;
        }

        public Location location() { return this.player.getLocation(); }
        public org.bukkit.util.BoundingBox box() { return this.player.getBoundingBox(); }
        public org.bukkit.entity.LivingEntity entity() { return this.player; }
        public boolean hittable() { return canBeHit(this.player); }
        public int maxNoDamageTicks() { return this.player.getMaximumNoDamageTicks(); }
        public java.util.UUID skinOf() { return this.player.getUniqueId(); }

        public void hit(final SumoBot by, final Location from, final float yaw, final boolean sprint, final int knockbackLevel) {
            by.land(this.player, from, yaw, sprint, knockbackLevel);
        }
    }

    private static final class BotFoe implements Foe {
        final SumoBot bot;
        final Location spawn;

        BotFoe(final SumoBot bot, final Location spawn) {
            this.bot = bot;
            this.spawn = spawn;
        }

        public Location location() {
            final Mannequin b = this.bot.body;
            return b == null ? this.spawn.clone() : b.getLocation();
        }

        public org.bukkit.util.BoundingBox box() {
            final Mannequin b = this.bot.body;
            return b == null ? org.bukkit.util.BoundingBox.of(this.spawn, 0.3, 0.9, 0.3).shift(0, 0.9, 0) : b.getBoundingBox();
        }

        public org.bukkit.entity.LivingEntity entity() { return this.bot.body; }
        public boolean hittable() { final Mannequin b = this.bot.body; return b != null && b.isValid(); }
        public int maxNoDamageTicks() { final Mannequin b = this.bot.body; return b == null ? 20 : b.getMaximumNoDamageTicks(); }
        public java.util.UUID skinOf() { return null; }

        public void hit(final SumoBot by, final Location from, final float yaw, final boolean sprint, final int knockbackLevel) {
            this.bot.takeHit(by, from, yaw, sprint, knockbackLevel);
        }
    }

    public static SumoBot of(final org.bukkit.entity.Entity entity) {
        return entity == null ? null : BOTS.get(entity.getUniqueId());
    }

    public static boolean isBot(final org.bukkit.entity.Entity entity) {
        return entity instanceof Mannequin m && (BOTS.containsKey(m.getUniqueId())
                || m.getPersistentDataContainer().has(KEY, PersistentDataType.BYTE));
    }

    /** The player it fights (null in a show match, where it fights another bot). */
    public Player getTarget() {
        return this.foe instanceof PlayerFoe p ? p.player : null;
    }

    public BotDifficulty getLevel() {
        return this.level;
    }

    public Mannequin getBody() {
        return this.body;
    }

    /** Its practice match (null in a show match). */
    public BotFight getFight() {
        return this.match instanceof BotFight f ? f : null;
    }

    BotMatch getMatch() {
        return this.match;
    }

    /** On {@code at}'s thread: the bot comes in, facing its opponent, and starts playing. */
    void spawn(final Location at) {
        final World world = at.getWorld();
        final Location spot = at.clone();
        final Vector toTarget = this.foe.location().toVector().subtract(spot.toVector());
        spot.setYaw((float) Math.toDegrees(Math.atan2(-toTarget.getX(), toTarget.getZ())));
        spot.setPitch(0);
        this.yaw = spot.getYaw();
        // Someone from the server's skin (never their opponent's own), under the bot's own name
        final com.destroystokyo.paper.profile.PlayerProfile skin = BotSkins.pick(this.foe.skinOf());
        this.body = world.spawn(spot, Mannequin.class, m -> {
            m.setPersistent(false);
            m.setVisibleByDefault(false);
            m.setRemoveWhenFarAway(false);
            m.setCollidable(false);
            m.setSilent(false);
            m.setImmovable(false);
            m.customName(MiniMessage.miniMessage().deserialize(this.level.colour + "Bot <gray>(" + this.level.label + ")"));
            m.setCustomNameVisible(true);
            m.setDescription(null);
            m.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
            final org.bukkit.attribute.AttributeInstance health = m.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            if (health != null)
                health.setBaseValue(1024);
            m.setHealth(1024);
            m.getEquipment().setItemInMainHand(this.level.combo ? null : SlapFish.create());
            if (skin != null)
                m.setProfile(io.papermc.paper.datacomponent.item.ResolvableProfile.resolvableProfile(skin));
        });
        BOTS.put(this.body.getUniqueId(), this);
        // Drawn as a player (a Mannequin's arm never swings on the client); as the Mannequin itself if that can't be done
        final Player target = this.getTarget();
        this.puppet = BotPuppet.show(target, this, this.body, skin, this.level);
        if (this.puppet == null && target != null)
            Threads.entity(target, () -> {
                final Mannequin b = this.body;
                if (b != null && target.isOnline())
                    target.showEntity(SpawnArena.INSTANCE, b);
            });
        // A moment to react to the start, like anyone
        this.startAt = this.level.reactionTicks + 4;
        this.task = this.body.getScheduler().runAtFixedRate(SpawnArena.INSTANCE, t -> this.tick(), null, 1L, 1L);
    }

    /** Gone (the fight's over): from any thread. */
    void remove() {
        final ScheduledTask t = this.task;
        if (t != null)
            t.cancel();
        final Mannequin b = this.body;
        this.body = null;
        final BotPuppet p = this.puppet;
        this.puppet = null;
        if (p != null)
            p.remove();
        if (b == null)
            return;
        BOTS.remove(b.getUniqueId());
        // The plugin is stopping: nothing can be scheduled any more, so it goes now (it isn't saved anyway)
        if (!SpawnArena.INSTANCE.isEnabled()) {
            try {
                b.remove();
            } catch (final RuntimeException ignored) {
                // Folia: not its thread
            }
            return;
        }
        b.getScheduler().run(SpawnArena.INSTANCE, x -> b.remove(), null);
    }

    /** Every bot goes (the plugin is stopping). */
    public static void removeAll() {
        for (final SumoBot bot : BOTS.values())
            try {
                final BotPuppet p = bot.puppet;
                if (p != null)
                    p.remove();
                final Mannequin b = bot.body;
                if (b != null)
                    b.remove();
            } catch (final RuntimeException ignored) {
                // Folia: not its thread at shutdown; it isn't saved anyway (not persistent)
            }
        BOTS.clear();
    }

    // ── Being hit ───────────────────────────────────────────────────────────

    /** Its opponent just hit it (the knockback is already on it): it may jump-reset, and it's back to full health. */
    void hurt() {
        final Mannequin b = this.body;
        if (b == null)
            return;
        b.setHealth(b.getMaxHealth());
        final BotPuppet p = this.puppet;
        if (p != null) {
            final Location at = b.getLocation(), from = this.foe.location();
            p.hurt((float) (Math.toDegrees(Math.atan2(from.getZ() - at.getZ(), from.getX() - at.getX())) - at.getYaw()));
        }
        if (ThreadLocalRandom.current().nextDouble() < this.level.jumpResetChance)
            this.jumpResetNext = true;
        // Thrown off by the hit, as a player is: its aim jolts (less the better it is) and it takes a moment to swing
        // back, so a run of good hits can keep it in a combo
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        // (with no hit delay hits come all the time, so each one throws it off much less, or it'd never aim again)
        final double jolt = (4 + this.level.aimNoise * 3) * (this.level.combo ? 0.25 : 1);
        this.noiseYaw += random.nextGaussian() * jolt;
        this.noisePitch += random.nextGaussian() * jolt * 0.5;
        if (!this.level.combo) // with no hit delay, it's all about clicking straight back
            this.nextSwingAt = Math.max(this.nextSwingAt, this.tick + this.level.reactionTicks + random.nextInt(3));
        this.hitNext = true;
        this.hitsTaken++;
        final boolean closeCall = this.nearEdge(b.getLocation());
        if (closeCall)
            this.closeCalls++;
        this.hitsInARow = 0;
        this.comboing = false;
        this.knockedUntil = this.tick + 6;
        if (this.level.rageCheats && !this.rageRolled && this.closeCalls >= this.closeCallsToRage
                && (this.hitsTaken - this.hitsLanded >= 4 || closeCall)) {
            this.rageRolled = true;
            if (random.nextDouble() < RAGE_CHANCE)
                this.startRage("is losing...");
        }
        if (this.level.combo)
            Threads.entityLater(b, () -> b.setNoDamageTicks(0), 1L);
    }

    /**
     * It's losing (or the match has gone on too long), so it stops hiding it: bhops at them, reaches further and clicks
     * faster for the rest of the match.
     */
    private void startRage(final String why) {
        this.rage = true;
        this.match.tell(MiniMessage.miniMessage().deserialize(this.level.colour
                + "<bold>" + this.level.label + "</bold> <gray>bot " + why + " so it's started <red><bold>rage cheating</bold><gray>!"));
    }

    // ── Thinking ────────────────────────────────────────────────────────────

    private void tick() {
        final Mannequin b = this.body;
        if (b == null || !b.isValid())
            return;
        this.tick++;
        final BotPuppet puppet = this.puppet;
        if (puppet != null)
            puppet.update(b);
        final FightState state = this.match.state();
        if (state == FightState.ENDING || state == FightState.OVER) {
            this.remove();
            return;
        }
        if (state != FightState.IN_FIGHT || this.foe == null || !this.match.inPlay())
            return;

        final Location at = b.getLocation();
        // Out exactly when a player would be: once the block at its feet is water or lava (not just touching some at its
        // side, or above it), however far it falls first; or if it somehow falls well below the arena without any
        if (me.zeronull.spawnarena.events.LiquidListener.inLiquid(at.getBlock()) || at.getY() < this.centre.getY() - 30) {
            this.match.out(this);
            return;
        }

        // What it knows of its opponent: where they were reactionTicks ago
        final Location now = this.foe.location();
        this.seen.addLast(now.toVector());
        while (this.seen.size() > this.level.reactionTicks + 1)
            this.seen.removeFirst();
        this.readStrafe(at);
        final Vector known = this.seen.peekFirst();

        this.aim(at, known);
        if (this.level.rageCheats && !this.rage && this.tick > this.rageAfterTicks && this.closeCalls >= this.closeCallsToRage)
            this.startRage("has had enough of this...");
        if (this.tick < this.startAt) {
            b.setRotation(this.yaw, this.pitch);
            return;
        }
        this.move(b, at, known);
        b.setRotation(this.yaw, this.pitch);
        b.setBodyYaw(this.yaw);
        this.maybeSwing(b, at);
    }

    /**
     * Turns towards where it saw its opponent, like a hand on a mouse rather than an aimbot: the turn speeds up and
     * settles (overshooting a little on a fast flick), small errors are left alone until they grow, and the aim wanders.
     * Better bots react harder and leave smaller errors.
     */
    private void aim(final Location at, final Vector target) {
        // Where on them it's aiming wanders slowly about their body (up and down, side to side), as a hand's does
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        final double roam = 0.15 + 0.05 * this.level.aimNoise;
        this.driftUp = Math.max(-roam, Math.min(roam, this.driftUp * 0.97 + random.nextGaussian() * 0.03));
        this.driftSide = Math.max(-roam, Math.min(roam, this.driftSide * 0.97 + random.nextGaussian() * 0.03));
        double dx = target.getX() - at.getX(), dz = target.getZ() - at.getZ();
        final double sideLength = Math.sqrt(dx * dx + dz * dz);
        if (sideLength > 1.0E-4) {
            final double sideX = -dz / sideLength, sideZ = dx / sideLength; // sideways to it
            // (and, once it's read their strafe, a little ahead of them that way)
            final double ahead = this.driftSide + this.strafeWay * this.strafeRead * 0.5;
            dx += sideX * ahead;
            dz += sideZ * ahead;
        }
        final double dy = (target.getY() + this.level.aimHeight + this.driftUp) - (at.getY() + EYE);
        final double flat = Math.sqrt(dx * dx + dz * dz);
        this.noiseYaw = this.noiseYaw * 0.85 + random.nextGaussian() * this.level.aimNoise * 0.15 * 2.5;
        this.noisePitch = this.noisePitch * 0.85 + random.nextGaussian() * this.level.aimNoise * 0.1 * 2.5;
        final double wantYaw = Math.toDegrees(Math.atan2(-dx, dz)) + this.noiseYaw;
        final double wantPitch = Math.max(-60, Math.min(60, -Math.toDegrees(Math.atan2(dy, flat)) + this.noisePitch));

        double errYaw = ((wantYaw - this.yaw) % 360 + 540) % 360 - 180;
        double errPitch = wantPitch - this.pitch;
        final double deadZone = Math.max(0.6, this.level.aimNoise * 1.0); // small errors are left be, as a person's are
        if (Math.abs(errYaw) < deadZone)
            errYaw *= 0.2;
        if (Math.abs(errPitch) < deadZone)
            errPitch *= 0.2;
        final double gain = Math.min(0.45, 0.08 + this.level.turnSpeed / 180);
        this.yawSpeed = clamp(this.yawSpeed * 0.6 + errYaw * gain, this.level.turnSpeed);
        this.pitchSpeed = clamp(this.pitchSpeed * 0.6 + errPitch * gain, this.level.turnSpeed * 0.6);
        this.yaw = (float) (this.yaw + this.yawSpeed);
        this.pitch = (float) Math.max(-90, Math.min(90, this.pitch + this.pitchSpeed));
    }

    private static double clamp(final double value, final double limit) {
        return Math.max(-limit, Math.min(limit, value));
    }

    /** Walks/sprints/strafes and jumps, with a player's acceleration, staying away from the edge. */
    private void move(final Mannequin b, final Location at, final Vector known) {
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        final Vector here = at.toVector();
        final Vector toTarget = known.clone().subtract(here).setY(0);
        final double distance = toTarget.length();
        final Vector forward = distance < 1.0E-4 ? facing(this.yaw) : toTarget.clone().multiply(1 / distance);
        final Vector side = new Vector(-forward.getZ(), 0, forward.getX());

        // Like a player: keeps pushing in, sprinting (each hit's knockback makes the gap), backing off only when right
        // on top of them; strafing when close
        double ahead = distance < 1.3 ? -0.6 : 1;
        if (this.tick < this.sTapUntil)
            ahead = -1; // S-tap: a quick step back
        double across = 0;
        if (this.comboing && distance > this.level.reach + 1.5)
            this.comboing = false; // knocked out of reach: the combo's dropped
        if (this.level.strafe > 0 && distance < 5 && !this.comboing) {
            if (this.tick >= this.strafeUntil) {
                this.strafeDir = random.nextBoolean() ? 1 : -1;
                this.strafeUntil = this.tick + 8 + random.nextInt(25);
            }
            across = this.strafeDir * this.level.strafe;
        }
        Vector input = forward.clone().multiply(ahead).add(side.clone().multiply(across));

        // The edge: if where it's heading (or its own spot) has no floor, it steers back towards the middle
        final Vector moving = b.getVelocity().clone().setY(0);
        final double danger = Math.max(this.edgeDanger(at, input), this.edgeDanger(at, moving.lengthSquared() > 1.0E-4 ? moving.clone().normalize() : input));
        if (danger > 0 && random.nextDouble() < this.level.edgeCaution) {
            final Vector home = this.centre.toVector().subtract(here).setY(0);
            if (home.lengthSquared() > 1.0E-4) {
                home.normalize();
                input = input.multiply(1 - danger).add(home.multiply(danger * 1.5));
            }
        }
        if (input.lengthSquared() > 1)
            input.normalize();

        // Knocked near the edge: it may crouch for a moment, like players do (slower, and it can't walk off)
        final boolean hit = this.hitNext;
        this.hitNext = false;
        if (hit && this.level.sneakChance > 0 && this.nearEdge(at) && random.nextDouble() < this.level.sneakChance)
            this.sneakUntil = this.tick + 6 + random.nextInt(8);
        // A sprint-jump in, then a crouch on landing: it stops dead in reach instead of sliding on, still hitting
        final boolean onGround = b.isOnGround();
        if (onGround && !this.wasOnGround && this.jumpedIn) {
            this.jumpedIn = false;
            if (random.nextDouble() < this.level.sneakChance)
                this.sneakUntil = this.tick + 4 + random.nextInt(4);
        }
        this.wasOnGround = onGround;
        final boolean sneak = this.tick < this.sneakUntil;
        if (sneak != this.sneaking) {
            this.sneaking = sneak;
            b.setPose(sneak ? org.bukkit.entity.Pose.SNEAKING : org.bukkit.entity.Pose.STANDING, sneak);
        }
        if (sneak && !floorUnder(at.clone().add(input.clone().multiply(0.6))))
            input = new Vector(); // sneaking stops at the edge

        // Sprint: on unless it let go after a hit (w-tap) and hasn't started again yet (never while crouching)
        if (!this.sprinting && this.tick >= this.sprintAgainAt)
            this.sprinting = true;
        final boolean sprint = this.sprinting && ahead > 0.5 && !sneak;
        final boolean ground = b.isOnGround();
        if (this.rage && !sneak && this.tick >= this.knockedUntil) {
            this.bhop(b, at, input, ground, random);
            return;
        }
        this.bhopSpeed = 0;
        this.bhopDir = null;
        final double accel = (ground ? (sprint ? SPRINT : WALK) : (sprint ? AIR_SPRINT : AIR_WALK)) * (sneak ? 0.3 : 1);
        final Vector velocity = b.getVelocity().add(input.multiply(accel));

        // Jumping: a jump-reset just after it's hit, or a sprint-jump in to close the gap
        final boolean jumpReset = this.jumpResetNext;
        this.jumpResetNext = false;
        final boolean wantJump = !sneak && (jumpReset
                || (sprint && distance > 3 && distance < 6 && random.nextDouble() < this.level.sprintJumpChance * 0.15));
        if (wantJump && ground && this.tick >= this.jumpCooldownUntil) {
            velocity.setY(JUMP);
            if (sprint || jumpReset)
                velocity.add(facing(this.yaw).multiply(SPRINT_JUMP_BOOST));
            this.jumpCooldownUntil = this.tick + 10;
            this.jumpedIn = !jumpReset && this.level.sprintJumpChance >= 0.3; // Hard and up crouch on landing
        }
        b.setVelocity(velocity);
    }

    /**
     * Rage cheating, the way a hacked client's speed bhop moves: a hop the moment it lands, every one the same, at one
     * steady speed above a sprint-jump's that never bleeds off in the air (no air friction), steering smoothly in the
     * air wherever it likes (strafe): circling its opponent, or away from the edge.
     */
    private void bhop(final Mannequin b, final Location at, final Vector input, final boolean ground, final ThreadLocalRandom random) {
        final Vector dir = input.clone().setY(0);
        if (dir.lengthSquared() < 1.0E-4)
            dir.copy(facing(this.yaw));
        dir.normalize();
        // Circling them as it hops (a strafe module), switching way on a steady beat (every hop and a half); never
        // strafing off the edge
        if (this.tick >= this.rageStrafeFlipAt) {
            this.strafeDir = -this.strafeDir;
            this.rageStrafeFlipAt = this.tick + 16;
        }
        final Vector circling = dir.clone().add(new Vector(-dir.getZ(), 0, dir.getX()).multiply(this.strafeDir * 0.8)).normalize();
        if (this.edgeDanger(at, circling) == 0)
            dir.copy(circling);
        // In full control: it turns smoothly towards where it wants to go (no snapping), at one steady speed, hop after
        // hop the same height, the moment it lands
        final Vector was = this.bhopDir;
        if (was != null)
            dir.copy(was.clone().multiply(0.6).add(dir.multiply(0.4)).normalize());
        this.bhopDir = dir.clone();
        double y = b.getVelocity().getY();
        if (ground)
            y = JUMP;
        this.bhopSpeed = BHOP_SPEED;
        b.setVelocity(dir.multiply(this.bhopSpeed).setY(y));
    }

    /** No floor within a couple of blocks in some direction. */
    private boolean nearEdge(final Location at) {
        for (int i = 0; i < 8; i++) {
            final double a = Math.PI / 4 * i;
            if (!floorUnder(at.clone().add(Math.cos(a) * 1.8, 0, Math.sin(a) * 1.8)))
                return true;
        }
        return false;
    }

    /** 0 (floor everywhere ahead) to 1 (none right in front), looking along {@code dir}. */
    private double edgeDanger(final Location at, final Vector dir) {
        if (dir.lengthSquared() < 1.0E-4)
            return 0;
        final Vector d = dir.clone().setY(0).normalize();
        final double[] steps = {0.7, 1.4, 2.2};
        for (int i = 0; i < steps.length; i++) {
            final Location probe = at.clone().add(d.clone().multiply(steps[i]));
            if (!floorUnder(probe))
                return 1.0 - i * 0.3;
        }
        return 0;
    }

    /** Solid ground within 3 blocks under this spot (not water). */
    private static boolean floorUnder(final Location spot) {
        final Block start = spot.getBlock();
        for (int down = 0; down <= 3; down++) {
            final Block b = start.getRelative(0, -down - 1, 0);
            if (b.isLiquid())
                return false;
            if (b.getType().isSolid())
                return true;
        }
        return false;
    }

    // ── Hitting ─────────────────────────────────────────────────────────────

    private void maybeSwing(final Mannequin b, final Location at) {
        if (this.tick < this.nextSwingAt)
            return;
        final Location eye = at.clone().add(0, EYE, 0);
        final Foe foe = this.foe;
        final double reachTo = distanceToBox(eye, foe.box());
        // Swings a little before it's in reach, like anyone does, if it's looking about the right way
        final double off = this.angleOff(eye);
        final double reach = this.rage ? RAGE_REACH : this.level.reach;
        if (reachTo > reach + 0.6 || off > 35)
            return;
        // Not someone it can't hurt (creative, spectator), and not through blocks
        if (!foe.hittable() || foe.entity() == null || !b.hasLineOfSight(foe.entity()))
            return;
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        // Comboing: the swing waits until they can be hit again (half their hit delay), so none is wasted
        this.nextSwingAt = this.tick + (this.rage ? 3 + random.nextInt(2)
                : this.comboing ? foe.maxNoDamageTicks() / 2 + random.nextInt(2)
                : this.level.minSwingTicks + random.nextInt(this.level.maxSwingTicks - this.level.minSwingTicks + 1));
        b.swingMainHand();
        final BotPuppet puppet = this.puppet;
        if (puppet != null)
            puppet.swing(b);
        if (reachTo > reach || off > 18 || (!this.rage && random.nextDouble() < this.level.missChance + this.strafeMiss(at))) {
            if (this.comboing && ++this.comboMisses >= 2)
                this.comboing = false;
            return;
        }
        final boolean sprintHit = this.sprinting;
        final float hitYaw = this.yaw;
        final Location from = at.clone();
        final int knockbackLevel = this.level.combo ? 0 : SlapFish.create().getEnchantmentLevel(org.bukkit.enchantments.Enchantment.KNOCKBACK);
        Threads.entity(foe.entity(), () -> foe.hit(this, from, hitYaw, sprintHit, knockbackLevel));
        if (sprintHit) {
            // A sprint hit slows the hitter and ends their sprint; w-tapping gets it back sooner
            b.setVelocity(b.getVelocity().multiply(new Vector(0.6, 1, 0.6)));
            this.sprinting = false;
            final boolean tap = this.comboing || random.nextDouble() < this.level.wTapChance;
            this.sprintAgainAt = this.tick + (tap ? 2 + random.nextInt(3) : 15 + random.nextInt(20));
            // The tap: letting go of forward (W-tap), a step back (S-tap) or a quick crouch (sneak-tap), each ending its
            // sprint so the next hit has a sprint hit's knockback again: what keeps someone in a combo
            final double kind = random.nextDouble();
            if (this.comboing) {
                // The pattern: a sneak-tap, then a W-tap, then a sneak-tap...
                if (this.comboStep++ % 2 == 0)
                    this.sneakUntil = Math.max(this.sneakUntil, this.tick + 4 + random.nextInt(3));
            } else if (tap && kind < this.level.sTapChance)
                this.sTapUntil = this.tick + 2 + random.nextInt(3);
            else if (tap && kind < this.level.sTapChance + this.level.sneakTapChance)
                this.sneakUntil = Math.max(this.sneakUntil, this.tick + 4 + random.nextInt(3));
        }
    }

    /** It landed a hit: a run of them starts a combo (bots that sneak-tap). */
    private void landed() {
        this.hitsLanded++;
        if (++this.hitsInARow >= 2 && this.level.sneakTapChance > 0 && !this.comboing) {
            this.comboing = true;
            this.comboMisses = 0;
        }
    }

    /** On its opponent's thread: the hit lands like a player's, unless they're still in their hit delay. */
    private void land(final Player p, final Location from, final float hitYaw, final boolean sprintHit, final int knockbackLevel) {
        if (!p.isOnline() || !this.match.inPlay() || this.match.state() != FightState.IN_FIGHT || !canBeHit(p))
            return;
        if (!this.level.combo && p.getNoDamageTicks() > p.getMaximumNoDamageTicks() / 2)
            return;
        p.setNoDamageTicks(this.level.combo ? 0 : p.getMaximumNoDamageTicks());
        this.landed();

        // The game's own knockback (LivingEntity.knockback), as a player's hit gives it: 0.4 away from the hitter, then
        // (sprinting, or a Knockback enchantment) half a block per level in the direction they're facing
        final Location pos = p.getLocation();
        final Vector before = p.getVelocity();
        p.knockback(0.4, from.getX() - pos.getX(), from.getZ() - pos.getZ());
        final int strength = knockbackLevel + (sprintHit ? 1 : 0);
        final double rad = Math.toRadians(hitYaw);
        if (strength > 0)
            p.knockback(strength * 0.5, Math.sin(rad), -Math.cos(rad));
        final Vector v = p.getVelocity();
        if (this.level.combo)
            v.setX(v.getX() / 1.2000000476837158).setZ(v.getZ() / 1.2000000476837158); // combo sumo's softer knockback
        if (!v.equals(before))
            p.setVelocity(v); // sent to their game, as a hit's knockback is
        p.sendHurtAnimation((float) (Math.toDegrees(Math.atan2(from.getZ() - pos.getZ(), from.getX() - pos.getX())) - p.getYaw()));
        p.getWorld().playSound(p, Sound.ENTITY_PLAYER_HURT, 1f, 1f);
    }

    /**
     * On this bot's thread, in a show match: {@code by} (the other bot) hits it, as a player's hit would: the same hit
     * delay, and the same knockback (worked out as the game does, then less for a bot that takes less), then its own
     * reaction to being hit.
     */
    private void takeHit(final SumoBot by, final Location from, final float hitYaw, final boolean sprintHit, final int knockbackLevel) {
        final Mannequin b = this.body;
        if (b == null || !b.isValid() || this.match.state() != FightState.IN_FIGHT)
            return;
        if (!by.level.combo && b.getNoDamageTicks() > b.getMaximumNoDamageTicks() / 2)
            return;
        b.setNoDamageTicks(by.level.combo ? 0 : b.getMaximumNoDamageTicks());
        by.landed();
        final Location pos = b.getLocation();
        Vector v = knockback(b.getVelocity(), b.isOnGround(), 0.4, from.getX() - pos.getX(), from.getZ() - pos.getZ());
        final int strength = knockbackLevel + (sprintHit ? 1 : 0);
        final double rad = Math.toRadians(hitYaw);
        if (strength > 0)
            v = knockback(v, b.isOnGround(), strength * 0.5, Math.sin(rad), -Math.cos(rad));
        double scale = this.level.knockbackTaken;
        if (by.level.combo)
            scale /= 1.2000000476837158; // combo sumo's softer knockback
        b.setVelocity(v.setX(v.getX() * scale).setZ(v.getZ() * scale));
        b.getWorld().playSound(pos, Sound.ENTITY_PLAYER_HURT, 1f, 1f);
        this.hurt();
    }

    /** LivingEntity.knockback: half the old velocity, pushed away from (x, z) by strength, a hop up if on the ground. */
    private static Vector knockback(final Vector old, final boolean onGround, final double strength, final double x, final double z) {
        double dx = x, dz = z;
        while (dx * dx + dz * dz < 1.0E-5) {
            dx = (Math.random() - Math.random()) * 0.01;
            dz = (Math.random() - Math.random()) * 0.01;
        }
        final Vector push = new Vector(dx, 0, dz).normalize().multiply(strength);
        return new Vector(old.getX() / 2 - push.getX(), onGround ? Math.min(0.4, old.getY() / 2 + strength) : old.getY(), old.getZ() / 2 - push.getZ());
    }

    private static boolean canBeHit(final Player p) {
        return p.getGameMode() != org.bukkit.GameMode.CREATIVE && p.getGameMode() != org.bukkit.GameMode.SPECTATOR && !p.isInvulnerable();
    }

    /**
     * The extra chance a swing misses because its opponent is strafing: how fast they move across its view (sideways
     * to it), as a human's aim lags behind that; better bots keep up better, but never fully.
     */
    private double strafeMiss(final Location at) {
        if (this.seen.size() < 3)
            return 0;
        final Vector now = this.seen.peekLast(), before = this.seen.stream().skip(this.seen.size() - 3).findFirst().orElse(now);
        final Vector moved = now.clone().subtract(before).setY(0).multiply(0.5); // blocks a tick, over the last 2 ticks
        final Vector to = now.clone().subtract(at.toVector()).setY(0);
        if (to.lengthSquared() < 1.0E-4)
            return 0;
        to.normalize();
        final double across = Math.abs(moved.getX() * to.getZ() - moved.getZ() * to.getX()); // sideways speed
        return Math.min(0.6, across / 0.25 * 0.5 * (1 - this.level.strafeResist)) * (1 - 0.6 * this.strafeRead);
    }

    /** Each tick: which way they're strafing, and whether it's the same way as before (see strafeRead). */
    private void readStrafe(final Location at) {
        if (this.seen.size() < 3)
            return;
        final Vector now = this.seen.peekLast(), before = this.seen.stream().skip(this.seen.size() - 3).findFirst().orElse(now);
        final Vector moved = now.clone().subtract(before).setY(0).multiply(0.5);
        final Vector to = now.clone().subtract(at.toVector()).setY(0);
        if (to.lengthSquared() < 1.0E-4)
            return;
        to.normalize();
        final double sideways = -(moved.getX() * to.getZ() - moved.getZ() * to.getX()); // along its side vector
        if (Math.abs(sideways) < 0.05) {
            this.strafeRead *= 0.97; // not strafing: it slowly forgets
            return;
        }
        final int way = sideways > 0 ? 1 : -1;
        if (way != this.strafeWay) {
            this.strafeWay = way;
            this.strafeRead = 0;
        }
        // About 3 seconds of the same strafe to read it fully for Easy, about 1.2 for the best
        this.strafeRead = Math.min(1, this.strafeRead + (1 + 1.5 * this.level.strafeResist) / 60);
    }

    /** Degrees between where it's looking and where on its opponent it aims. */
    private double angleOff(final Location eye) {
        final Vector look = new Vector(-Math.sin(Math.toRadians(this.yaw)) * Math.cos(Math.toRadians(this.pitch)),
                -Math.sin(Math.toRadians(this.pitch)), Math.cos(Math.toRadians(this.yaw)) * Math.cos(Math.toRadians(this.pitch)));
        final Vector to = this.foe.location().toVector().add(new Vector(0, this.level.aimHeight, 0)).subtract(eye.toVector());
        if (to.lengthSquared() < 1.0E-6)
            return 0;
        return Math.toDegrees(look.angle(to));
    }

    private static double distanceToBox(final Location eye, final org.bukkit.util.BoundingBox box) {
        final double x = Math.max(box.getMinX(), Math.min(eye.getX(), box.getMaxX()));
        final double y = Math.max(box.getMinY(), Math.min(eye.getY(), box.getMaxY()));
        final double z = Math.max(box.getMinZ(), Math.min(eye.getZ(), box.getMaxZ()));
        return eye.toVector().distance(new Vector(x, y, z));
    }

    private static Vector facing(final float yaw) {
        final double rad = Math.toRadians(yaw);
        return new Vector(-Math.sin(rad), 0, Math.cos(rad));
    }

    /** Bodies left over from before (a crash): they're removed whenever they turn up. */
    public static boolean isLeftover(final org.bukkit.entity.Entity entity) {
        return entity instanceof Mannequin m && m.getPersistentDataContainer().has(KEY, PersistentDataType.BYTE)
                && !BOTS.containsKey(m.getUniqueId());
    }

}
