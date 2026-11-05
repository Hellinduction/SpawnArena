package me.zeronull.spawnarena;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * What sumo bots remember of each player they've fought, so a Master bot doesn't start from nothing each match:
 * <ul>
 * <li>which of its styles has beaten them: it plays the ones that work more often, still trying the others now and
 * then in case they've changed (Thompson sampling, a simple form of machine learning);</li>
 * <li>their habits: which way they strafe more, how long they keep strafing one way before switching, and how often
 * they sprint-jump in.</li>
 * </ul>
 * Every practice match teaches it their habits (whatever the level); only Master plays on them, and only Master's
 * matches teach it which style works. Older matches count for less and less, so it keeps up as they change. Kept with
 * the player (in their player data), so it lasts restarts.
 */
final class BotMemory {
    /** Master's styles (AGGRESSIVE, DEFENSIVE, STRAFER, BALANCED: see SumoBot.Style) */
    static final int STYLES = 4;
    private static final String[] STYLE_NAMES = {"Aggressive", "Defensive", "Strafer", "Balanced"};
    /** Each match, how much everything before it still counts */
    private static final double FORGET = 0.93;
    private static final NamespacedKey KEY = new NamespacedKey(SpawnArena.INSTANCE, "bot_memory");
    private static final Map<UUID, BotMemory> LOADED = new ConcurrentHashMap<>();

    /** Matches each style won and lost against them (as the bot sees it), older ones counting for less */
    private final double[] styleWins = new double[STYLES], styleLosses = new double[STYLES];
    /** Ticks they've strafed each way, as a bot facing them sees it (its right, its left) */
    private double strafeRight, strafeLeft;
    /** Their strafe runs (one way before switching): ticks in all, and how many */
    private double runTicks, runs;
    /** Times they came in from range, and how many of those were sprint-jumps in */
    private double approaches, jumpIns;

    /** On the player's thread, as a match starts: what's remembered of them (nothing yet: a fresh memory). */
    static BotMemory load(final Player player) {
        final BotMemory memory = new BotMemory();
        final String saved = player.getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
        if (saved != null)
            memory.parse(saved);
        LOADED.put(player.getUniqueId(), memory);
        return memory;
    }

    /** What's remembered of them, if it's been loaded (a match has started since they joined). */
    static BotMemory of(final UUID id) {
        return LOADED.get(id);
    }

