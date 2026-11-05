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
    /** A match it's still in after this long, it rage cheats anyway: 35 to 60 seconds, picked for each match */
    private final long rageAfterTicks = 20L * (35 + ThreadLocalRandom.current().nextInt(26));
    /** A speed hack's bhop speed (blocks a tick): a sprint-jump peaks at about 0.36. */
    private static final double BHOP_SPEED = 0.5;

    private final BotFight fight;
    private final Player target;
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
    private volatile long knockedUntil;
    /** Moving backwards until this tick (an S-tap after a hit). */
    private long sTapUntil;
    /** Where its opponent was, the last few ticks: it acts on where they were reactionTicks ago. */
    private final Deque<Vector> seen = new ArrayDeque<>();

    SumoBot(final BotFight fight, final Player target, final BotDifficulty level, final Location centre) {
        this.fight = fight;
        this.target = target;
        this.level = level;
        this.centre = centre;
    }

    public static SumoBot of(final org.bukkit.entity.Entity entity) {
        return entity == null ? null : BOTS.get(entity.getUniqueId());
    }

    public static boolean isBot(final org.bukkit.entity.Entity entity) {
        return entity instanceof Mannequin m && (BOTS.containsKey(m.getUniqueId())
                || m.getPersistentDataContainer().has(KEY, PersistentDataType.BYTE));
    }

    public Player getTarget() {
        return this.target;
    }

    public BotDifficulty getLevel() {
        return this.level;
    }

    public Mannequin getBody() {
        return this.body;
    }

    public BotFight getFight() {
        return this.fight;
    }

    /** On {@code at}'s thread: the bot comes in, facing its opponent, and starts playing. */
    void spawn(final Location at) {
        final World world = at.getWorld();
        final Location spot = at.clone();
        final Vector toTarget = this.target.getLocation().toVector().subtract(spot.toVector());
        spot.setYaw((float) Math.toDegrees(Math.atan2(-toTarget.getX(), toTarget.getZ())));
        spot.setPitch(0);
        this.yaw = spot.getYaw();
        // Someone from the server's skin (never their opponent's own), under the bot's own name
        final com.destroystokyo.paper.profile.PlayerProfile skin = BotSkins.pick(this.target.getUniqueId());
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
        this.puppet = BotPuppet.show(this.target, this, this.body, skin, this.level);
        if (this.puppet == null)
            Threads.entity(this.target, () -> {
                final Mannequin b = this.body;
                if (b != null && this.target.isOnline())
                    this.target.showEntity(SpawnArena.INSTANCE, b);
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
            final Location at = b.getLocation(), from = this.target.getLocation();
            p.hurt((float) (Math.toDegrees(Math.atan2(from.getZ() - at.getZ(), from.getX() - at.getX())) - at.getYaw()));
        }
        if (ThreadLocalRandom.current().nextDouble() < this.level.jumpResetChance)
            this.jumpResetNext = true;
        // Thrown off by the hit, as a player is: its aim jolts (less the better it is) and it takes a moment to swing
        // back, so a run of good hits can keep it in a combo
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        final double jolt = 4 + this.level.aimNoise * 3;
        this.noiseYaw += random.nextGaussian() * jolt;
        this.noisePitch += random.nextGaussian() * jolt * 0.5;
        if (!this.level.combo) // with no hit delay, it's all about clicking straight back
            this.nextSwingAt = Math.max(this.nextSwingAt, this.tick + this.level.reactionTicks + random.nextInt(3));
        this.hitNext = true;
        this.hitsTaken++;
        this.knockedUntil = this.tick + 6;
        if (this.level.rageCheats && !this.rageRolled && this.hitsTaken >= 3
                && (this.hitsTaken - this.hitsLanded >= 4 || this.nearEdge(b.getLocation()))) {
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
        Threads.entity(this.target, () -> {
            if (!this.target.isOnline())
                return;
            this.target.sendMessage(MiniMessage.miniMessage().deserialize(this.level.colour
                    + "<bold>" + this.level.label + "</bold> <gray>bot " + why + " so it's started <red><bold>rage cheating</bold><gray>!"));
            this.target.playSound(this.target, Sound.ENTITY_WITHER_SPAWN, 0.4f, 1.6f);
        });
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
        final FightState state = this.fight.getState();
        if (state == FightState.ENDING || state == FightState.OVER) {
            this.remove();
            return;
        }
        if (state != FightState.IN_FIGHT || !this.target.isOnline() || !this.fight.isFighter(this.target))
            return;

        final Location at = b.getLocation();
        // Out once it touches the water or lava (where its feet are, not just above it), however far it falls first;
        // or if it somehow falls well below the arena without touching any
        final Block feet = at.getBlock();
        if (me.zeronull.spawnarena.events.LiquidListener.inLiquid(feet) || b.isInWater() || b.isInLava()
                || at.getY() < this.centre.getY() - 30) {
            this.fight.botOut();
            return;
        }

        // What it knows of its opponent: where they were reactionTicks ago
        final Location now = this.target.getLocation();
        this.seen.addLast(now.toVector());
        while (this.seen.size() > this.level.reactionTicks + 1)
            this.seen.removeFirst();
        final Vector known = this.seen.peekFirst();

        this.aim(at, known);
        if (this.level.rageCheats && !this.rage && this.tick > this.rageAfterTicks)
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
        final double dx = target.getX() - at.getX(), dz = target.getZ() - at.getZ();
        final double dy = (target.getY() + this.level.aimHeight) - (at.getY() + EYE);
        final double flat = Math.sqrt(dx * dx + dz * dz);
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        this.noiseYaw = this.noiseYaw * 0.85 + random.nextGaussian() * this.level.aimNoise * 0.15 * 2.5;
        this.noisePitch = this.noisePitch * 0.85 + random.nextGaussian() * this.level.aimNoise * 0.1 * 2.5;
        final double wantYaw = Math.toDegrees(Math.atan2(-dx, dz)) + this.noiseYaw;
        final double wantPitch = Math.max(-60, Math.min(60, -Math.toDegrees(Math.atan2(dy, flat)) + this.noisePitch));

        double errYaw = ((wantYaw - this.yaw) % 360 + 540) % 360 - 180;
        double errPitch = wantPitch - this.pitch;
        final double deadZone = this.level.aimNoise * 0.6;
        if (Math.abs(errYaw) < deadZone)
            errYaw *= 0.2;
        if (Math.abs(errPitch) < deadZone)
            errPitch *= 0.2;
        final double gain = Math.min(0.55, 0.1 + this.level.turnSpeed / 160);
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
        if (this.level.strafe > 0 && distance < 5) {
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
     * Rage cheating, the way a hacked client's speed bhop moves: a hop the moment it lands (now and then a low one),
     * its speed set well above a sprint-jump's on each hop and only bleeding off slowly in the air (no air friction),
     * and it steers instantly in the air (strafe), circling its opponent, or away from the edge.
     */
    private void bhop(final Mannequin b, final Location at, final Vector input, final boolean ground, final ThreadLocalRandom random) {
        final Vector dir = input.clone().setY(0);
        if (dir.lengthSquared() < 1.0E-4)
            dir.copy(facing(this.yaw));
        dir.normalize();
        // Circling them as it hops (a strafe module), switching way every half second or so; never strafing off the edge
        if (this.tick >= this.rageStrafeFlipAt) {
            this.strafeDir = -this.strafeDir;
            this.rageStrafeFlipAt = this.tick + 6 + random.nextInt(14);
        }
        final Vector circling = dir.clone().add(new Vector(-dir.getZ(), 0, dir.getX()).multiply(this.strafeDir * 0.9)).normalize();
        if (this.edgeDanger(at, circling) == 0)
            dir.copy(circling);
        double y = b.getVelocity().getY();
        if (ground) {
            y = random.nextDouble() < 0.3 ? 0.31 : JUMP;
            this.bhopSpeed = Math.max(this.bhopSpeed * 1.08, BHOP_SPEED) * (0.96 + random.nextDouble() * 0.08);
            this.bhopSpeed = Math.min(this.bhopSpeed, BHOP_SPEED * 1.25);
        } else {
            this.bhopSpeed *= 0.985;
        }
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
        final double reachTo = distanceToBox(eye, this.target.getBoundingBox());
        // Swings a little before it's in reach, like anyone does, if it's looking about the right way
        final double off = this.angleOff(eye);
        final double reach = this.rage ? RAGE_REACH : this.level.reach;
        if (reachTo > reach + 0.6 || off > 35)
            return;
        // Not someone it can't hurt (creative, spectator), and not through blocks
        if (!canBeHit(this.target) || !b.hasLineOfSight(this.target))
            return;
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        this.nextSwingAt = this.tick + (this.rage ? 3 + random.nextInt(2)
                : this.level.minSwingTicks + random.nextInt(this.level.maxSwingTicks - this.level.minSwingTicks + 1));
        b.swingMainHand();
        final BotPuppet puppet = this.puppet;
        if (puppet != null)
            puppet.swing(b);
        if (reachTo > reach || off > 18 || (!this.rage && random.nextDouble() < this.level.missChance + this.strafeMiss(at)))
            return;
        final boolean sprintHit = this.sprinting;
        final float hitYaw = this.yaw;
        final Location from = at.clone();
        final int knockbackLevel = this.level.combo ? 0 : SlapFish.create().getEnchantmentLevel(org.bukkit.enchantments.Enchantment.KNOCKBACK);
        Threads.entity(this.target, () -> this.land(from, hitYaw, sprintHit, knockbackLevel));
        if (sprintHit) {
            // A sprint hit slows the hitter and ends their sprint; w-tapping gets it back sooner
            b.setVelocity(b.getVelocity().multiply(new Vector(0.6, 1, 0.6)));
            this.sprinting = false;
            final boolean tap = random.nextDouble() < this.level.wTapChance;
            this.sprintAgainAt = this.tick + (tap ? 2 + random.nextInt(3) : 15 + random.nextInt(20));
            // The tap: letting go of forward (W-tap), a step back (S-tap) or a quick crouch (sneak-tap), each ending its
            // sprint so the next hit has a sprint hit's knockback again: what keeps someone in a combo
            final double kind = random.nextDouble();
            if (tap && kind < this.level.sTapChance)
                this.sTapUntil = this.tick + 2 + random.nextInt(3);
            else if (tap && kind < this.level.sTapChance + this.level.sneakTapChance)
                this.sneakUntil = Math.max(this.sneakUntil, this.tick + 2 + random.nextInt(2));
        }
    }

    /** On its opponent's thread: the hit lands like a player's, unless they're still in their hit delay. */
    private void land(final Location from, final float hitYaw, final boolean sprintHit, final int knockbackLevel) {
        final Player p = this.target;
        if (!p.isOnline() || !this.fight.isFighter(p) || this.fight.getState() != FightState.IN_FIGHT || !canBeHit(p))
            return;
        if (!this.level.combo && p.getNoDamageTicks() > p.getMaximumNoDamageTicks() / 2)
            return;
        p.setNoDamageTicks(this.level.combo ? 0 : p.getMaximumNoDamageTicks());
        this.hitsLanded++;

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
        return Math.min(0.6, across / 0.25 * 0.5 * (1 - this.level.strafeResist));
    }

    /** Degrees between where it's looking and where on its opponent it aims. */
    private double angleOff(final Location eye) {
        final Vector look = new Vector(-Math.sin(Math.toRadians(this.yaw)) * Math.cos(Math.toRadians(this.pitch)),
                -Math.sin(Math.toRadians(this.pitch)), Math.cos(Math.toRadians(this.yaw)) * Math.cos(Math.toRadians(this.pitch)));
        final Vector to = this.target.getLocation().toVector().add(new Vector(0, this.level.aimHeight, 0)).subtract(eye.toVector());
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
