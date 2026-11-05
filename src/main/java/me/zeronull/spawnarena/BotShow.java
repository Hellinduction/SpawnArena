package me.zeronull.spawnarena;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Sumo bots fighting each other, to watch (/arenabotfight): a 1v1, or a 2v2 with teammates side by side at each spawn
 * point. Each bot goes for the nearest bot of the other team and fights it as it would a player; one that goes in the
 * water or lava is out, and a team with none left loses. Three minutes at most (a draw). Anyone near enough sees it,
 * each team outlined in its own colour, except fighters of matches in the arena, who neither see nor hear it. It
 * counts for nothing. In a SINGLE arena no match starts while it's on.
 */
public final class BotShow implements SumoBot.BotMatch {
    private static final List<BotShow> RUNNING = new CopyOnWriteArrayList<>();
    /** The longest a show match goes on before it's a draw (three minutes) */
    private static final long MAX_TICKS = 20L * 180;
    /** How far apart teammates come in, either side of the spawn point */
    private static final double TEAMMATE_GAP = 0.9;

    private final Arena arena;
    private final Location centre;
    private final List<SumoBot> teamA = new ArrayList<>(), teamB = new ArrayList<>();
    /** Bot -> where it came in (a target's whereabouts until its body's there) */
    private final java.util.Map<SumoBot, Location> spawns = new java.util.LinkedHashMap<>();
    /** Bots still in it */
    private final java.util.Set<SumoBot> in = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile FightState state = FightState.IN_FIGHT;
    private volatile ScheduledTask targeting;
    /** Its outline colours, picked as a fight's are (one, or one per team in a 2v2); null when fights don't glow */
    private volatile java.util.List<NamedTextColor> colours;
    /**
     * Master against each bot level: [show matches lost to it in a row, locked-in matches left against it]. Lose to
     * the same level too often and it locks in against it for the next few (as against a player: see BotMemory).
     */
    private static final java.util.Map<BotDifficulty, int[]> RIVALS = new java.util.concurrent.ConcurrentHashMap<>();
    /** The skins its bots have borrowed: never two the same */
    private final java.util.Set<String> skins = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Who started it (/arenabotfight): the only one told how it goes */
    private final org.bukkit.command.CommandSender starter;

    private BotShow(final Arena arena, final Location centre, final org.bukkit.command.CommandSender starter) {
        this.starter = starter;
        this.arena = arena;
        this.centre = centre;
    }

