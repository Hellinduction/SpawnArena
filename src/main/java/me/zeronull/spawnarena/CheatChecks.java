package me.zeronull.spawnarena;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Looks back over a player's recorded sumo matches (see {@link MatchRecorder}) for signs of cheating, comparing them with
 * everyone else's: reach (allowing for both players' ping), knockback taken against what the server sent, whether their
 * view turns in a mouse's fixed steps, sprinting backwards, and speed. Their aim at each swing, clicking and jump resets
 * are shown against other players too, but never count against them by themselves (good players stand out there).
 *
 * <p>A sumo bot (something playing for them) shows as matches with several of: nearly every swing landing, W-tapping and
 * switching strafe far more than anyone, jump resets, and hits landed exactly as the other player can be hit again (see
 * {@link #botSigns}). Found from real recordings of one: no real player's match here shows four of these.
 *
 * <p>Random sumo is allowed for: Spear lunges (no reach or speed), Archer, Snowball Fight and Rod (an item use swings
 * the arm: no clicking or aim; their hits aren't with a hand), Ice Rink (sliding: speed against other Ice Rink matches,
 * and knockback is only measured while they're in the air, where ice changes nothing), Moon Gravity (the same in the
 * air sideways). Bedrock players (Geyser) turn without a mouse's steps and their keys come through differently: no
 * aim-steps or sprinting checks for them.
 *
 * <p>Plain Java (no server needed), so it can run off the main thread over thousands of recordings.
 */
public final class CheatChecks {
    private CheatChecks() {
    }

    // How far the server allows for each player's ping: their positions are looked at over this long before the hit
    private static double lagWindowMs(final int ping) {
        return Math.min(ping, 1000) * 1.5 + 250;
    }

    /** Reach at or past these (blocks, from the eyes to the nearest part of them) is long / very long. */
    static final double LONG_REACH = 3.1, VERY_LONG_REACH = 3.4;
    /** A match's view turns fitting a mouse's steps less than this is off. */
    static final double ROTATION_OFF = 0.8;
    /** Knockback taken, of what was sent: below these is low / very low. */
    static final double KB_LOW = 0.6, KB_VERY_LOW = 0.45;

    /**
     * Whether a plugin that makes crystals hittable sooner (FasterCrystals) is on the server: then hitting one "before it
     * could be seen" is that plugin working, not a cheat, so crystal timing is shown but never flags anyone.
     */
    static boolean fasterCrystals() {
        try {
            return org.bukkit.Bukkit.getServer() != null && org.bukkit.Bukkit.getPluginManager().isPluginEnabled("FasterCrystals");
        } catch (final RuntimeException | LinkageError e) {
            return false;
        }
    }
    /** Through walls (hits, crystals, blocks broken; not placed): lag makes a few, so it takes this many to look, or be suspicious */
    static final int WALLS_LOOK = 5, WALLS_SUSPICIOUS = 10;
    /** Faster than this (blocks a second, over a second, with no hit for 1.5 seconds) is too fast, but on ice. */
    static final double SPEED_LIMIT = 7.8;
    /** Spleef: a block broken by hand this far (from the eyes to its nearest part) or further is far / very far. */
    static final double FAR_BREAK = 5.0, VERY_FAR_BREAK = 6.0;
    /** Spleef: breaking a block while looking more than this far (degrees) from it is off aim. */
    static final double OFF_AIM_BREAK = 30;
    /** Spleef: this many blocks broken by hand within one tick is more than a vanilla client can. */
    static final int BURST_BREAKS = 3;
    /** A totem back in the off hand sooner than this past their ping (ms) after a pop is faster than a person reacts. */
    static final int FAST_REFILL = 100;
    /** Picked up and put in the off hand within this (ms): one click straight after the other, as auto-totem does. */
    static final int INSTANT_CLICKS = 5;
    /** Totem refills this alike (their spread, ms, over 5 or more) are suspicious / worth a look (as TotemGuard's) */
    static final double REFILL_SPREAD_SUSPICIOUS = 5, REFILL_SPREAD_LOOK = 15;
    /** In the air (nothing under them, nothing helping) this long (ms) and never more than a block lower: hovering */
    static final int HOVER_MS = 1500;
    /** Rising this far (blocks) with nothing under them, nothing helping and nothing pushing them: higher than a jump */
    static final double RISE = 1.6;
    /** "On the ground" with nothing under them for this many ticks in a row: claiming to stand on nothing */
    static final int SPOOF_TICKS = 10;
    /** Timer: their game's ticks a second over a 10-second stretch past this is running fast. */
    static final double TIMER_RATE = 21;
    /** Air movement: a jump this many ticks long at least is judged */
    static final int AIR_TICKS = 9;
    /** Crystal PvP: a crystal hit this far (from the eyes to the nearest part of it) or further is far / very far. */
    static final double FAR_CRYSTAL = 3.5, VERY_FAR_CRYSTAL = 4.0;
    /** A crystal hit sooner after it appeared than this share of their ping: before they could have seen it. */
    static final double PREDICTED_SHARE = 0.6;
    /** ...only counted with a ping of at least this (ms): below it, timing says nothing. */
    static final int PREDICTED_MIN_PING = 40;
    /** What's left of sideways speed after each tick in the air */
    private static final double AIR = 0.91;

    /** What was measured for one fighter in one match. */
    public static final class Match {
        public final Recording rec;
        public final Recording.Fighter f;
        public final SumoVariant variant;
        /** The kind of arena (see {@link CheatChecks#kind}) */
        public final String kind;
        public final List<Float> reach = new ArrayList<>();
        public int longHits, veryLongHits;
        /** Format 2: knockback taken over what was sent; format 1: how far a hit sent them (no velocity known) */
        public final List<Float> kbRatio = new ArrayList<>(), kbDistance = new ArrayList<>();
        public int jumpResets, kbHits;
        /** How many of their view turns fit their mouse's step (-1: not checked), and from how many turns */
        public double rotationFit = -1;
        public int rotationTurns;
        public final List<Float> aimError = new ArrayList<>();
        public int ticksInRange, swingsInRange, landedInRange, bestSecond;
        public int sprintBackTicks, movingTicks;
        /** Speeds over a second (blocks a second) at or above 6, and how many seconds were looked at */
        public final List<Float> fastSeconds = new ArrayList<>();
        public int secondsChecked;
        public float topSpeed;
        /** Bot signs: W-taps and strafe switches (a second), hits landed 10-11 ticks after the last, of all hit gaps */
        public double wtapRate, strafeSwitchRate;
        public int hitGapsExact, hitGaps;
        /** Which of the bot signs this match shows (see botSigns) */
        public final List<String> botSigns = new ArrayList<>();
        /** Spleef: blocks broken by hand, those far / very far away, those broken looking away, bursts in one tick */
        public int breaks, farBreaks, veryFarBreaks, offAimBreaks, aimedBreaks, bursts;
        public float maxBreakReach;
        /** Crystal PvP: blocks placed, the same way */
        public int places, farPlaces, veryFarPlaces, offAimPlaces, aimedPlaces;
        public float maxPlaceReach;
        /** Totems: popped, put back in the off hand after (within 3 s, before the next pop), too fast, put in within 5 ms of
         * picking it up; each refill's time past their ping */
        public int totemPops, totemRefills, fastRefills, instantClicks;
        public final List<Float> refillDelays = new ArrayList<>();
        /** Through walls: player hits, crystal hits, blocks broken and placed with nothing of them to be seen; hits landed */
        public int wallHits, wallCrystals, wallBreaks, wallPlaces, landedHits;
        /** Inventory clicks, and those made holding a movement key (the game lets go of them all with an inventory open) */
        public int invClicks, moveClicks;
        /** Flying: hovering, rising higher than a jump, standing on nothing; ticks with nothing under them (0: not recorded) */
        public int hovers, rises, groundSpoofs, airTicks;
        /** Timer: their game's ticks counted, over how long (ms), 10-second stretches looked at, those running fast */
        public long clientTicks, clientTickMs;
        public int tickStretches, fastStretches;
        /** Air movement: jumps judged, those that sped up or turned in the air more than the game allows */
        public int airJumps, airOdd;
        /** Crystal PvP: crystals hit, far / very far, looking away, before they could have seen them (of those timed), most in a second */
        public int crystalHits, farCrystalHits, veryFarCrystalHits, offAimCrystalHits, aimedCrystalHits, predictedHits, timedHits, bestCrystalSecond;
        public float maxCrystalReach;

        Match(final Recording rec, final Recording.Fighter f) {
            this.rec = rec;
            this.f = f;
            this.variant = rec.variant();
            this.kind = kind(rec);
        }

        public List<String> opponents() {
            final List<String> names = new ArrayList<>();
            for (final Recording.Fighter o : this.rec.fighters)
                if (o.team != this.f.team)
                    names.add(o.name);
            return names;
        }
    }

    // ── One match ───────────────────────────────────────────────────────────

    public static Match analyse(final Recording rec, final Recording.Fighter f) {
        final Match m = new Match(rec, f);
        if (f.frames < 2)
            return m;
        // Every kind of arena: auto-totem, through walls, moving in an inventory, flying
        totems(m);
        walls(m);
        inventory(m);
        timer(m);
        if (!f.bedrock()) {
            flying(m);
            airMovement(m);
        }
        final List<Recording.Fighter> opponents = new ArrayList<>();
        for (final Recording.Fighter o : rec.fighters)
            if (o.team != f.team && o.frames > 0)
                opponents.add(o);
        final SumoVariant v = m.variant;
        if (rec.crystal) {
            // Crystal PvP: their sword hits' reach, how they turned, sprinted and moved, and how they placed, broke and popped
            // (knockback, aim, clicking and the bot signs are sumo's: explosions and crystals throw them off)
            if (!opponents.isEmpty())
                reach(m, opponents);
            breaking(m);
            crystals(m);
            if (!f.bedrock()) {
                rotation(m);
                sprintBack(m);
            }
            speed(m);
            return m;
        }
        if (rec.spleef) {
            // No hits in spleef (only snowballs): how they broke the floor, how they turned, sprinted and moved
            breaking(m);
            if (!f.bedrock()) {
                rotation(m);
                sprintBack(m);
            }
            speed(m);
            return m;
        }
        if (rec.kit) {
            // A plain arena (swords and the like): reach, knockback taken, how they turned and aimed, clicking, sprinting
            // and moving (the bot signs are sumo's: they were worked out from sumo matches)
            if (!opponents.isEmpty())
                reach(m, opponents);
            knockback(m, opponents);
            if (!f.bedrock()) {
                rotation(m);
                sprintBack(m);
            }
            if (!opponents.isEmpty()) {
                aim(m, opponents);
                clicks(m, opponents);
            }
            breaking(m);
            speed(m);
            return m;
        }
        final boolean itemSwings = v == SumoVariant.ARCHER || v == SumoVariant.SNOWBALL || v == SumoVariant.ROD;
        if (!opponents.isEmpty() && v != SumoVariant.SPEAR)
            reach(m, opponents);
        knockback(m, opponents);
        if (!f.bedrock())
            rotation(m);
        if (!opponents.isEmpty() && !itemSwings) {
            if (v != SumoVariant.SPEAR)
                aim(m, opponents);
            clicks(m, opponents);
        }
        if (!f.bedrock())
            sprintBack(m);
        if (v != SumoVariant.SPEAR && (rec.format >= 2 || !itemSwings))
            speed(m);
        if (!opponents.isEmpty() && !itemSwings && v != SumoVariant.SPEAR)
            botSigns(m);
        return m;
    }

    private static int at(final Recording.Fighter o, final int timeMs) {
        int i = Arrays.binarySearch(o.time, timeMs);
        if (i < 0)
            i = -i - 1;
        return Math.min(Math.max(i, 0), o.frames - 1);
    }

    private static double eyeHeight(final Recording.Fighter f, final int i) {
        return f.has(i, Recording.SNEAKING) ? 1.27 : 1.62;
    }

    /**
     * Spleef: each block they broke by hand (not by snowball): how far from their eyes its nearest part was, how far off
     * they were looking from it (the best of that tick and those either side: the look and the break can arrive a tick
     * apart either way), and any tick with
     * {@link #BURST_BREAKS} or more broken.
     */
    private static void breaking(final Match m) {
        final Recording.Fighter f = m.f;
        final int me = m.rec.fighters.indexOf(f);
        final double ox = Math.floor(m.rec.centreX) - m.rec.centreX, oy = Math.floor(m.rec.centreY) - m.rec.centreY,
                oz = Math.floor(m.rec.centreZ) - m.rec.centreZ;
        final List<Integer> times = new ArrayList<>();
        for (final Recording.BlockChange b : m.rec.blocks) {
            if (b.by() != me || (b.how() != Recording.BY_HAND && b.how() != Recording.PLACED))
                continue;
            final boolean placed = b.how() == Recording.PLACED;
            if (placed)
                m.places++;
            else {
                times.add(b.ms());
                m.breaks++;
            }
            int i = Arrays.binarySearch(f.time, b.ms());
            if (i < 0)
                i = -i - 2;
            i = Math.max(0, Math.min(i, f.frames - 1));
            final double minX = b.x() + ox, minY = b.y() + oy, minZ = b.z() + oz;
            double best = Double.MAX_VALUE, aim = Double.MAX_VALUE;
            for (int j = Math.max(0, i - 1); j <= Math.min(f.frames - 1, i + 1); j++) {
                final double ex = f.x[j], ey = f.y[j] + eyeHeight(f, j), ez = f.z[j];
                final double nx = Math.max(minX, Math.min(ex, minX + 1)) - ex, ny = Math.max(minY, Math.min(ey, minY + 1)) - ey,
                        nz = Math.max(minZ, Math.min(ez, minZ + 1)) - ez;
                best = Math.min(best, Math.sqrt(nx * nx + ny * ny + nz * nz));
                // How far off the block they were looking: from its middle, less how wide it looks from there
                final double cx = minX + 0.5 - ex, cy = minY + 0.5 - ey, cz = minZ + 0.5 - ez;
                final double dist = Math.sqrt(cx * cx + cy * cy + cz * cz);
                if (dist < 0.9) {
                    aim = 0;
                    continue;
                }
                final double yaw = Math.toRadians(f.yaw[j]), pitch = Math.toRadians(f.pitch[j]);
                final double lx = -Math.sin(yaw) * Math.cos(pitch), ly = -Math.sin(pitch), lz = Math.cos(yaw) * Math.cos(pitch);
                final double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (lx * cx + ly * cy + lz * cz) / dist))));
                aim = Math.min(aim, Math.max(0, angle - Math.toDegrees(Math.asin(Math.min(1, 0.87 / dist)))));
            }
            if (placed) {
                m.maxPlaceReach = Math.max(m.maxPlaceReach, (float) best);
                if (best >= FAR_BREAK)
                    m.farPlaces++;
                if (best >= VERY_FAR_BREAK)
                    m.veryFarPlaces++;
                if (!f.bedrock()) {
                    m.aimedPlaces++;
                    if (aim > OFF_AIM_BREAK)
                        m.offAimPlaces++;
                }
                continue;
            }
            m.maxBreakReach = Math.max(m.maxBreakReach, (float) best);
            if (best >= FAR_BREAK)
                m.farBreaks++;
            if (best >= VERY_FAR_BREAK)
                m.veryFarBreaks++;
            if (!f.bedrock()) {
                m.aimedBreaks++;
                if (aim > OFF_AIM_BREAK)
                    m.offAimBreaks++;
            }
        }
        java.util.Collections.sort(times);
        for (int a = 0; a + BURST_BREAKS - 1 < times.size(); a++)
            if (times.get(a + BURST_BREAKS - 1) - times.get(a) < 50) {
                m.bursts++;
                a += BURST_BREAKS - 1;
            }
    }

    /**
     * Crystal PvP: each crystal they hit: how far from their eyes its nearest part was and how far off they were looking
     * from it (the best of that tick and those either side), whether it was sooner after it appeared than they could have
     * seen it (a share of their ping: id-predicting autocrystals hit crystals the moment they're placed), and the most they
     * hit in any second.
     */
    private static void crystals(final Match m) {
        final Recording.Fighter f = m.f;
        final int me = m.rec.fighters.indexOf(f);
        final Map<Integer, Recording.Crystal> byId = new java.util.HashMap<>();
        for (final Recording.Crystal c : m.rec.crystals())
            byId.put(c.id(), c);
        final List<Integer> times = new ArrayList<>();
        for (final Recording.Event e : m.rec.events) {
            if (e.kind() != Recording.CRYSTAL_HIT || e.fighter() != me)
                continue;
            final Recording.Crystal c;
            try {
                c = byId.get(e.details().readInt());
            } catch (final java.io.IOException ex) {
                continue;
            }
            if (c == null)
                continue;
            m.crystalHits++;
            times.add(e.ms());
            int i = Arrays.binarySearch(f.time, e.ms());
            if (i < 0)
                i = -i - 2;
            i = Math.max(0, Math.min(i, f.frames - 1));
            double best = Double.MAX_VALUE, aim = Double.MAX_VALUE;
            for (int j = Math.max(0, i - 1); j <= Math.min(f.frames - 1, i + 1); j++) {
                final double ex = f.x[j], ey = f.y[j] + eyeHeight(f, j), ez = f.z[j];
                final double nx = Math.max(c.x() - 1, Math.min(ex, c.x() + 1)) - ex, ny = Math.max(c.y(), Math.min(ey, c.y() + 2)) - ey,
                        nz = Math.max(c.z() - 1, Math.min(ez, c.z() + 1)) - ez;
                best = Math.min(best, Math.sqrt(nx * nx + ny * ny + nz * nz));
                final double cx = c.x() - ex, cy = c.y() + 1 - ey, cz = c.z() - ez;
                final double dist = Math.sqrt(cx * cx + cy * cy + cz * cz);
                if (dist < 1.8) {
                    aim = 0;
                    continue;
                }
                final double yaw = Math.toRadians(f.yaw[j]), pitch = Math.toRadians(f.pitch[j]);
                final double lx = -Math.sin(yaw) * Math.cos(pitch), ly = -Math.sin(pitch), lz = Math.cos(yaw) * Math.cos(pitch);
                final double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (lx * cx + ly * cy + lz * cz) / dist))));
                aim = Math.min(aim, Math.max(0, angle - Math.toDegrees(Math.asin(Math.min(1, 1.73 / dist)))));
            }
            m.maxCrystalReach = Math.max(m.maxCrystalReach, (float) best);
            if (best >= FAR_CRYSTAL)
                m.farCrystalHits++;
            if (best >= VERY_FAR_CRYSTAL)
                m.veryFarCrystalHits++;
            if (!f.bedrock()) {
                m.aimedCrystalHits++;
                if (aim > OFF_AIM_BREAK)
                    m.offAimCrystalHits++;
            }
            final int ping = f.ping[i];
            if (ping >= PREDICTED_MIN_PING) {
                m.timedHits++;
                if (e.ms() - c.placedMs() < ping * PREDICTED_SHARE)
                    m.predictedHits++;
            }
        }
        java.util.Collections.sort(times);
        for (int a = 0, b = 0; b < times.size(); b++) {
            while (times.get(b) - times.get(a) >= 1000)
                a++;
            m.bestCrystalSecond = Math.max(m.bestCrystalSecond, b - a + 1);
        }
    }

    /** The frame at or before this time. */
    private static int frameAt(final Recording.Fighter f, final int ms) {
        int i = Arrays.binarySearch(f.time, ms);
        if (i < 0)
            i = -i - 2;
        return Math.max(0, Math.min(i, f.frames - 1));
    }

    /**
     * Auto-totem: after each totem they popped, how long until a new one was in their off hand (within 3 s, before the
     * next pop), past their ping (the pop takes that long to reach them and their click to come back): under
     * {@link #FAST_REFILL} is faster than anyone reacts; and a totem put in within {@link #INSTANT_CLICKS} ms of picking
     * it up is one click straight after the other.
     */
    private static void totems(final Match m) {
        final Recording.Fighter f = m.f;
        final int me = m.rec.fighters.indexOf(f);
        final List<Integer> pops = new ArrayList<>();
        final List<int[]> backs = new ArrayList<>(); // {ms, gap since picked up}
        for (final Recording.Event e : m.rec.events) {
            if (e.fighter() != me)
                continue;
            if (e.kind() == Recording.TOTEM)
                pops.add(e.ms());
            else if (e.kind() == Recording.TOTEM_BACK)
                try {
                    final java.io.DataInputStream in = e.details();
                    in.readByte();
                    backs.add(new int[]{e.ms(), in.readInt()});
                } catch (final java.io.IOException ignored) {
                    // a short one
                }
        }
        m.totemPops = pops.size();
        for (int p = 0; p < pops.size(); p++) {
            final int pop = pops.get(p), next = p + 1 < pops.size() ? pops.get(p + 1) : Integer.MAX_VALUE;
            for (final int[] back : backs) {
                if (back[0] < pop || back[0] > next || back[0] - pop > 3000)
                    continue;
                final int delay = back[0] - pop, ping = f.ping == null ? 0 : f.ping[frameAt(f, pop)];
                m.totemRefills++;
                m.refillDelays.add((float) (delay - ping));
                if (delay - ping < FAST_REFILL)
                    m.fastRefills++;
                if (back[1] >= 0 && back[1] <= INSTANT_CLICKS && delay <= 1500)
                    m.instantClicks++;
                break;
            }
        }
    }

    /** Through walls: what they did with nothing of it to be seen from their eyes (worked out live, as they did it). */
    private static void walls(final Match m) {
        final Recording.Fighter f = m.f;
        final int me = m.rec.fighters.indexOf(f);
        for (int i = 0; i < f.frames; i++)
            if (f.has(i, Recording.LANDED))
                m.landedHits++;
        for (final Recording.Event e : m.rec.events) {
            if (e.fighter() != me || e.kind() != Recording.THROUGH_WALL)
                continue;
            try {
                switch (e.details().readByte()) {
                    case Recording.WALL_HIT -> m.wallHits++;
                    case Recording.WALL_CRYSTAL -> m.wallCrystals++;
                    case Recording.WALL_BREAK -> m.wallBreaks++;
                    case Recording.WALL_PLACE -> m.wallPlaces++;
                    default -> { }
                }
            } catch (final java.io.IOException ignored) {
                // a short one
            }
        }
    }

    /**
     * Moving in an inventory: the game lets go of every movement key while an inventory is open, so a click with one
     * held (in that tick and the next) is a client moving with it open.
     */
    private static void inventory(final Match m) {
        final Recording.Fighter f = m.f;
        if (f.bedrock())
            return;
        final int me = m.rec.fighters.indexOf(f);
        final int moving = Recording.FORWARD | Recording.BACK | Recording.LEFT | Recording.RIGHT | Recording.JUMP;
        for (final Recording.Event e : m.rec.events) {
            if (e.fighter() != me || e.kind() != Recording.INVENTORY_CLICK)
                continue;
            m.invClicks++;
            final int i = frameAt(f, e.ms());
            if ((f.keys[i] & moving) != 0 && i + 1 < f.frames && (f.keys[i + 1] & moving) != 0)
                m.moveClicks++;
        }
    }

    /**
     * Flying: stretches with nothing under them (as the server saw it), nothing helping them through the air (an elytra,
     * levitation, water...) and nothing having pushed them in the last second and a half: one that lasts
     * {@link #HOVER_MS} without them ever dropping a block is hovering, one where they rise {@link #RISE} is higher than a
     * jump; and saying they're on the ground with nothing under them {@link #SPOOF_TICKS} ticks running is standing on
     * nothing.
     */
    private static void flying(final Match m) {
        final Recording.Fighter f = m.f;
        if (f.more == null)
            return;
        int pushed = -1000, start = -1, spoof = 0;
        double low = 0, high = 0, from = 0;
        for (int i = 0; i <= f.frames; i++) {
            final boolean end = i == f.frames;
            if (!end && (f.has(i, Recording.HURT) || f.has(i, Recording.GLIDING)
                    || f.hasMore(i, Recording.SHOT | Recording.TELEPORTED | Recording.VELOCITY | Recording.SURROUNDED | Recording.HELPED) || teleported(f, i)))
                pushed = i;
            final boolean nothingUnder = !end && f.hasMore(i, Recording.UNSUPPORTED);
            if (nothingUnder)
                m.airTicks++;
            final boolean free = nothingUnder && i - pushed > 30;
            // Standing on nothing
            if (free && f.has(i, Recording.GROUND))
                spoof++;
            else {
                if (spoof >= SPOOF_TICKS)
                    m.groundSpoofs++;
                spoof = 0;
            }
            // In the air
            if (free) {
                if (start < 0) {
                    start = i;
                    low = high = from = f.y[i];
                }
                low = Math.min(low, f.y[i]);
                high = Math.max(high, f.y[i]);
            } else if (start >= 0) {
                // (any stretch of it: a hover is often followed by falling, in the same time off the ground)
                if (hovered(f, start, i - 1))
                    m.hovers++;
                if (high - from >= RISE)
                    m.rises++;
                start = -1;
            }
        }
    }

    /**
     * Timer: their game's ticks (a 1.21.2+ client sends a packet at the end of each), counted about every second, put
     * together into stretches of 10 seconds or more: over {@link #TIMER_RATE} a second in one is their game running fast.
     * (Lag bunches them up and a frozen game catches up after, but over 10 seconds that evens out at 20.)
     */
    private static void timer(final Match m) {
        final int me = m.rec.fighters.indexOf(m.f);
        long n = 0, ms = 0;
        for (final Recording.Event e : m.rec.events) {
            if (e.fighter() != me || e.kind() != Recording.CLIENT_TICKS)
                continue;
            try {
                final java.io.DataInputStream in = e.details();
                final int ticks = in.readShort(), span = in.readShort();
                if (span <= 0)
                    continue;
                m.clientTicks += ticks;
                m.clientTickMs += span;
                n += ticks;
                ms += span;
                if (ms >= 10_000) {
                    m.tickStretches++;
                    if (n * 1000.0 / ms > TIMER_RATE)
                        m.fastStretches++;
                    n = ms = 0;
                }
            } catch (final java.io.IOException ignored) {
                // a short one
            }
        }
    }

    /**
     * Air movement (bhop, air-strafe): in the air the game only lets their speed fall (to 91% a tick) plus a little from
     * their keys (0.026 blocks a tick), so over a jump how they're moving at the end is bounded by how they were moving at
     * the start. A jump that ends faster than that allows, or turns more than it allows without losing speed, isn't the
     * game's (bumping into something only slows them). Each end is the average of four ticks and there's room on top, as
     * lag bunching moves together (a tick with none, then one with two) is everywhere: only clear speeding up or turning
     * in the air shows. (Bhop that speeds up on the ground, between jumps, is the speed check's.) Only jumps with nothing
     * pushing or helping them for 1.5 s before.
     */
    private static void airMovement(final Match m) {
        final Recording.Fighter f = m.f;
        int pushed = -1000, start = -1;
        for (int i = 0; i <= f.frames; i++) {
            final boolean end = i == f.frames;
            if (!end && (f.has(i, Recording.HURT) || f.has(i, Recording.GLIDING)
                    || f.hasMore(i, Recording.SHOT | Recording.TELEPORTED | Recording.VELOCITY | Recording.SURROUNDED | Recording.HELPED) || teleported(f, i)))
                pushed = i;
            final boolean air = !end && !f.has(i, Recording.GROUND) && i - pushed > 30 && (i == 0 || f.time[i] - f.time[i - 1] <= 120);
            if (air) {
                if (start < 0)
                    start = i;
            } else if (start >= 0) {
                judgeJump(m, f, start + 1, i - 1); // (from the tick after take-off: the jump's own push is in that one)
                start = -1;
            }
        }
    }

    private static void judgeJump(final Match m, final Recording.Fighter f, final int a, final int e) {
        if (e - a + 1 < AIR_TICKS)
            return;
        final double[] v0 = velocity(f, a, a + 4), v1 = velocity(f, e - 4, e);
        final double n = Math.max(1, (f.time[e - 2] - f.time[a + 2]) / 50.0); // ticks between the two
        final double decay = Math.pow(0.91, n), bound = 0.026 * (1 - decay) / 0.09;
        final double px = v0[0] * decay, pz = v0[1] * decay;
        final double end = Math.hypot(v1[0], v1[1]), predicted = Math.hypot(px, pz), off = Math.hypot(v1[0] - px, v1[1] - pz);
        final double slack = bound * 1.3 + 0.25;
        m.airJumps++;
        final boolean faster = end > predicted + slack;
        final boolean turned = off > slack && end >= predicted * 0.9 && Math.hypot(v0[0], v0[1]) > 0.1;
        if (faster || turned)
            m.airOdd++;
    }

    /** Their sideways velocity (blocks a tick, x and z) over these frames. */
    private static double[] velocity(final Recording.Fighter f, final int from, final int to) {
        final double ticks = Math.max(1, Math.round((f.time[to] - f.time[from]) / 50.0));
        return new double[]{(f.x[to] - f.x[from]) / ticks, (f.z[to] - f.z[from]) / ticks};
    }

    /** Whether, between these frames, they stayed {@link #HOVER_MS} or more within a block of the same height. */
    private static boolean hovered(final Recording.Fighter f, final int from, final int to) {
        for (int s = from; s <= to; s++) {
            double low = f.y[s], high = f.y[s];
            for (int j = s; j <= to; j++) {
                low = Math.min(low, f.y[j]);
                high = Math.max(high, f.y[j]);
                if (high - low >= 1.0)
                    break;
                if (f.time[j] - f.time[s] >= HOVER_MS)
                    return true;
            }
        }
        return false;
    }

    /** From an eye to the nearest point of a player's box (0.6 wide, 1.8 high) standing at (x, y, z). */
    static double toBox(final double ex, final double ey, final double ez, final double x, final double y, final double z) {
        final double dx = Math.max(Math.max(x - 0.3 - ex, 0), ex - (x + 0.3));
        final double dy = Math.max(Math.max(y - ey, 0), ey - (y + 1.8));
        final double dz = Math.max(Math.max(z - 0.3 - ez, 0), ez - (z + 0.3));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Each hit landed with their hand: the closest the one they hit was, anywhere over the attacker's ping (and some
     * more) before it, to where the attacker was then. Lag can only make a real hit look closer, never further.
     */
    private static void reach(final Match m, final List<Recording.Fighter> opponents) {
        final Recording.Fighter f = m.f;
        for (int i = 1; i < f.frames; i++) {
            if (!f.has(i, Recording.LANDED))
                continue;
            final List<Recording.Fighter> victims = new ArrayList<>(1);
            if (f.landedOn != null && f.landedOn[i] >= 0 && f.landedOn[i] < m.rec.fighters.size())
                victims.add(m.rec.fighters.get(f.landedOn[i]));
            else {
                for (final Recording.Fighter o : opponents) {
                    final int j = at(o, f.time[i]);
                    for (int k = Math.max(0, j - 1); k <= Math.min(o.frames - 1, j + 1); k++)
                        if (o.has(k, Recording.HURT)) {
                            victims.add(o);
                            break;
                        }
                }
                if (victims.isEmpty())
                    victims.addAll(opponents);
            }
            final double window = lagWindowMs(f.ping[i]);
            double best = Double.MAX_VALUE;
            for (final Recording.Fighter o : victims) {
                if (o.frames == 0)
                    continue;
                for (int k = i - 1; k <= i; k++) {
                    final double ey = f.y[k] + eyeHeight(f, k);
                    for (int j = Math.max(0, at(o, (int) (f.time[k] - window)) - 1); j < o.frames && o.time[j] <= f.time[k] + 50; j++)
                        best = Math.min(best, toBox(f.x[k], ey, f.z[k], o.x[j], o.y[j], o.z[j]));
                }
            }
            if (best == Double.MAX_VALUE || best > 8)
                continue;
            m.reach.add((float) best);
            if (best >= LONG_REACH)
                m.longHits++;
            if (best >= VERY_LONG_REACH)
                m.veryLongHits++;
        }
    }

    /**
     * After each knockback: how far they went along it over three ticks in the air (where ice or slow falling change
     * nothing sideways), from whenever it reached them within their ping. Vanilla: the velocity, then 91% of it, then 91%
     * of that; holding a key or jumping into it takes some off, but nowhere near half.
     */
    private static void knockback(final Match m, final List<Recording.Fighter> opponents) {
        final Recording.Fighter f = m.f;
        for (int i = 1; i < f.frames; i++) {
            final boolean melee = f.has(i, Recording.HURT);
            final boolean hit = melee || f.hasMore(i, Recording.SHOT);
            if (!hit)
                continue;
            final int lag = (int) Math.ceil((Math.min(f.ping[i], 1000) * 1.5 + 150) / 50.0) + 1;
            final int end = i + lag + 4;
            if (end >= f.frames)
                continue;
            boolean clean = true;
            for (int k = i + 1; k <= end && clean; k++)
                if (f.has(k, Recording.HURT) || f.hasMore(k, Recording.SHOT) || teleported(f, k))
                    clean = false;
            if (!clean || teleported(f, i))
                continue;
            final double ux, uz, h;
            // Sent the same tick, or as the next one's sending goes out
            float[] vel = null;
            if (f.velocity != null)
                for (int k = i - 1; k <= Math.min(i + 1, f.frames - 1) && vel == null; k++)
                    vel = f.velocity[k];
            if (vel != null) {
                h = Math.hypot(vel[0], vel[2]);
                if (h < 0.15)
                    continue;
                ux = vel[0] / h;
                uz = vel[2] / h;
            } else {
                if (m.rec.format >= 2 || !melee || opponents.isEmpty())
                    continue; // format 2 always has the velocity of a real knockback
                // Format 1: away from the nearest opponent
                Recording.Fighter from = null;
                double d = Double.MAX_VALUE;
                for (final Recording.Fighter o : opponents) {
                    final int j = at(o, f.time[i]);
                    final double dd = Math.hypot(f.x[i] - o.x[j], f.z[i] - o.z[j]);
                    if (dd < d) {
                        d = dd;
                        from = o;
                    }
                }
                if (from == null || d < 0.05 || d > 6)
                    continue;
                final int j = at(from, f.time[i]);
                ux = (f.x[i] - from.x[j]) / d;
                uz = (f.z[i] - from.z[j]) / d;
                h = 0;
            }
            // The furthest along it over three ticks: in the air if they were (where ice or slow falling change nothing
            // sideways), otherwise on the ground
            double bestAir = -Double.MAX_VALUE, bestAny = -Double.MAX_VALUE;
            int airStart = -1, anyStart = -1;
            for (int s = i - 1; s <= i + lag; s++) {
                if (s < 0 || s + 3 >= f.frames)
                    continue;
                final double along = (f.x[s + 3] - f.x[s]) * ux + (f.z[s + 3] - f.z[s]) * uz;
                if (along > bestAny) {
                    bestAny = along;
                    anyStart = s;
                }
                final boolean air = !f.has(s + 1, Recording.GROUND) && !f.has(s + 2, Recording.GROUND) && !f.has(s + 3, Recording.GROUND);
                if (air && along > bestAir) {
                    bestAir = along;
                    airStart = s;
                }
            }
            if (anyStart < 0)
                continue;
            final double best;
            final double expected;
            if (vel == null) {
                best = bestAny;
                expected = 0;
            } else if (airStart >= 0) {
                best = bestAir;
                expected = h * (1 + AIR + AIR * AIR);
            } else if (vel[1] > 0.1) {
                // Knocked upwards, yet never off the ground: vanilla always leaves it (nothing above them in sumo)
                best = bestAny;
                expected = h * (1 + AIR + AIR * AIR);
            } else {
                // Knocked along the ground (already falling, say): its friction, ice's if it's Ice Rink
                final double g = (m.variant == SumoVariant.ICE ? 0.98 : 0.6) * AIR;
                best = bestAny;
                expected = h * (1 + g + g * g);
            }
            final int bestStart = airStart >= 0 && vel != null ? airStart : anyStart;
            m.kbHits++;
            for (int k = bestStart; k <= bestStart + 1; k++)
                if (k > 0 && f.holding(k, Recording.JUMP) && f.has(k - 1, Recording.GROUND)) {
                    m.jumpResets++;
                    break;
                }
            if (vel != null)
                m.kbRatio.add((float) (best / expected));
            else
                m.kbDistance.add((float) best);
        }
    }

    /**
     * A mouse turns the view in whole steps of a size set by its sensitivity: every change in where they look up or
     * down is a whole number of the same step. Something turning it for them usually isn't.
     */
    private static void rotation(final Match m) {
        final Recording.Fighter f = m.f;
        final double[] turns = new double[f.frames];
        int n = 0;
        for (int i = 1; i < f.frames; i++) {
            if (teleported(f, i) || Math.abs(f.pitch[i]) >= 89.9f || Math.abs(f.pitch[i - 1]) >= 89.9f)
                continue;
            final double d = Math.abs((double) f.pitch[i] - f.pitch[i - 1]);
            if (d > 1e-4 && d < 60)
                turns[n++] = d;
        }
        if (n < 40)
            return;
        final double[] all = Arrays.copyOf(turns, n);
        final double[] sorted = all.clone();
        Arrays.sort(sorted);
        double bestFit = 0;
        for (int c = 0; c < Math.min(15, n); c++) {
            if (c > 0 && sorted[c] == sorted[c - 1])
                continue;
            for (int k = 1; k <= 8; k++) {
                final double step = sorted[c] / k;
                if (step < 0.002)
                    break;
                int fit = 0;
                for (final double d : all) {
                    final double steps = d / step;
                    if (steps < 2000 && Math.abs(steps - Math.rint(steps)) < 0.03)
                        fit++;
                }
                bestFit = Math.max(bestFit, fit / (double) n);
            }
        }
        m.rotationFit = bestFit;
        m.rotationTurns = n;
    }

    /** At each swing near an opponent: how far off (sideways, degrees) they were looking from them. */
    private static void aim(final Match m, final List<Recording.Fighter> opponents) {
        final Recording.Fighter f = m.f;
        for (int i = 0; i < f.frames; i++) {
            if (!f.has(i, Recording.SWUNG) || f.hasMore(i, Recording.DIGGING))
                continue;
            final double window = lagWindowMs(f.ping[i]);
            double best = Double.MAX_VALUE, near = Double.MAX_VALUE;
            for (final Recording.Fighter o : opponents)
                for (int j = Math.max(0, at(o, (int) (f.time[i] - window)) - 1); j < o.frames && o.time[j] <= f.time[i] + 50; j++) {
                    final double dx = o.x[j] - f.x[i], dz = o.z[j] - f.z[i];
                    final double d = Math.hypot(dx, dz);
                    if (d < 0.5)
                        continue;
                    final double want = Math.toDegrees(Math.atan2(-dx, dz));
                    final double err = Math.abs(((f.yaw[i] - want) % 360 + 540) % 360 - 180);
                    if (err < best) {
                        best = err;
                        near = d;
                    }
                }
            if (best != Double.MAX_VALUE && near < 3.5)
                m.aimError.add((float) best);
        }
    }

    /** Swings while an opponent's in reach, hits landed then, and the most swings in any second (not at a block). */
    private static void clicks(final Match m, final List<Recording.Fighter> opponents) {
        final Recording.Fighter f = m.f;
        final int[] window = new int[20];
        int inWindow = 0;
        for (int i = 0; i < f.frames; i++) {
            final boolean swung = f.has(i, Recording.SWUNG) && !f.hasMore(i, Recording.DIGGING);
            double near = Double.MAX_VALUE;
            for (final Recording.Fighter o : opponents) {
                final int j = at(o, f.time[i]);
                near = Math.min(near, Math.hypot(o.x[j] - f.x[i], o.z[j] - f.z[i]));
            }
            if (near < 4.5) {
                m.ticksInRange++;
                if (swung)
                    m.swingsInRange++;
                if (f.has(i, Recording.LANDED))
                    m.landedInRange++;
            }
            inWindow -= window[i % 20];
            window[i % 20] = swung ? 1 : 0;
            inWindow += window[i % 20];
            m.bestSecond = Math.max(m.bestSecond, inWindow);
        }
    }

    /** A match is bot-like with at least this many of the bot signs */
    static final int BOT_SIGNS = 4;

    /**
     * Signs of a bot playing for them, from recordings of one against everyone else's: (1) nearly every swing near the
     * other player lands (95%+, 10 swings or more: it only swings when the hit will land); (2) W-tapping, forward let go
     * and pressed again within three ticks, half a second or more (real players: about 0.1 a second); (3) switching
     * strafe direction 0.45 a second or more (real players: about 0.25); (4) jump resets on 10%+ of knockback (real
     * players: almost never); (5) 8%+ of hits landed exactly 10 or 11 ticks after the last, the moment they can be hit
     * again.
     */
    private static void botSigns(final Match m) {
        final Recording.Fighter f = m.f;
        int last = -1;
        for (int i = 0; i < f.frames; i++)
            if (f.has(i, Recording.LANDED)) {
                if (last >= 0 && i - last <= 30) {
                    m.hitGaps++;
                    if (i - last == 10 || i - last == 11)
                        m.hitGapsExact++;
                }
                last = i;
            }
        if (!f.bedrock()) {
            int wtaps = 0, switches = 0, lastStrafe = 0;
            for (int i = 1; i < f.frames; i++) {
                if (!f.holding(i, Recording.FORWARD) && f.holding(i - 1, Recording.FORWARD))
                    for (int k = i + 1; k <= Math.min(f.frames - 1, i + 3); k++)
                        if (f.holding(k, Recording.FORWARD)) {
                            wtaps++;
                            break;
                        }
                final int strafe = f.holding(i, Recording.LEFT) ? -1 : f.holding(i, Recording.RIGHT) ? 1 : 0;
                if (strafe != 0 && lastStrafe != 0 && strafe != lastStrafe)
                    switches++;
                if (strafe != 0)
                    lastStrafe = strafe;
            }
            final double seconds = f.frames / 20.0;
            m.wtapRate = wtaps / seconds;
            m.strafeSwitchRate = switches / seconds;
        }
        if (m.swingsInRange >= 10 && m.landedInRange >= 0.95 * m.swingsInRange)
            m.botSigns.add(Math.round(100.0 * m.landedInRange / m.swingsInRange) + "% of swings landed");
        if (m.wtapRate >= 0.5)
            m.botSigns.add(String.format("W-tapping %.1f a second", m.wtapRate));
        if (m.strafeSwitchRate >= 0.45)
            m.botSigns.add(String.format("switching strafe %.1f a second", m.strafeSwitchRate));
        if (m.kbHits >= 5 && m.jumpResets >= 0.1 * m.kbHits)
            m.botSigns.add(Math.round(100.0 * m.jumpResets / m.kbHits) + "% jump resets");
        if (m.hitGaps >= 3 && m.hitGapsExact >= 0.08 * m.hitGaps)
            m.botSigns.add(Math.round(100.0 * m.hitGapsExact / m.hitGaps) + "% of hits timed to the tick");
    }

    public static boolean botLike(final Match m) {
        return m.botSigns.size() >= BOT_SIGNS;
    }

    /** Sprinting while holding only back: vanilla stops sprinting as soon as you're not going forwards. */
    private static void sprintBack(final Match m) {
        final Recording.Fighter f = m.f;
        for (int i = 1; i < f.frames; i++) {
            if (Math.hypot(f.x[i] - f.x[i - 1], f.z[i] - f.z[i - 1]) < 0.05)
                continue;
            m.movingTicks++;
            if (f.has(i, Recording.SPRINTING) && f.holding(i, Recording.BACK) && !f.holding(i, Recording.FORWARD))
                m.sprintBackTicks++;
        }
    }

    /**
     * Moved by the server: marked as such from format 2 on, and before that, more than 2.5 blocks in a tick (lag
     * bunching a few ticks' moves together doesn't come near that).
     */
    static boolean teleported(final Recording.Fighter f, final int i) {
        if (f.hasMore(i, Recording.TELEPORTED))
            return true;
        if (i == 0)
            return false;
        if (f.time[i] - f.time[i - 1] > 1000)
            return true; // (out of a free-for-all for a while, and back in)
        final double dx = f.x[i] - f.x[i - 1], dy = f.y[i] - f.y[i - 1], dz = f.z[i] - f.z[i - 1];
        return dx * dx + dy * dy + dz * dz > 2.5 * 2.5;
    }

    /** How far they went in a second, every quarter second, when nothing had hit or moved them for 1.5 seconds. */
    private static void speed(final Match m) {
        final Recording.Fighter f = m.f;
        int lastPushed = -1000;
        final int[] pushedAt = new int[f.frames];
        for (int i = 0; i < f.frames; i++) {
            // (gliding, levitating, in water or on slime and the like: not their own running, so not measured)
            if (f.has(i, Recording.HURT) || f.has(i, Recording.GLIDING)
                    || f.hasMore(i, Recording.SHOT | Recording.TELEPORTED | Recording.VELOCITY | Recording.SURROUNDED | Recording.HELPED) || teleported(f, i))
                lastPushed = i;
            pushedAt[i] = lastPushed;
        }
        for (int i = 20; i < f.frames; i += 5) {
            if (pushedAt[i] > i - 30 || i < 30)
                continue;
            final int ms = f.time[i] - f.time[i - 20];
            if (ms <= 0 || ms > 1300)
                continue;
            final double speed = Math.hypot(f.x[i] - f.x[i - 20], f.z[i] - f.z[i - 20]) / (ms / 1000.0);
            m.secondsChecked++;
            m.topSpeed = Math.max(m.topSpeed, (float) speed);
            if (speed >= 6)
                m.fastSeconds.add((float) speed);
        }
    }

    // ── Everyone (what's usual) ─────────────────────────────────────────────

    /** What's usual, from everyone's recordings: per-player figures to rank someone among, and per-variant limits. */
    /**
     * Which kind of arena a recording is from, so each player is only measured against others playing the same:
     * "sumo", "kit", "spleef" or "crystal", and a free-for-all session's parts of sumo, kit or crystal ("sumo-ffa"...).
     */
    public static String kind(final Recording rec) {
        final String base = rec.crystal ? "crystal" : rec.spleef ? "spleef" : rec.kit ? "kit" : "sumo";
        return rec.session && !rec.spleef ? base + "-ffa" : base;
    }

    /** "Sumo", "Kit FFA"... */
    public static String kindTitle(final String kind) {
        final String base = kind.replace("-ffa", "");
        return Character.toUpperCase(base.charAt(0)) + base.substring(1) + (kind.endsWith("-ffa") ? " FFA" : "");
    }

    /** What's usual for each kind of arena (see {@link #kind}), from everyone's recordings. */
    public static final class Baselines {
        public final long builtAt = System.currentTimeMillis();
        public int matches, players;
        final Map<String, Baseline> byKind = new HashMap<>();

        /** That kind's (an empty one, nobody to rank against, if there are no recordings of it). */
        public Baseline of(final String kind) {
            return this.byKind.computeIfAbsent(kind, Baseline::new);
        }
    }

    /** Everyone's matches, each kind of arena on its own. */
    public static Baselines baselines(final Collection<Match> matches) {
        final Baselines all = new Baselines();
        all.matches = (int) matches.stream().map(m -> m.rec).distinct().count();
        all.players = (int) matches.stream().map(m -> m.f.uuid).distinct().count();
        final Map<String, List<Match>> byKind = new HashMap<>();
        for (final Match m : matches)
            byKind.computeIfAbsent(m.kind, k -> new ArrayList<>()).add(m);
        byKind.forEach((kind, theirs) -> all.byKind.put(kind, baseline(kind, theirs)));
        return all;
    }

    public static final class Baseline {
        public final long builtAt = System.currentTimeMillis();
        public int matches, players;
        /** The kind of arena (see {@link CheatChecks#kind}) */
        public final String kind;
        /** Kit and crystal arenas (speed potions and the like): their speed limit, from their own matches (or none yet) */
        Double speedLimit;

        Baseline(final String kind) {
            this.kind = kind;
        }
        /** Each player's figure (those with enough to go on), sorted */
        final Map<String, double[]> perPlayer = new HashMap<>();
        /** Format 1's knockback distance, usual (the middle) for each variant */
        final Map<SumoVariant, Double> kbDistanceMiddle = new HashMap<>();
        /** The speed limit on ice, from Ice Rink matches (or none yet) */
        Double iceSpeedLimit;

        /** The share of players (with enough to go on) whose figure is below this one: 0 to 1, or -1 if none. */
        public double rank(final String figure, final double value) {
            final double[] all = this.perPlayer.get(figure);
            if (all == null || all.length < 5)
                return -1;
            int below = 0;
            while (below < all.length && all[below] < value)
                below++;
            return below / (double) all.length;
        }

        public double speedLimit(final SumoVariant v) {
            if (v == SumoVariant.ICE)
                return this.iceSpeedLimit == null ? Double.MAX_VALUE : this.iceSpeedLimit;
            return this.speedLimit != null ? this.speedLimit : SPEED_LIMIT;
        }
    }

    /** The figures players are ranked by, and how much each needs. */
    static final String AIM = "aim", CPS = "cps", ACCURACY = "accuracy", JUMP_RESETS = "jumpresets", REACH = "reach",
            KB = "kb", KB_OLD = "kbold";

    /** What's usual in one kind of arena, from everyone's matches there. */
    static Baseline baseline(final String kind, final Collection<Match> matches) {
        final Baseline b = new Baseline(kind);
        b.matches = (int) matches.stream().map(m -> m.rec).distinct().count();
        // Format 1's knockback distances, per variant, first (each player's is then a share of it)
        final Map<SumoVariant, List<Float>> distances = new HashMap<>();
        final List<Float> iceSpeeds = new ArrayList<>(), speeds = new ArrayList<>();
        int iceSeconds = 0, seconds = 0;
        for (final Match m : matches) {
            distances.computeIfAbsent(m.variant, x -> new ArrayList<>()).addAll(m.kbDistance);
            if (m.variant == SumoVariant.ICE) {
                iceSpeeds.addAll(m.fastSeconds);
                iceSeconds += m.secondsChecked;
            } else {
                speeds.addAll(m.fastSeconds);
                seconds += m.secondsChecked;
            }
        }
        // Kit and crystal arenas: their own speed limit, the same way as ice's (sumo's own is set from real recordings)
        if (!kind.startsWith("sumo") && seconds >= 300)
            b.speedLimit = Math.max(SPEED_LIMIT, fastest(speeds, seconds) * 1.2);
        for (final Map.Entry<SumoVariant, List<Float>> e : distances.entrySet())
            if (e.getValue().size() >= 100)
                b.kbDistanceMiddle.put(e.getKey(), (double) median(e.getValue()));
        if (iceSeconds >= 300) {
            // The fastest one in a thousand seconds on ice (all below 6 count as below it), and some room
            b.iceSpeedLimit = Math.max(SPEED_LIMIT, fastest(iceSpeeds, iceSeconds) * 1.2);
        }
        final Map<UUID, List<Match>> byPlayer = new HashMap<>();
        for (final Match m : matches)
            byPlayer.computeIfAbsent(m.f.uuid, x -> new ArrayList<>()).add(m);
        b.players = byPlayer.size();
        final Map<String, List<Double>> figures = new HashMap<>();
        for (final List<Match> theirs : byPlayer.values()) {
            final Summary s = new Summary(theirs, b);
            s.figures().forEach((k, v) -> figures.computeIfAbsent(k, x -> new ArrayList<>()).add(v));
        }
        figures.forEach((k, v) -> b.perPlayer.put(k, v.stream().mapToDouble(Double::doubleValue).sorted().toArray()));
        return b;
    }

    /** The fastest second in a thousand, of {@code seconds} looked at ({@code fast}: those at 6 or more; all below count as 6). */
    private static double fastest(final List<Float> fast, final int seconds) {
        final int index = seconds - 1 - seconds / 1000;
        final List<Float> sorted = new ArrayList<>(fast);
        sorted.sort(null);
        final int from = seconds - sorted.size();
        return index < from ? 6 : sorted.get(index - from);
    }

    /** How spread out these are (their standard deviation). */
    static double spread(final List<Float> values) {
        double sum = 0, squares = 0;
        for (final float v : values) {
            sum += v;
            squares += v * v;
        }
        final double mean = sum / values.size();
        return Math.sqrt(Math.max(0, squares / values.size() - mean * mean));
    }

    static float median(final List<Float> values) {
        if (values.isEmpty())
            return Float.NaN;
        final float[] a = new float[values.size()];
        for (int i = 0; i < a.length; i++)
            a[i] = values.get(i);
        Arrays.sort(a);
        return a[a.length / 2];
    }

    static float quantile(final List<Float> values, final double q) {
        if (values.isEmpty())
            return Float.NaN;
        final float[] a = new float[values.size()];
        for (int i = 0; i < a.length; i++)
            a[i] = values.get(i);
        Arrays.sort(a);
        return a[(int) Math.min(a.length - 1, Math.round(q * (a.length - 1)))];
    }

    // ── One player, all their matches ───────────────────────────────────────

    public enum Verdict {
        FINE, LOOK, SUSPICIOUS, INFO, SKIPPED
    }

    /** One line of the report. */
    public record Line(String check, Verdict verdict, String text, String explain) {
    }

    /** All of one player's matches put together. */
    public static final class Summary {
        /** Through-wall hits, crystals and blocks broken (placed blocks don't count: see the Through walls line) */
        int wallsCounted() {
            return this.wallHits + this.wallCrystals + this.wallBreaks;
        }

        public final List<Match> matches;
        final Baseline baseline;
        /** The kind of arena these matches are from (one summary per kind: see {@link CheatChecks#kind}) */
        public final String kind;
        public final List<Float> reach = new ArrayList<>(), kbRatio = new ArrayList<>(), kbShare = new ArrayList<>(), aimError = new ArrayList<>();
        public int longHits, veryLongHits, jumpResets, kbHits;
        public int rotationMatches, rotationOff;
        public int ticksInRange, swingsInRange, landedInRange, bestSecond;
        public int sprintBackTicks, movingTicks;
        public int secondsChecked, tooFast;
        /** Matches that look like a bot playing (see botSigns), and how many could be judged */
        public int botLike, botChecked;
        public float topSpeed, maxReach;
        public boolean bedrock;
        /** Totems, walls, inventory, flying: see Match */
        public int totemPops, totemRefills, fastRefills, instantClicks, wallHits, wallCrystals, wallBreaks, wallPlaces, landedHits,
                invClicks, moveClicks, hovers, rises, groundSpoofs, airTicks, tickStretches, fastStretches, airJumps, airOdd;
        public long clientTicks, clientTickMs;
        public final List<Float> refillDelays = new ArrayList<>();
        /** Spleef and crystal PvP: see Match */
        public int breaks, farBreaks, veryFarBreaks, offAimBreaks, aimedBreaks, bursts;
        public float maxBreakReach;
        public int places, farPlaces, veryFarPlaces, offAimPlaces, aimedPlaces;
        public float maxPlaceReach;
        public int crystalHits, farCrystalHits, veryFarCrystalHits, offAimCrystalHits, aimedCrystalHits, predictedHits, timedHits, bestCrystalSecond;
        public float maxCrystalReach;

        public Summary(final List<Match> matches, final Baseline baseline) {
            this.matches = matches;
            this.baseline = baseline;
            this.kind = baseline != null ? baseline.kind : matches.isEmpty() ? "sumo" : matches.get(0).kind;
            for (final Match m : matches) {
                this.reach.addAll(m.reach);
                for (final float r : m.reach)
                    this.maxReach = Math.max(this.maxReach, r);
                this.longHits += m.longHits;
                this.veryLongHits += m.veryLongHits;
                this.kbRatio.addAll(m.kbRatio);
                final Double middle = baseline == null ? null : baseline.kbDistanceMiddle.get(m.variant);
                if (middle != null && middle > 0)
                    for (final float d : m.kbDistance)
                        this.kbShare.add((float) (d / middle));
                this.jumpResets += m.jumpResets;
                this.kbHits += m.kbHits;
                if (m.rotationFit >= 0) {
                    this.rotationMatches++;
                    if (m.rotationFit < ROTATION_OFF)
                        this.rotationOff++;
                }
                this.aimError.addAll(m.aimError);
                this.ticksInRange += m.ticksInRange;
                this.swingsInRange += m.swingsInRange;
                this.landedInRange += m.landedInRange;
                this.bestSecond = Math.max(this.bestSecond, m.bestSecond);
                this.sprintBackTicks += m.sprintBackTicks;
                this.movingTicks += m.movingTicks;
                this.secondsChecked += m.secondsChecked;
                this.topSpeed = Math.max(this.topSpeed, m.topSpeed);
                this.tooFast += tooFast(m, baseline);
                if (m.swingsInRange >= 10) {
                    this.botChecked++;
                    if (botLike(m))
                        this.botLike++;
                }
                this.bedrock |= m.f.bedrock();
                this.breaks += m.breaks;
                this.farBreaks += m.farBreaks;
                this.veryFarBreaks += m.veryFarBreaks;
                this.offAimBreaks += m.offAimBreaks;
                this.aimedBreaks += m.aimedBreaks;
                this.bursts += m.bursts;
                this.maxBreakReach = Math.max(this.maxBreakReach, m.maxBreakReach);
                this.totemPops += m.totemPops;
                this.totemRefills += m.totemRefills;
                this.fastRefills += m.fastRefills;
                this.instantClicks += m.instantClicks;
                this.refillDelays.addAll(m.refillDelays);
                this.wallHits += m.wallHits;
                this.wallCrystals += m.wallCrystals;
                this.wallBreaks += m.wallBreaks;
                this.wallPlaces += m.wallPlaces;
                this.landedHits += m.landedHits;
                this.invClicks += m.invClicks;
                this.moveClicks += m.moveClicks;
                this.hovers += m.hovers;
                this.rises += m.rises;
                this.groundSpoofs += m.groundSpoofs;
                this.airTicks += m.airTicks;
                this.clientTicks += m.clientTicks;
                this.clientTickMs += m.clientTickMs;
                this.tickStretches += m.tickStretches;
                this.fastStretches += m.fastStretches;
                this.airJumps += m.airJumps;
                this.airOdd += m.airOdd;
                this.places += m.places;
                this.farPlaces += m.farPlaces;
                this.veryFarPlaces += m.veryFarPlaces;
                this.offAimPlaces += m.offAimPlaces;
                this.aimedPlaces += m.aimedPlaces;
                this.maxPlaceReach = Math.max(this.maxPlaceReach, m.maxPlaceReach);
                this.crystalHits += m.crystalHits;
                this.farCrystalHits += m.farCrystalHits;
                this.veryFarCrystalHits += m.veryFarCrystalHits;
                this.offAimCrystalHits += m.offAimCrystalHits;
                this.aimedCrystalHits += m.aimedCrystalHits;
                this.predictedHits += m.predictedHits;
                this.timedHits += m.timedHits;
                this.bestCrystalSecond = Math.max(this.bestCrystalSecond, m.bestCrystalSecond);
                this.maxCrystalReach = Math.max(this.maxCrystalReach, m.maxCrystalReach);
            }
        }

        static int tooFast(final Match m, final Baseline baseline) {
            final double limit = baseline == null ? (m.variant == SumoVariant.ICE ? Double.MAX_VALUE : SPEED_LIMIT) : baseline.speedLimit(m.variant);
            int n = 0;
            for (final float s : m.fastSeconds)
                if (s > limit)
                    n++;
            return n;
        }

        /** The figures this player is ranked among others by (only those with enough behind them). */
        Map<String, Double> figures() {
            final Map<String, Double> out = new LinkedHashMap<>();
            if (this.aimError.size() >= 100)
                out.put(AIM, (double) median(this.aimError));
            if (this.ticksInRange >= 2000) {
                out.put(CPS, 20.0 * this.swingsInRange / this.ticksInRange);
                if (this.swingsInRange >= 100)
                    out.put(ACCURACY, this.landedInRange / (double) this.swingsInRange);
            }
            if (this.kbHits >= 50)
                out.put(JUMP_RESETS, this.jumpResets / (double) this.kbHits);
            if (this.reach.size() >= 50)
                out.put(REACH, (double) quantile(this.reach, 0.99));
            if (this.kbRatio.size() >= 15)
                out.put(KB, (double) median(this.kbRatio));
            if (this.kbShare.size() >= 50)
                out.put(KB_OLD, (double) median(this.kbShare));
            return out;
        }

        private String among(final String figure, final double value, final boolean higherIsMore, final String more, final String less) {
            if (this.baseline == null)
                return "";
            final double r = this.baseline.rank(figure, value);
            if (r < 0)
                return "";
            final int pct = (int) Math.round(100 * (higherIsMore ? r : 1 - r));
            final String who = "% of " + kindTitle(this.kind) + " players";
            return pct >= 50 ? "; " + more + " than " + pct + who : "; " + less + " than " + (100 - pct) + who;
        }

        public List<Line> lines() {
            final List<Line> lines = new ArrayList<>();
            // A bot playing for them (sumo only: what's looked for was found from a sumo bot)
            final boolean sumo = this.kind.startsWith("sumo"), noMelee = this.kind.equals("spleef"),
                    noKnockback = noMelee || this.kind.startsWith("crystal");
            if (!sumo) {
                // (no bot line)
            } else if (this.botChecked == 0)
                lines.add(new Line("Bot", Verdict.SKIPPED, "no matches with enough swings to judge", ""));
            else {
                final Verdict v = this.botLike >= 3 ? Verdict.SUSPICIOUS : this.botLike >= 1 ? Verdict.LOOK : Verdict.FINE;
                lines.add(new Line("Bot", v, this.botLike == 0 ? "no match played like a bot (" + this.botChecked + " looked at)"
                                : String.format("%d of %d matches played like a bot (%d%%)", this.botLike, this.botChecked, Math.round(100.0 * this.botLike / this.botChecked)),
                        "A match is bot-like with " + BOT_SIGNS + " or more of: nearly every swing landing, W-tapping and switching strafe far"
                                + " more than anyone, jump resets, hits timed to the tick. Found from real recordings of a sumo bot: no real player's"
                                + " match here showed four. Someone using one now and then shows it only in those matches."));
            }
            // Reach (not in spleef: no hits there)
            if (noMelee) {
                // (no reach line)
            } else if (this.reach.isEmpty())
                lines.add(new Line("Reach", Verdict.SKIPPED, "no hits landed to measure", ""));
            else {
                final double share = this.longHits / (double) this.reach.size();
                final Verdict v = this.veryLongHits >= 2 || (this.longHits >= 4 && share >= 0.005) ? Verdict.SUSPICIOUS
                        : this.longHits >= 2 && share >= 0.002 ? Verdict.LOOK : Verdict.FINE;
                lines.add(new Line("Reach", v, String.format("%,d hits, longest %.2f blocks, %d at %.1f+ (%d at %.1f+)", this.reach.size(),
                        this.maxReach, this.longHits, LONG_REACH, this.veryLongHits, VERY_LONG_REACH)
                        + (this.reach.size() >= 50 ? among(REACH, quantile(this.reach, 0.99), true, "further", "shorter") : ""),
                        "The closest the one they hit had been to them at any point over their ping (and a quarter second more) before the"
                                + " hit: lag can only make a hit look closer. Real players here almost never go past " + LONG_REACH + "."));
            }
            // Knockback (not in spleef or crystal: snowballs and explosions throw it off)
            if (noKnockback) {
                // (no knockback line)
            } else if (this.kbRatio.size() >= 15) {
                final float med = median(this.kbRatio);
                final Verdict v = med < KB_VERY_LOW ? Verdict.SUSPICIOUS : med < KB_LOW ? Verdict.LOOK : Verdict.FINE;
                lines.add(new Line("Knockback", v, String.format("took %d%% of what was sent (typical hit, %d hits)", Math.round(med * 100), this.kbRatio.size())
                                + among(KB, med, true, "more", "less"),
                        "How far each knockback carried them through the air, against what the server sent. Jumping into it or holding"
                                + " a key takes some off (down to about 75%), anti-knockback a lot more."));
            } else if (this.kbShare.size() >= 30) {
                final float med = median(this.kbShare);
                lines.add(new Line("Knockback", Verdict.INFO, String.format("went %d%% as far as usual when hit (%d hits; older recordings: rough)",
                                Math.round(med * 100), this.kbShare.size()) + among(KB_OLD, med, true, "further", "less far"),
                        "Matches recorded before 9 Nov don't have the knockback the server sent, so this is only against how far"
                                + " hits usually send people. A lot depends on how they play."));
            } else
                lines.add(new Line("Knockback", Verdict.SKIPPED, "not enough hits taken yet (" + (this.kbRatio.size() + this.kbShare.size()) + ")", ""));
            // Mouse steps
            if (this.bedrock)
                lines.add(new Line("Aim steps", Verdict.SKIPPED, "Bedrock player (no mouse steps to check)", ""));
            else if (this.rotationMatches == 0)
                lines.add(new Line("Aim steps", Verdict.SKIPPED, "not enough looking around yet", ""));
            else {
                final double share = this.rotationOff / (double) this.rotationMatches;
                final Verdict v = this.rotationOff >= 3 && share >= 0.3 ? Verdict.SUSPICIOUS : this.rotationOff >= 2 && share >= 0.15 ? Verdict.LOOK : Verdict.FINE;
                lines.add(new Line("Aim steps", v, this.rotationOff + " of " + this.rotationMatches + " matches turned in ways a mouse doesn't",
                        "A mouse turns the view in whole steps of a size set by its sensitivity. Aim assists usually don't (a few"
                                + " fake it). Changing sensitivity or a zoom mod mid-match can throw one match off."));
            }
            // Sprinting backwards
            if (this.bedrock)
                lines.add(new Line("Sprinting", Verdict.SKIPPED, "Bedrock player", ""));
            else {
                final double share = this.movingTicks == 0 ? 0 : this.sprintBackTicks / (double) this.movingTicks;
                final Verdict v = this.sprintBackTicks >= 40 && share >= 0.005 ? Verdict.SUSPICIOUS
                        : this.sprintBackTicks >= 15 && share >= 0.002 ? Verdict.LOOK : Verdict.FINE;
                lines.add(new Line("Sprinting", v, this.sprintBackTicks == 0 ? "never sprinted backwards"
                                : String.format("sprinted backwards for %d ticks (%.2f%% of the time moving)", this.sprintBackTicks, share * 100),
                        "Vanilla stops sprinting as soon as you stop going forwards. Real players here: a handful of ticks at most."));
            }
            // Speed
            if (this.secondsChecked == 0)
                lines.add(new Line("Speed", Verdict.SKIPPED, "nothing to measure (Spear matches, or always being hit)", ""));
            else {
                final Verdict v = this.tooFast >= 3 ? Verdict.SUSPICIOUS : this.tooFast >= 1 ? Verdict.LOOK : Verdict.FINE;
                lines.add(new Line("Speed", v, String.format("fastest %.1f blocks a second%s", this.topSpeed,
                                this.tooFast == 0 ? "" : ", too fast " + this.tooFast + " time" + (this.tooFast == 1 ? "" : "s")),
                        "Over a second, with nothing hitting them for a second and a half before. Real players here top out around 7.3 (sprint-jumping); past "
                                + SPEED_LIMIT + " is too fast (on ice: against other Ice Rink matches). Once can be a lag spike."));
            }
            // For comparison only
            if (this.aimError.size() >= 30) {
                final float med = median(this.aimError);
                lines.add(new Line("Aim at swings", Verdict.INFO, String.format("%.1f° off, typically", med) + among(AIM, med, false, "closer", "further off"),
                        "How far to the side of the one they swung at they were looking. Good players are close too: only very"
                                + " close in every match is odd."));
            }
            if (this.ticksInRange >= 200) {
                final double cps = 20.0 * this.swingsInRange / this.ticksInRange;
                final double accuracy = this.swingsInRange == 0 ? 0 : this.landedInRange / (double) this.swingsInRange;
                lines.add(new Line("Clicking", Verdict.INFO, String.format("%.1f a second in reach, %d%% landed, most in a second %d",
                                cps, Math.round(accuracy * 100), this.bestSecond) + among(CPS, cps, true, "more", "fewer"),
                        "Only to the tick (20 a second at most), and drag or butterfly clicking can reach that: never a sign by itself."));
            }
            // Spleef: breaking the floor
            if (this.breaks > 0) {
                final double far = this.farBreaks / (double) this.breaks;
                lines.add(new Line("Break reach", this.veryFarBreaks >= 2 || (this.farBreaks >= 5 && far >= 0.02) ? Verdict.SUSPICIOUS
                        : this.farBreaks >= 2 ? Verdict.LOOK : Verdict.FINE,
                        String.format("%,d blocks broken by hand, furthest %.1f blocks, %d at %.0f+ (%d at %.0f+)", this.breaks, this.maxBreakReach,
                                this.farBreaks, FAR_BREAK, this.veryFarBreaks, VERY_FAR_BREAK),
                        "Spleef: from their eyes to the nearest part of each block as they broke it. Survival reach is 4.5 blocks;"
                                + " lag adds a little, not a block and more."));
                if (this.aimedBreaks > 0) {
                    final double off = this.offAimBreaks / (double) this.aimedBreaks;
                    lines.add(new Line("Break aim", this.offAimBreaks >= 5 && off >= 0.05 ? Verdict.SUSPICIOUS
                            : this.offAimBreaks >= 2 ? Verdict.LOOK : Verdict.FINE,
                            String.format("%d of %,d broken while looking %.0f°+ away from the block", this.offAimBreaks, this.aimedBreaks, OFF_AIM_BREAK),
                            "Spleef: a block can only be broken by looking at it. Breaking ones they aren't looking at is what nukers do."));
                }
                lines.add(new Line("Break speed", this.bursts >= 2 ? Verdict.SUSPICIOUS : this.bursts == 1 ? Verdict.LOOK : Verdict.FINE,
                        this.bursts == 0 ? "never more than a vanilla client can" : String.format("%d time%s %d+ blocks in one tick", this.bursts,
                                this.bursts == 1 ? "" : "s", BURST_BREAKS),
                        "Spleef: a vanilla client breaks at most one block a tick (lag can bunch two together)."));
            }
            // Auto-totem
            if (this.totemRefills > 0) {
                final double spread = this.refillDelays.size() >= 5 ? spread(this.refillDelays) : Double.NaN;
                final double fast = this.fastRefills / (double) this.totemRefills;
                final Verdict v = this.instantClicks >= 2 || (this.fastRefills >= 3 && fast >= 0.3) || spread < REFILL_SPREAD_SUSPICIOUS ? Verdict.SUSPICIOUS
                        : this.instantClicks >= 1 || this.fastRefills >= 3 || spread < REFILL_SPREAD_LOOK ? Verdict.LOOK : Verdict.FINE;
                lines.add(new Line("Totems", v, String.format("%d back in the off hand after %d pops, typically %d ms past their ping%s; %d too fast, %d put in within %d ms of picking it up",
                                this.totemRefills, this.totemPops, Math.round(median(this.refillDelays)),
                                Double.isNaN(spread) ? "" : String.format(" (spread %d ms)", Math.round(spread)), this.fastRefills, this.instantClicks, INSTANT_CLICKS),
                        "After a totem pops, how long until a new one is in the off hand, less their ping. A person needs a few hundred ms (an"
                                + " inventory click or the swap key); auto-totem is near instant, or the same delay every time (a spread under "
                                + Math.round(REFILL_SPREAD_LOOK) + " ms), or puts it in the moment it's picked up."));
            }
            // Through walls (what they hit, broke or placed with nothing of it to be seen from their eyes)
            // (placed blocks don't count: a block goes against another one's face, so the spot it fills is often behind
            // something from their eyes: that's normal building)
            final int walls = this.wallsCounted();
            final int actions = this.landedHits + this.crystalHits + this.breaks;
            if (walls > 0 || (actions > 0 && !this.kind.startsWith("sumo"))) {
                final Verdict v = walls >= WALLS_SUSPICIOUS && walls >= 0.05 * actions ? Verdict.SUSPICIOUS
                        : walls >= WALLS_LOOK ? Verdict.LOOK : Verdict.FINE;
                lines.add(new Line("Through walls", v, walls == 0 ? "nothing hit or broken through a wall (" + actions + " looked at)"
                                : String.format("%d of %,d through a wall: %d player hits, %d crystals, %d blocks broken",
                                walls, actions, this.wallHits, this.wallCrystals, this.wallBreaks),
                        "Worked out as it happened: every line from their eyes to it was blocked. Lag makes a few now and then;"
                                + " hitting or breaking through walls again and again is what killaura and nuker do. (Placed blocks"
                                + " aren't counted: placing against a block often fills a spot hidden behind it.)"));
            }
            // Moving in an inventory
            if (this.invClicks > 0 && !this.bedrock) {
                final Verdict v = this.moveClicks >= 5 && this.moveClicks >= 0.1 * this.invClicks ? Verdict.SUSPICIOUS
                        : this.moveClicks >= 2 ? Verdict.LOOK : Verdict.FINE;
                lines.add(new Line("Inventory", v, String.format("%d of %,d inventory clicks made holding a movement key", this.moveClicks, this.invClicks),
                        "The game lets go of every movement key while an inventory is open: clicking in one while still moving is an"
                                + " inventory-move cheat (or a mod for it)."));
            }
            // Flying
            if (this.airTicks > 0 && !this.bedrock) {
                final int odd = this.hovers + this.rises + this.groundSpoofs;
                final Verdict v = this.hovers >= 3 || this.rises >= 3 || this.groundSpoofs >= 3 ? Verdict.SUSPICIOUS : odd >= 1 ? Verdict.LOOK : Verdict.FINE;
                lines.add(new Line("Flying", v, odd == 0 ? "never hovered, rose higher than a jump or stood on nothing"
                                : String.format("hovered %d time%s, rose higher than a jump %d, stood on nothing %d", this.hovers,
                                this.hovers == 1 ? "" : "s", this.rises, this.groundSpoofs),
                        "With nothing under them (as the server saw it), nothing helping (elytra, levitation, slow falling, jump boost, water,"
                                + " ladders, slime) and nothing pushing them: staying up " + (HOVER_MS / 1000.0) + " s without dropping, rising more than "
                                + RISE + " blocks, or saying they're on the ground for " + SPOOF_TICKS + " ticks. Recorded from 12 Nov on."));
            }
            // Timer
            if (this.tickStretches > 0 && this.clientTicks > 0) { // (a game that sends none: older versions, through ViaVersion)
                final double rate = this.clientTicks * 1000.0 / this.clientTickMs;
                final Verdict v = this.fastStretches >= 3 || (this.clientTickMs >= 60_000 && rate > 20.6) ? Verdict.SUSPICIOUS
                        : this.fastStretches >= 1 ? Verdict.LOOK : Verdict.FINE;
                lines.add(new Line("Timer", v, String.format("their game ran at %.2f ticks a second (20 is normal); %d of %d 10-second stretches over %.0f",
                                rate, this.fastStretches, this.tickStretches, TIMER_RATE),
                        "A game sends a packet at the end of each of its ticks: 20 a second. Lag bunches them and a frozen game catches up,"
                                + " but over 10 seconds that evens out. More, again and again, is a timer cheat speeding the game up."));
            }
            // Air movement (bhop, air-strafe)
            if (this.airJumps > 0 && !this.bedrock) {
                final Verdict v = this.airOdd >= 5 && this.airOdd >= 0.1 * this.airJumps ? Verdict.SUSPICIOUS
                        : this.airOdd >= 2 ? Verdict.LOOK : Verdict.FINE;
                lines.add(new Line("Air movement", v, String.format("%d of %,d jumps sped up or turned in the air more than the game allows",
                                this.airOdd, this.airJumps),
                        "In the air the game only lets speed fall, plus a little from the keys: ending a jump faster than that, or turning"
                                + " sharply without losing speed, is bhop or air-strafe (bumping into something only slows you)."));
            }
            // Crystal PvP: popping crystals, and placing blocks
            if (this.crystalHits > 0) {
                final double far = this.farCrystalHits / (double) this.crystalHits;
                lines.add(new Line("Crystal reach", this.veryFarCrystalHits >= 2 || (this.farCrystalHits >= 5 && far >= 0.02) ? Verdict.SUSPICIOUS
                        : this.farCrystalHits >= 2 ? Verdict.LOOK : Verdict.FINE,
                        String.format("%,d crystals hit, furthest %.1f blocks, %d at %.1f+ (%d at %.1f+)", this.crystalHits, this.maxCrystalReach,
                                this.farCrystalHits, FAR_CRYSTAL, this.veryFarCrystalHits, VERY_FAR_CRYSTAL),
                        "From their eyes to the nearest part of each crystal as they hit it. Survival reach is 3 blocks; lag adds a"
                                + " little, not half a block and more."));
                if (this.aimedCrystalHits > 0) {
                    final double off = this.offAimCrystalHits / (double) this.aimedCrystalHits;
                    lines.add(new Line("Crystal aim", this.offAimCrystalHits >= 5 && off >= 0.05 ? Verdict.SUSPICIOUS
                            : this.offAimCrystalHits >= 2 ? Verdict.LOOK : Verdict.FINE,
                            String.format("%d of %,d hit while looking %.0f°+ away from the crystal", this.offAimCrystalHits, this.aimedCrystalHits, OFF_AIM_BREAK),
                            "A crystal can only be hit by looking at it. Hitting ones they aren't looking at is what auto-crystal does"
                                    + " (most turn the view as well, so this catches only some)."));
                }
                if (this.timedHits > 0) {
                    final double share = this.predictedHits / (double) this.timedHits;
                    lines.add(new Line("Crystal timing", fasterCrystals() ? Verdict.INFO : this.predictedHits >= 20 && share >= 0.15 ? Verdict.SUSPICIOUS
                            : this.predictedHits >= 10 && share >= 0.05 ? Verdict.LOOK : Verdict.FINE,
                            String.format("%d of %,d hit before they could have seen them; most in a second %d", this.predictedHits, this.timedHits,
                                    this.bestCrystalSecond),
                            "A crystal can't be seen until the server's word of it reaches them, and their hit takes as long to come back:"
                                    + " about their ping. Hitting it sooner means hitting it before it showed, which is what auto-crystals that"
                                    + " guess a new crystal's number do. Only counted with a ping of " + PREDICTED_MIN_PING + " ms or more."));
                } else
                    lines.add(new Line("Crystal timing", Verdict.INFO, String.format("most crystals hit in a second: %d (ping too low to time them)",
                            this.bestCrystalSecond), ""));
            }
            if (this.places > 0) {
                final double far = this.farPlaces / (double) this.places;
                lines.add(new Line("Place reach", this.veryFarPlaces >= 2 || (this.farPlaces >= 5 && far >= 0.02) ? Verdict.SUSPICIOUS
                        : this.farPlaces >= 2 ? Verdict.LOOK : Verdict.FINE,
                        String.format("%,d blocks placed, furthest %.1f blocks, %d at %.0f+ (%d at %.0f+)", this.places, this.maxPlaceReach,
                                this.farPlaces, FAR_BREAK, this.veryFarPlaces, VERY_FAR_BREAK),
                        "From their eyes to the nearest part of each block they placed. Survival reach is 4.5 blocks."));
                if (this.aimedPlaces > 0)
                    lines.add(new Line("Place aim", this.offAimPlaces >= 5 && this.offAimPlaces / (double) this.aimedPlaces >= 0.05 ? Verdict.SUSPICIOUS
                            : this.offAimPlaces >= 2 ? Verdict.LOOK : Verdict.FINE,
                            String.format("%d of %,d placed while looking %.0f°+ away from the block", this.offAimPlaces, this.aimedPlaces, OFF_AIM_BREAK),
                            "A block goes against the one they're looking at. Placing blocks they aren't looking at is what scaffold and"
                                    + " surround cheats do."));
            }
            if (this.kbHits >= 30)
                lines.add(new Line("Jump resets", Verdict.INFO, String.format("jumped as knockback came %d%% of the time", Math.round(100.0 * this.jumpResets / this.kbHits))
                                + among(JUMP_RESETS, this.jumpResets / (double) this.kbHits, true, "more", "less"),
                        "Jumping the moment knockback lands cuts it a little: a real technique. Near every time is what macros do."));
            return lines;
        }

        /**
         * How suspicious, for ranking players against each other (/arenacheck top): each suspicious line counts a lot, each
         * one worth a closer look a little, and how much of it there is on top (bot-like matches, long hits...).
         */
        public int suspicion() {
            int score = 0;
            for (final Line l : this.lines())
                score += l.verdict() == Verdict.SUSPICIOUS ? 100 : l.verdict() == Verdict.LOOK ? 20 : 0;
            score += 3 * this.botLike + 2 * this.veryLongHits + this.longHits + 2 * this.rotationOff + this.sprintBackTicks / 10 + 2 * this.tooFast
                    + 2 * this.veryFarBreaks + this.farBreaks + this.offAimBreaks + 3 * this.bursts
                    + 2 * this.veryFarCrystalHits + this.farCrystalHits + this.offAimCrystalHits
                    + (!fasterCrystals() && this.predictedHits >= 10 && this.predictedHits >= 0.05 * Math.max(1, this.timedHits) ? 2 * this.predictedHits : 0)
                    + 2 * this.veryFarPlaces + this.farPlaces + this.offAimPlaces
                    + 5 * this.instantClicks + (this.fastRefills >= 3 ? 2 * this.fastRefills : 0)
                    + (this.wallsCounted() >= WALLS_LOOK ? 2 * this.wallsCounted() : 0)
                    + this.moveClicks + 3 * (this.hovers + this.rises + this.groundSpoofs) + 5 * this.fastStretches + 2 * this.airOdd;
            if (this.kbRatio.size() >= 15 && median(this.kbRatio) < KB_LOW)
                score += 10;
            return score;
        }

        /** What stands out ("Bot: 119 of 372 matches played like a bot; Reach: ..."), or "" for nothing. */
        public String reasons() {
            final List<String> out = new ArrayList<>();
            for (final Line l : this.lines())
                if (l.verdict() == Verdict.SUSPICIOUS || l.verdict() == Verdict.LOOK)
                    out.add(l.check() + ": " + l.text());
            return String.join("; ", out);
        }

        public Verdict overall() {
            Verdict worst = Verdict.FINE;
            for (final Line l : this.lines())
                if (l.verdict() == Verdict.SUSPICIOUS)
                    return Verdict.SUSPICIOUS;
                else if (l.verdict() == Verdict.LOOK)
                    worst = Verdict.LOOK;
            return worst;
        }

        /** How odd one match looks (0: nothing), and why. */
        public static Map.Entry<Integer, String> oddness(final Match m, final Baseline baseline) {
            int score = 0;
            final List<String> why = new ArrayList<>();
            if (botLike(m)) {
                score += 2 * m.botSigns.size();
                why.add("bot-like: " + String.join(", ", m.botSigns));
            }
            if (m.longHits > 0) {
                score += m.longHits + 3 * m.veryLongHits;
                float max = 0;
                for (final float r : m.reach)
                    max = Math.max(max, r);
                why.add(String.format("%d long hit%s (up to %.2f)", m.longHits, m.longHits == 1 ? "" : "s", max));
            }
            if (m.kbRatio.size() >= 4 && median(m.kbRatio) < KB_LOW) {
                score += 3;
                why.add(String.format("took %d%% knockback", Math.round(median(m.kbRatio) * 100)));
            }
            if (m.rotationFit >= 0 && m.rotationFit < ROTATION_OFF) {
                score += 2;
                why.add(String.format("turning %d%% mouse-like", Math.round(m.rotationFit * 100)));
            }
            if (m.sprintBackTicks >= 5) {
                score += m.sprintBackTicks / 5;
                why.add(m.sprintBackTicks + " ticks sprinting backwards");
            }
            final int fast = tooFast(m, baseline);
            if (fast > 0) {
                score += 2 * fast;
                why.add(String.format("too fast (%.1f b/s)", m.topSpeed));
            }
            if (m.farBreaks > 0) {
                score += m.farBreaks + 2 * m.veryFarBreaks;
                why.add(String.format("%d block%s broken far away (up to %.1f)", m.farBreaks, m.farBreaks == 1 ? "" : "s", m.maxBreakReach));
            }
            if (m.offAimBreaks > 0) {
                score += m.offAimBreaks;
                why.add(m.offAimBreaks + " broken looking away");
            }
            if (m.bursts > 0) {
                score += 3 * m.bursts;
                why.add(m.bursts + "x " + BURST_BREAKS + "+ blocks in a tick");
            }
            if (m.farCrystalHits > 0) {
                score += m.farCrystalHits + 2 * m.veryFarCrystalHits;
                why.add(String.format("%d crystal%s hit far away (up to %.1f)", m.farCrystalHits, m.farCrystalHits == 1 ? "" : "s", m.maxCrystalReach));
            }
            if (m.offAimCrystalHits > 0) {
                score += m.offAimCrystalHits;
                why.add(m.offAimCrystalHits + " crystal" + (m.offAimCrystalHits == 1 ? "" : "s") + " hit looking away");
            }
            if (m.predictedHits >= 3 && !fasterCrystals()) {
                score += 2 * m.predictedHits;
                why.add(m.predictedHits + " crystal" + (m.predictedHits == 1 ? "" : "s") + " hit before they could see it");
            }
            if (m.instantClicks > 0 || m.fastRefills >= 2) {
                score += 5 * m.instantClicks + 2 * m.fastRefills;
                why.add(m.fastRefills + " totem" + (m.fastRefills == 1 ? "" : "s") + " back too fast" + (m.instantClicks > 0 ? ", " + m.instantClicks + " put in instantly" : ""));
            }
            final int walls = m.wallHits + m.wallCrystals + m.wallBreaks; // (not blocks placed: see the Through walls line)
            if (walls >= 3) {
                score += 2 * walls;
                why.add(walls + " through a wall");
            }
            if (m.moveClicks > 0) {
                score += m.moveClicks;
                why.add(m.moveClicks + " inventory click" + (m.moveClicks == 1 ? "" : "s") + " while moving");
            }
            if (m.fastStretches > 0) {
                score += 5 * m.fastStretches;
                why.add(String.format("timer: %.1f ticks/s", m.clientTicks * 1000.0 / Math.max(1, m.clientTickMs)));
            }
            if (m.airOdd > 0) {
                score += 2 * m.airOdd;
                why.add(m.airOdd + " odd jump" + (m.airOdd == 1 ? "" : "s"));
            }
            if (m.hovers + m.rises + m.groundSpoofs > 0) {
                score += 3 * (m.hovers + m.rises + m.groundSpoofs);
                why.add(String.format("flying: %d hover, %d rise, %d on nothing", m.hovers, m.rises, m.groundSpoofs));
            }
            if (m.farPlaces > 0) {
                score += m.farPlaces + 2 * m.veryFarPlaces;
                why.add(String.format("%d block%s placed far away (up to %.1f)", m.farPlaces, m.farPlaces == 1 ? "" : "s", m.maxPlaceReach));
            }
            if (m.offAimPlaces > 0) {
                score += m.offAimPlaces;
                why.add(m.offAimPlaces + " placed looking away");
            }
            return Map.entry(score, String.join(", ", why));
        }

        /** Their oddest matches, oddest first. */
        public List<Map.Entry<Match, String>> oddest(final int count) {
            final List<Map.Entry<Match, Map.Entry<Integer, String>>> scored = new ArrayList<>();
            for (final Match m : this.matches) {
                final Map.Entry<Integer, String> o = oddness(m, this.baseline);
                if (o.getKey() > 0)
                    scored.add(Map.entry(m, o));
            }
            scored.sort(Comparator.comparingInt((Map.Entry<Match, Map.Entry<Integer, String>> e) -> -e.getValue().getKey())
                    .thenComparing(e -> -e.getKey().rec.started));
            final List<Map.Entry<Match, String>> out = new ArrayList<>();
            for (int i = 0; i < Math.min(count, scored.size()); i++)
                out.add(Map.entry(scored.get(i).getKey(), scored.get(i).getValue().getValue()));
            return out;
        }
    }

    /** Every recording under the folder (newest first), up to {@code max}. */
    public static List<File> recordings(final File root, final int max) {
        final List<File> files = new ArrayList<>();
        final File[] days = root.listFiles(File::isDirectory);
        if (days == null)
            return files;
        Arrays.sort(days, Comparator.comparing(File::getName).reversed());
        for (final File day : days) {
            final File[] in = day.listFiles((d, n) -> n.endsWith(".sarec.gz"));
            if (in == null)
                continue;
            Arrays.sort(in, Comparator.comparing(File::getName).reversed());
            for (final File f : in) {
                files.add(f);
                if (files.size() >= max)
                    return files;
            }
        }
        return files;
    }
}
