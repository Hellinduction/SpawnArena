package me.zeronull.spawnarena;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Two sumo bots fighting each other, to watch (/arenabotfight): they come in at the arena's spawn points and fight as
 * they would a player, until one goes in the water or lava (the other wins) or three minutes are up (a draw). Anyone
 * near enough sees it, except fighters of matches in the arena, who neither see nor hear it. It counts for nothing.
 * In a SINGLE arena no match starts while it's on.
 */
public final class BotShow implements SumoBot.BotMatch {
    private static final List<BotShow> RUNNING = new CopyOnWriteArrayList<>();
    /** The longest a show match goes on before it's a draw (three minutes) */
    private static final long MAX_TICKS = 20L * 180;
    /** How far away people hear how it went */
    private static final double TELL_DISTANCE = 64;

    private final Arena arena;
    private final Location centre;
    private final SumoBot a, b;
    private volatile FightState state = FightState.IN_FIGHT;

    private BotShow(final Arena arena, final Location centre, final BotDifficulty one, final BotDifficulty two) {
        this.arena = arena;
        this.centre = centre;
        this.a = new SumoBot(this, one, centre);
        this.b = new SumoBot(this, two, centre);
    }

    /** Starts a show match between bots of these levels in this arena; returns why it couldn't, or null. */
    public static String start(final Arena arena, final BotDifficulty one, final BotDifficulty two) {
        if (!BotFight.available(arena))
            return "Bots only fight in sumo arenas (set up, not a free-for-all).";
        if (!arena.isOpen())
            return "That arena is closed right now.";
        if (arena.getArenaMode() != ArenaMode.MULTI && (!arena.getFights().isEmpty() || runningIn(arena)))
            return "That arena is busy right now. Try again when it's free.";
        final Location p1 = arena.spawnPoint1, p2 = arena.spawnPoint2;
        if (p1 == null || p2 == null)
            return "That arena has no spawn points.";
        final BotShow show = new BotShow(arena, p1.clone().add(p2).multiply(0.5), one, two);
        SumoBot.fights(show.a, show.b, p1, p2);
        RUNNING.add(show);
        Threads.location(p1, () -> {
            if (show.state == FightState.IN_FIGHT)
                show.a.spawn(p1);
        });
        Threads.location(p2, () -> {
            if (show.state == FightState.IN_FIGHT)
                show.b.spawn(p2);
        });
        Threads.globalLater(() -> {
            if (show.state == FightState.IN_FIGHT) {
                show.tell(MiniMessage.miniMessage().deserialize("<gray>Time's up: the " + one.coloured() + " <gray>and "
                        + two.coloured() + " <gray>bots drew."));
                show.end();
            }
        }, MAX_TICKS);
        show.tell(MiniMessage.miniMessage().deserialize("<gold>Bot fight! <gray>" + one.coloured() + " <gray>vs " + two.coloured()
                + " <gray>in the " + Arena.displayName(arena.getArenaName()) + " arena."));
        return null;
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

    /** Where its two bots are now (for working out whose a sound is). */
    public List<Location> botLocations() {
        final List<Location> out = new ArrayList<>(2);
        for (final SumoBot bot : List.of(this.a, this.b)) {
            final org.bukkit.entity.Mannequin body = bot.getBody();
            if (body != null)
                out.add(body.getLocation());
        }
        return out;
    }

    private void end() {
        this.state = FightState.OVER;
        RUNNING.remove(this);
        this.a.remove();
        this.b.remove();
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

    @Override
    public void out(final SumoBot bot) {
        synchronized (this) {
            if (this.state != FightState.IN_FIGHT)
                return;
            this.state = FightState.ENDING;
        }
        final SumoBot winner = bot == this.a ? this.b : this.a;
        this.tell(MiniMessage.miniMessage().deserialize("<gold>Bot fight: <gray>the " + winner.getLevel().coloured()
                + " <gray>bot beat the " + bot.getLevel().coloured() + " <gray>bot!"));
        this.end();
    }

    @Override
    public Arena arena() {
        return this.arena;
    }

    @Override
    public Fight fight() {
        return null;
    }

    /** To everyone near enough to be watching. */
    @Override
    public void tell(final Component message) {
        for (final Player p : this.centre.getWorld().getPlayers())
            Threads.entity(p, () -> {
                if (p.isOnline() && p.getWorld().equals(this.centre.getWorld()) && p.getLocation().distance(this.centre) <= TELL_DISTANCE) {
                    p.sendMessage(message);
                    p.playSound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.4f);
                }
            });
    }
}