    /**
     * Starts a show match in this arena: the first half of {@code levels} against the second (1v1 or 2v2); returns why
     * it couldn't, or null.
     */
    public static String start(final Arena arena, final List<BotDifficulty> levels, final org.bukkit.command.CommandSender starter) {
        if (levels.size() != 2 && levels.size() != 4)
            return "Bot fights are 1v1 (2 bots) or 2v2 (4 bots).";
        if (!BotFight.available(arena))
            return "Bots only fight in sumo arenas (set up, not a free-for-all).";
        if (!arena.isOpen())
            return "That arena is closed right now.";
        if (arena.getArenaMode() != ArenaMode.MULTI && (!arena.getFights().isEmpty() || runningIn(arena)))
            return "That arena is busy right now. Try again when it's free.";
        final Location p1 = arena.spawnPoint1, p2 = arena.spawnPoint2;
        if (p1 == null || p2 == null)
            return "That arena has no spawn points.";
        final BotShow show = new BotShow(arena, p1.clone().add(p2).multiply(0.5), starter);
        final int perTeam = levels.size() / 2;
        show.colours = FightGlow.showColours(show, perTeam > 1);
        // Teammates side by side, across the line between the spawn points
        final Vector across = p2.toVector().subtract(p1.toVector()).setY(0);
        final Vector side = across.lengthSquared() < 1.0E-4 ? new Vector(1, 0, 0) : new Vector(-across.getZ(), 0, across.getX()).normalize();
        for (int i = 0; i < levels.size(); i++) {
            final boolean first = i < perTeam;
            final SumoBot bot = new SumoBot(show, levels.get(i), show.centre);
            (first ? show.teamA : show.teamB).add(bot);
            final double offset = perTeam == 1 ? 0 : (i % perTeam == 0 ? -TEAMMATE_GAP : TEAMMATE_GAP);
            show.spawns.put(bot, (first ? p1 : p2).clone().add(side.clone().multiply(offset)));
            show.in.add(bot);
        }
        // Master locks in against a level it's kept losing to
        for (final SumoBot bot : show.spawns.keySet())
            for (final SumoBot foe : show.teamA.contains(bot) ? show.teamB : show.teamA) {
                final int[] rival = RIVALS.get(foe.getLevel());
                if (bot.getLevel() == BotDifficulty.MASTER && rival != null && rival[1] > 0)
                    bot.lockIn();
            }
        show.retarget();
        RUNNING.add(show);
        for (final java.util.Map.Entry<SumoBot, Location> e : show.spawns.entrySet()) {
            final SumoBot bot = e.getKey();
            final Location at = e.getValue();
            Threads.location(at, () -> {
                if (show.state == FightState.IN_FIGHT)
                    bot.spawn(at);
            });
        }
        // Each goes for the nearest of the other team, looked at again twice a second
        show.targeting = Threads.globalTimer(t -> {
            if (show.state != FightState.IN_FIGHT)
                t.cancel();
            else
                show.retarget();
        }, 10L, 10L);
        Threads.globalLater(() -> {
            if (show.state == FightState.IN_FIGHT) {
                show.tell(MiniMessage.miniMessage().deserialize("<gray>Time's up: " + names(show.teamA) + " <gray>and "
                        + names(show.teamB) + " <gray>drew."));
                show.end();
            }
        }, MAX_TICKS);
        show.tell(MiniMessage.miniMessage().deserialize("<gold>Bot fight! <gray>" + names(show.teamA) + " <gray>vs "
                + names(show.teamB) + " <gray>in the " + Arena.displayName(arena.getArenaName()) + " arena."));
        return null;
    }

    /** "Hard" or "Hard & Easy", coloured, for messages. */
    private static String names(final List<SumoBot> team) {
        final List<String> out = new ArrayList<>();
        for (final SumoBot bot : team)
            out.add(bot.getLevel().coloured());
        return String.join(" <gray>& ", out);
    }

    /** Every bot still in goes for the nearest bot still in on the other team. */
    private void retarget() {
        for (final SumoBot bot : this.in) {
            final List<SumoBot> foes = this.teamA.contains(bot) ? this.teamB : this.teamA;
            final Location here = this.whereIs(bot);
            SumoBot best = null;
            double bestDistance = Double.MAX_VALUE;
            for (final SumoBot foe : foes) {
                if (!this.in.contains(foe))
                    continue;
                final Location there = this.whereIs(foe);
                final double d = here.getWorld() == there.getWorld() ? here.distanceSquared(there) : Double.MAX_VALUE / 2;
                if (d < bestDistance) {
                    best = foe;
                    bestDistance = d;
                }
            }
            if (best != null)
                bot.fight(best, this.spawns.get(best));
        }
    }

    private Location whereIs(final SumoBot bot) {
        final org.bukkit.entity.Mannequin body = bot.getBody();
        return body != null ? body.getLocation() : this.spawns.get(bot).clone();
    }

    /** The world an arena is in (its spawn points'), or null. */
    public static org.bukkit.World worldOf(final Arena arena) {
        return arena.spawnPoint1 == null ? null : arena.spawnPoint1.getWorld();
    }

    /** Every show match on now. */
    public static List<BotShow> running() {
        return RUNNING;
    }

    public static boolean runningIn(final Arena arena) {
        for (final BotShow show : RUNNING)
            if (show.arena.getArenaName().equals(arena.getArenaName()))
                return true;
        return false;
    }

