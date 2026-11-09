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
    static final double KB_LOW = 0.7, KB_VERY_LOW = 0.5;
    /** Faster than this (blocks a second, over a second, with no hit for 1.5 seconds) is too fast, but on ice. */
    static final double SPEED_LIMIT = 7.8;
    /** What's left of sideways speed after each tick in the air */
    private static final double AIR = 0.91;

    /** What was measured for one fighter in one match. */
    public static final class Match {
        public final Recording rec;
        public final Recording.Fighter f;
        public final SumoVariant variant;
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

        Match(final Recording rec, final Recording.Fighter f) {
            this.rec = rec;
            this.f = f;
            this.variant = rec.variant();
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
        final List<Recording.Fighter> opponents = new ArrayList<>();
        for (final Recording.Fighter o : rec.fighters)
            if (o.team != f.team && o.frames > 0)
                opponents.add(o);
        final SumoVariant v = m.variant;
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
        final double dx = f.x[i] - f.x[i - 1], dy = f.y[i] - f.y[i - 1], dz = f.z[i] - f.z[i - 1];
        return dx * dx + dy * dy + dz * dz > 2.5 * 2.5;
    }

    /** How far they went in a second, every quarter second, when nothing had hit or moved them for 1.5 seconds. */
    private static void speed(final Match m) {
        final Recording.Fighter f = m.f;
        int lastPushed = -1000;
        final int[] pushedAt = new int[f.frames];
        for (int i = 0; i < f.frames; i++) {
            if (f.has(i, Recording.HURT) || f.hasMore(i, Recording.SHOT | Recording.TELEPORTED | Recording.VELOCITY) || teleported(f, i))
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
    public static final class Baseline {
        public final long builtAt = System.currentTimeMillis();
        public int matches, players;
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
            return SPEED_LIMIT;
        }
    }

    /** The figures players are ranked by, and how much each needs. */
    static final String AIM = "aim", CPS = "cps", ACCURACY = "accuracy", JUMP_RESETS = "jumpresets", REACH = "reach",
            KB = "kb", KB_OLD = "kbold";

    public static Baseline baseline(final Collection<Match> matches) {
        final Baseline b = new Baseline();
        b.matches = (int) matches.stream().map(m -> m.rec).distinct().count();
        // Format 1's knockback distances, per variant, first (each player's is then a share of it)
        final Map<SumoVariant, List<Float>> distances = new HashMap<>();
        final List<Float> iceSpeeds = new ArrayList<>();
        int iceSeconds = 0;
        for (final Match m : matches) {
            distances.computeIfAbsent(m.variant, x -> new ArrayList<>()).addAll(m.kbDistance);
            if (m.variant == SumoVariant.ICE) {
                iceSpeeds.addAll(m.fastSeconds);
                iceSeconds += m.secondsChecked;
            }
        }
        for (final Map.Entry<SumoVariant, List<Float>> e : distances.entrySet())
            if (e.getValue().size() >= 100)
                b.kbDistanceMiddle.put(e.getKey(), (double) median(e.getValue()));
        if (iceSeconds >= 300) {
            // The fastest one in a thousand seconds on ice (all below 6 count as below it), and some room
            final int index = iceSeconds - 1 - iceSeconds / 1000;
            iceSpeeds.sort(null);
            final int from = iceSeconds - iceSpeeds.size();
            final double p999 = index < from ? 6 : iceSpeeds.get(index - from);
            b.iceSpeedLimit = Math.max(SPEED_LIMIT, p999 * 1.2);
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
        public final List<Match> matches;
        final Baseline baseline;
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

        public Summary(final List<Match> matches, final Baseline baseline) {
            this.matches = matches;
            this.baseline = baseline;
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
            return pct >= 50 ? "; " + more + " than " + pct + "% of players" : "; " + less + " than " + (100 - pct) + "% of players";
        }

        public List<Line> lines() {
            final List<Line> lines = new ArrayList<>();
            // A bot playing for them
            if (this.botChecked == 0)
                lines.add(new Line("Bot", Verdict.SKIPPED, "no matches with enough swings to judge", ""));
            else {
                final Verdict v = this.botLike >= 3 ? Verdict.SUSPICIOUS : this.botLike >= 1 ? Verdict.LOOK : Verdict.FINE;
                lines.add(new Line("Bot", v, this.botLike == 0 ? "no match played like a bot (" + this.botChecked + " looked at)"
                                : String.format("%d of %d matches played like a bot (%d%%)", this.botLike, this.botChecked, Math.round(100.0 * this.botLike / this.botChecked)),
                        "A match is bot-like with " + BOT_SIGNS + " or more of: nearly every swing landing, W-tapping and switching strafe far"
                                + " more than anyone, jump resets, hits timed to the tick. Found from real recordings of a sumo bot: no real player's"
                                + " match here showed four. Someone using one now and then shows it only in those matches."));
            }
            // Reach
            if (this.reach.isEmpty())
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
            // Knockback
            if (this.kbRatio.size() >= 15) {
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
            score += 3 * this.botLike + 2 * this.veryLongHits + this.longHits + 2 * this.rotationOff + this.sprintBackTicks / 10 + 2 * this.tooFast;
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