    /** On the player's thread: kept with them. */
    static void save(final Player player) {
        final BotMemory memory = LOADED.get(player.getUniqueId());
        if (memory != null && player.isOnline())
            player.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, memory.toString());
    }

    /** On the player's thread: what's remembered of them, loaded if it isn't yet. */
    static BotMemory peek(final Player player) {
        final BotMemory memory = LOADED.get(player.getUniqueId());
        return memory != null ? memory : load(player);
    }

    /** What it's learnt of them, for /arenabotmemory (MiniMessage lines). */
    synchronized java.util.List<String> describe() {
        final java.util.List<String> out = new java.util.ArrayList<>();
        double matches = 0;
        for (int i = 0; i < STYLES; i++)
            matches += this.styleWins[i] + this.styleLosses[i];
        out.add("<gray>Master matches remembered: <white>" + String.format("%.1f", matches) + " <dark_gray>(older ones count for less)");
        for (int i = 0; i < STYLES; i++) {
            final double w = this.styleWins[i], l = this.styleLosses[i];
            final int chance = (int) Math.round(100 * (w + 1) / (w + l + 2));
            out.add("<gray> " + STYLE_NAMES[i] + ": <green>" + String.format("%.1f", w) + " won <gray>/ <red>"
                    + String.format("%.1f", l) + " lost <dark_gray>(it reckons " + chance + "% to beat them)");
        }
        final double side = this.strafeSide();
        out.add("<gray>Strafes: <white>" + (Math.abs(side) < 0.1 ? "both ways about the same"
                : Math.round(100 * this.strafeRight / Math.max(1, this.strafeRight + this.strafeLeft)) + "% to a bot's right")
                + " <dark_gray>(" + Math.round(this.strafeRight + this.strafeLeft) + " ticks seen)");
        final double run = this.averageRun(0, 0);
        out.add("<gray>Switches strafe after: <white>" + (run > 0 ? String.format("%.1f", run / 20) + " s on average" : "not known yet"));
        out.add("<gray>Sprint-jumps in: <white>" + Math.round(100 * this.jumpInRate(0, 0)) + "% of the time <dark_gray>("
                + Math.round(this.approaches) + " times coming in seen" + (this.jumpInRate(0, 0) > 0.3 ? "; Master sidesteps them" : "") + ")");
        return out;
    }

    static void forget(final UUID id) {
        LOADED.remove(id);
    }

    // ── What it knows ───────────────────────────────────────────────────────

    /**
     * The style to play them with: each style's chance of beating them is guessed from how it's done (a draw from
     * its Beta distribution), and the best guess is played. A style that's worked is picked more, but one that's
     * hardly been tried still gets its turn, as its guesses vary more.
     */
    synchronized int pickStyle() {
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        int best = 0;
        double bestGuess = -1;
        for (int i = 0; i < STYLES; i++) {
            final double guess = beta(1 + this.styleWins[i], 1 + this.styleLosses[i], random);
            if (guess > bestGuess) {
                bestGuess = guess;
                best = i;
            }
        }
        return best;
    }

    /**
     * Which way they strafe more, and how sure it is: -1 (always its left) to 1 (always its right); near 0 when
     * there's no clear way, or not much seen yet.
     */
    synchronized double strafeSide() {
        return (this.strafeRight - this.strafeLeft) / (this.strafeRight + this.strafeLeft + 100);
    }

    /** Ticks they usually strafe one way before switching (with this match's runs too); 0 if not known yet. */
    synchronized double averageRun(final double moreTicks, final int moreRuns) {
        final double n = this.runs + moreRuns;
        return n < 3 ? 0 : (this.runTicks + moreTicks) / n;
    }

    /** How often they sprint-jump in (with this match's too), 0 to 1: low until it's seen a few. */
    synchronized double jumpInRate(final int moreApproaches, final int moreJumpIns) {
        return (this.jumpIns + moreJumpIns) / (this.approaches + moreApproaches + 3);
    }

    /** A match is over: what it saw is added, everything before counting for a little less. */
    synchronized void learn(final int style, final boolean botWon, final double right, final double left,
                            final double runTicks, final int runs, final int approaches, final int jumpIns) {
        for (int i = 0; i < STYLES; i++) {
            this.styleWins[i] *= FORGET;
            this.styleLosses[i] *= FORGET;
        }
        if (style >= 0 && style < STYLES) {
            if (botWon)
                this.styleWins[style]++;
            else
                this.styleLosses[style]++;
        }
        this.strafeRight = this.strafeRight * FORGET + right;
        this.strafeLeft = this.strafeLeft * FORGET + left;
        this.runTicks = this.runTicks * FORGET + runTicks;
        this.runs = this.runs * FORGET + runs;
        this.approaches = this.approaches * FORGET + approaches;
        this.jumpIns = this.jumpIns * FORGET + jumpIns;
    }

    // ── Kept as text ────────────────────────────────────────────────────────

    @Override
    public synchronized String toString() {
        final StringBuilder out = new StringBuilder("1");
        for (int i = 0; i < STYLES; i++)
            out.append(',').append(round(this.styleWins[i])).append(',').append(round(this.styleLosses[i]));
        for (final double d : new double[]{this.strafeRight, this.strafeLeft, this.runTicks, this.runs, this.approaches, this.jumpIns})
            out.append(',').append(round(d));
        return out.toString();
    }

    private void parse(final String saved) {
        try {
            final String[] parts = saved.split(",");
            if (!"1".equals(parts[0]) || parts.length != 1 + STYLES * 2 + 6)
                return;
            int n = 1;
            for (int i = 0; i < STYLES; i++) {
                this.styleWins[i] = Double.parseDouble(parts[n++]);
                this.styleLosses[i] = Double.parseDouble(parts[n++]);
            }
            this.strafeRight = Double.parseDouble(parts[n++]);
            this.strafeLeft = Double.parseDouble(parts[n++]);
            this.runTicks = Double.parseDouble(parts[n++]);
            this.runs = Double.parseDouble(parts[n++]);
            this.approaches = Double.parseDouble(parts[n++]);
            this.jumpIns = Double.parseDouble(parts[n]);
        } catch (final NumberFormatException ignored) {
            // Unreadable: start again
        }
    }

    private static String round(final double d) {
        return Double.toString(Math.round(d * 1000) / 1000.0);
    }

    // ── Sampling ────────────────────────────────────────────────────────────

    /** A draw from Beta(a, b) (a, b at least 1). */
    private static double beta(final double a, final double b, final ThreadLocalRandom random) {
        final double x = gamma(a, random), y = gamma(b, random);
        return x / (x + y);
    }

    /** A draw from Gamma(shape, 1), shape at least 1 (Marsaglia and Tsang's method). */
    private static double gamma(final double shape, final ThreadLocalRandom random) {
        final double d = shape - 1.0 / 3, c = 1 / Math.sqrt(9 * d);
        while (true) {
            double x, v;
            do {
                x = random.nextGaussian();
                v = 1 + c * x;
            } while (v <= 0);
            v = v * v * v;
            final double u = random.nextDouble();
            if (u < 1 - 0.0331 * x * x * x * x || Math.log(u) < 0.5 * x * x + d * (1 - v + Math.log(v)))
                return d * v;
        }
    }
}