    /** Ends the show matches in this arena (every one with null); returns how many. */
    public static int stop(final Arena arena) {
        int n = 0;
        for (final BotShow show : RUNNING)
            if (arena == null || show.arena.getArenaName().equals(arena.getArenaName())) {
                show.end();
                n++;
            }
        return n;
    }

    /** Where its bots still in are now (for working out whose a sound is). */
    public List<Location> botLocations() {
        final List<Location> out = new ArrayList<>(this.in.size());
        for (final SumoBot bot : this.in) {
            final org.bukkit.entity.Mannequin body = bot.getBody();
            if (body != null)
                out.add(body.getLocation());
        }
        return out;
    }

    private void end() {
        this.state = FightState.OVER;
        RUNNING.remove(this);
        final ScheduledTask t = this.targeting;
        if (t != null)
            t.cancel();
        for (final SumoBot bot : this.spawns.keySet())
            bot.remove();
        this.in.clear();
        FightGlow.endShow(this);
        // The arena's free again: anyone who queued meanwhile gets their match
        if (this.arena.queue != null)
            Threads.global(() -> this.arena.queue.tryStartFight());
    }

    // ── As its bots see it ──────────────────────────────────────────────────

    @Override
    public FightState state() {
        return this.state;
    }

    @Override
    public boolean inPlay() {
        return this.state == FightState.IN_FIGHT;
    }

    /** A bot's in the water or lava: it's out; a team with none left loses. */
    @Override
    public void out(final SumoBot bot) {
        final List<SumoBot> team, other;
        synchronized (this) {
            if (this.state != FightState.IN_FIGHT || !this.in.remove(bot))
                return;
            team = this.teamA.contains(bot) ? this.teamA : this.teamB;
            other = team == this.teamA ? this.teamB : this.teamA;
            for (final SumoBot mate : team)
                if (this.in.contains(mate)) {
                    bot.remove();
                    this.retarget();
                    return;
                }
            this.state = FightState.ENDING;
        }
        this.rivalry(other, team);
        final boolean solo = this.teamA.size() == 1;
        this.tell(MiniMessage.miniMessage().deserialize("<gold>Bot fight: <gray>" + (solo ? "the " : "") + names(other)
                + " <gray>" + (solo ? "bot beat the " : "beat ") + names(team) + (solo ? " <gray>bot!" : "<gray>!")));
        this.end();
    }

    /** The result, for Master's record against each bot level it played (see RIVALS). */
    private void rivalry(final List<SumoBot> winners, final List<SumoBot> losers) {
        for (final List<SumoBot> side : List.of(winners, losers))
            for (final SumoBot bot : side) {
                if (bot.getLevel() != BotDifficulty.MASTER)
                    continue;
                for (final SumoBot foe : side == winners ? losers : winners) {
                    final int[] rival = RIVALS.computeIfAbsent(foe.getLevel(), l -> new int[2]);
                    synchronized (rival) {
                        if (rival[1] > 0)
                            rival[1]--;
                        else if (side == winners)
                            rival[0] = 0;
                        else if (++rival[0] >= BotMemory.LOSSES_TO_LOCK) {
                            rival[0] = 0;
                            rival[1] = BotMemory.LOCKED_MATCHES;
                        }
                    }
                }
            }
    }

    @Override
    public java.util.Set<String> skinsTaken() {
        return this.skins;
    }

    @Override
    public NamedTextColor teamColour(final SumoBot bot) {
        final java.util.List<NamedTextColor> c = this.colours;
        if (c == null || c.isEmpty())
            return null;
        return c.size() > 1 && !this.teamA.contains(bot) ? c.get(1) : c.get(0);
    }

    @Override
    public Arena arena() {
        return this.arena;
    }

    @Override
    public Fight fight() {
        return null;
    }

    /** To whoever started it (/arenabotfight), and nobody else. */
    @Override
    public void tell(final Component message) {
        if (!(this.starter instanceof Player p)) {
            this.starter.sendMessage(message);
            return;
        }
        Threads.entity(p, () -> {
            if (p.isOnline()) {
                p.sendMessage(message);
                p.playSound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.4f);
            }
        });
    }
}
