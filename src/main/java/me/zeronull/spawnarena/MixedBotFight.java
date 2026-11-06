package me.zeronull.spawnarena;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A sumo match with players and bots in it (/arenabotfight with player names among the bots): e.g. a player against a
 * bot, two players against two bots, or a player and a bot against two bots. Its players get the usual kit, teleport in
 * and everything back after; each bot comes in beside its team's spawn and goes for the nearest player or bot of the
 * other team. Anyone who goes in the water is out, and a team loses once none of its players or bots are left. It
 * counts for nothing (no wins, losses, streaks, Elo, rewards, bets or fight time), like a practice bot match.
 */
public final class MixedBotFight extends Fight implements SumoBot.BotMatch {
    private final List<BotDifficulty> levelsA, levelsB;
    private final List<SumoBot> botsA = new CopyOnWriteArrayList<>(), botsB = new CopyOnWriteArrayList<>();
    /** Bots still in it */
    private final Set<SumoBot> in = ConcurrentHashMap.newKeySet();
    /** Bot -> where it came in (a target's whereabouts until its body's there) */
    private final java.util.Map<SumoBot, Location> spawns = new ConcurrentHashMap<>();
    /** The skins its bots have borrowed: never two the same */
    private final Set<String> skins = ConcurrentHashMap.newKeySet();
    /** Who started it: told how it goes as well as the players (if they aren't one) */
    private final CommandSender starter;
    private volatile ScheduledTask targeting;

    private MixedBotFight(final List<BotDifficulty> levelsA, final List<BotDifficulty> levelsB, final CommandSender starter) {
        this.levelsA = List.copyOf(levelsA);
        this.levelsB = List.copyOf(levelsB);
        this.starter = starter;
    }

    /**
     * Starts one in this arena: {@code playersA} and {@code botsA} against {@code playersB} and {@code botsB} (the same
     * number a side, one or two). Returns why it couldn't, or null.
     */
    public static String start(final Arena arena, final List<Player> playersA, final List<BotDifficulty> botsA,
                               final List<Player> playersB, final List<BotDifficulty> botsB, final CommandSender starter) {
        if (!BotFight.available(arena))
            return "Bots only fight in sumo arenas (set up, not a free-for-all).";
        if (botsA.isEmpty() && botsB.isEmpty())
            return "No bots in it: for players only, use /setupfight.";
        final List<Player> everyone = new ArrayList<>(playersA);
        everyone.addAll(playersB);
        if (everyone.isEmpty())
            return "No players in it.";
        if (new java.util.HashSet<>(everyone).size() != everyone.size())
            return "Someone's in it twice.";
        for (final Player p : everyone) {
            if (!p.isOnline())
                return p.getName() + " isn't online.";
            final ArenaQueue queued = ArenaQueue.PLAYER_QUEUE_MAP.get(p);
            if (queued != null && queued.playerInQueue(p))
                return p.getName() + " is in the " + queued.getArena().getArenaName() + " queue: they need to leave it first.";
            final String problem = me.zeronull.spawnarena.commands.impl.JoinArenaQueueCommand.cantJoin(p, arena);
            if (problem != null)
                return p.getName() + ": " + problem;
        }
        final Arena where = arena.arenaForNewFight();
        if (where == null || BotShow.runningIn(arena))
            return "That arena is busy right now. Try again when it's free.";
        final MixedBotFight fight = new MixedBotFight(botsA, botsB, starter);
        fight.asDuel(); // nothing at stake: no requeue, no bets, no lag-out used up
        fight.initiateTeamFight(playersA, playersB, where);
        final Component line = MiniMessage.miniMessage().deserialize("<gold>Bot fight! <gray><a> <gray>vs <b> <gray>in the "
                        + Arena.displayName(arena.getArenaName()) + " arena. It doesn't count for stats.",
                Placeholder.parsed("a", names(playersA, botsA)), Placeholder.parsed("b", names(playersB, botsB)));
        for (final Player p : everyone)
            p.sendMessage(line);
        if (!(starter instanceof Player s) || !everyone.contains(s))
            starter.sendMessage(line);
        return null;
    }

    /** "noulez & Hard", the bots in their level's colour (MiniMessage). */
    private static String names(final List<Player> players, final List<BotDifficulty> bots) {
        final List<String> out = new ArrayList<>();
        for (final Player p : players)
            out.add("<white>" + MiniMessage.miniMessage().escapeTags(p.getName()));
        for (final BotDifficulty level : bots)
            out.add(level.coloured() + " <gray>bot");
        return String.join(" <gray>& ", out);
    }

    private List<Player> playersOf(final boolean firstTeam) {
        final List<Player> out = new ArrayList<>();
        for (final Player p : this.getMembers())
            if (this.onFirstTeam(p) == firstTeam)
                out.add(p);
        return out;
    }

    // ── Fight ───────────────────────────────────────────────────────────────

    @Override
    protected int botsOnTeam(final boolean firstTeam) {
        return (firstTeam ? this.levelsA : this.levelsB).size();
    }

    @Override
    public boolean withBots() {
        return true;
    }

    @Override
    public List<Location> botLocations() {
        final List<Location> out = new ArrayList<>(this.in.size());
        for (final SumoBot bot : this.in) {
            final org.bukkit.entity.Mannequin body = bot.getBody();
            if (body != null)
                out.add(body.getLocation());
        }
        return out;
    }

    /** Combo bots only: combo sumo (fists, no hit delay); otherwise the arena's own kit, no random variant. */
    @Override
    protected void pickVariant() {
        boolean allCombo = true;
        for (final BotDifficulty level : this.levelsA)
            allCombo &= level.combo;
        for (final BotDifficulty level : this.levelsB)
            allCombo &= level.combo;
        this.variant = allCombo ? SumoVariant.COMBO : null;
    }

    /** Everyone's in: the bots come in beside their team's spawn, each going for the nearest of the other team. */
    @Override
    protected void onLive() {
        final Location a = this.teamSpawn(true), b = this.teamSpawn(false);
        if (a == null || b == null) {
            this.endFight();
            return;
        }
        final Location centre = a.clone().add(b).multiply(0.5);
        for (final boolean first : new boolean[]{true, false}) {
            final Location spawn = first ? a : b;
            for (final BotDifficulty level : first ? this.levelsA : this.levelsB) {
                final SumoBot bot = new SumoBot(this, level, centre);
                (first ? this.botsA : this.botsB).add(bot);
                this.spawns.put(bot, spawn.clone());
                this.in.add(bot);
            }
        }
        this.retarget(); // (each knows who it's facing as it comes in)
        for (final boolean first : new boolean[]{true, false}) {
            final Location spawn = first ? a : b;
            final List<SumoBot> team = first ? this.botsA : this.botsB;
            final int players = this.playersOf(first).size();
            Threads.location(spawn, () -> {
                // Beside the spawn: the other side from their player, or either side of it for two bots
                final Location[] sides = players + team.size() == 2 ? this.sideSpots(spawn) : null;
                for (int i = 0; i < team.size(); i++) {
                    final SumoBot bot = team.get(i);
                    final Location at = sides == null ? spawn.clone() : sides[players == 1 ? 1 : i].clone();
                    this.spawns.put(bot, at);
                    if (this.getState() == FightState.IN_FIGHT)
                        bot.spawn(at);
                }
            });
        }
        this.targeting = Threads.globalTimer(t -> {
            if (this.getState() != FightState.IN_FIGHT)
                t.cancel();
            else
                this.retarget();
        }, 10L, 10L);
    }

    /** Every bot still in goes for the nearest player still fighting or bot still in on the other team. */
    private void retarget() {
        for (final SumoBot bot : this.in) {
            final boolean mine = this.botsA.contains(bot);
            final Location here = this.whereIs(bot);
            Player bestPlayer = null;
            SumoBot bestBot = null;
            double best = Double.MAX_VALUE;
            for (final Player p : this.getFighters()) {
                if (this.onFirstTeam(p) == mine || !p.isOnline())
                    continue;
                final Location there = p.getLocation();
                final double d = here.getWorld() == there.getWorld() ? here.distanceSquared(there) : Double.MAX_VALUE / 2;
                if (d < best) {
                    best = d;
                    bestPlayer = p;
                }
            }
            for (final SumoBot foe : mine ? this.botsB : this.botsA) {
                if (!this.in.contains(foe))
                    continue;
                final Location there = this.whereIs(foe);
                final double d = here.getWorld() == there.getWorld() ? here.distanceSquared(there) : Double.MAX_VALUE / 2;
                if (d < best) {
                    best = d;
                    bestBot = foe;
                    bestPlayer = null;
                }
            }
            if (bestBot != null)
                bot.fight(bestBot, this.spawns.get(bestBot));
            else if (bestPlayer != null)
                bot.fight(bestPlayer);
        }
    }

    private Location whereIs(final SumoBot bot) {
        final org.bukkit.entity.Mannequin body = bot.getBody();
        return body != null ? body.getLocation() : this.spawns.get(bot).clone();
    }

    /** A bot on this team is still in: it isn't beaten yet, whoever of its players is out. */
    @Override
    protected boolean othersStillIn(final List<Player> team) {
        if (team.isEmpty())
            return false;
        final boolean first = this.onFirstTeam(team.get(0));
        for (final SumoBot bot : first ? this.botsA : this.botsB)
            if (this.in.contains(bot))
                return true;
        return false;
    }

    /** A team's last player is out, and none of its bots are left: the other team won. */
    @Override
    protected void finish(final List<Player> winners, final List<Player> losers) {
        final boolean firstWon = !losers.isEmpty() ? !this.onFirstTeam(losers.get(0)) : !winners.isEmpty() && this.onFirstTeam(winners.get(0));
        this.result(firstWon);
    }

    private void result(final boolean firstWon) {
        final List<Player> won = this.playersOf(firstWon), lost = this.playersOf(!firstWon);
        final String winners = names(won, firstWon ? this.levelsA : this.levelsB);
        final String losers = names(lost, firstWon ? this.levelsB : this.levelsA);
        this.tell(MiniMessage.miniMessage().deserialize("<gold>Bot fight: " + winners + " <gray>beat " + losers + "<gray>!"));
        if (!(this.starter instanceof Player s) || !this.getMembers().contains(s))
            this.starter.sendMessage(MiniMessage.miniMessage().deserialize("<gold>Bot fight: " + winners + " <gray>beat " + losers + "<gray>!"));
        final net.kyori.adventure.title.Title.Times times = net.kyori.adventure.title.Title.Times.times(
                java.time.Duration.ofMillis(250), java.time.Duration.ofMillis(2500), java.time.Duration.ofMillis(750));
        for (final Player p : won)
            if (p.isOnline())
                p.showTitle(net.kyori.adventure.title.Title.title(MiniMessage.miniMessage().deserialize("<green><bold>Victory!"),
                        MiniMessage.miniMessage().deserialize("<gray>You beat " + losers), times));
        for (final Player p : lost)
            if (p.isOnline())
                p.showTitle(net.kyori.adventure.title.Title.title(MiniMessage.miniMessage().deserialize("<red><bold>You Lose!"),
                        MiniMessage.miniMessage().deserialize("<gray>Beaten by " + winners), times));
        this.endFight();
    }

    @Override
    public void endFight() {
        final ScheduledTask t = this.targeting;
        if (t != null)
            t.cancel();
        for (final SumoBot bot : this.spawns.keySet())
            bot.remove();
        this.in.clear();
        super.endFight();
    }

    /** Practice: no fight time counted. */
    @Override
    protected void countFightTime(final UUID id) {
        this.fightingSince.remove(id);
    }

    // ── As its bots see it ──────────────────────────────────────────────────

    @Override
    public FightState state() {
        return this.getState();
    }

    @Override
    public boolean inPlay() {
        return this.getState() == FightState.IN_FIGHT;
    }

    /** A bot's in the water or lava: it's out; its team loses if none of its players or bots are left. */
    @Override
    public void out(final SumoBot bot) {
        final boolean first;
        synchronized (this) {
            if (this.fightState != FightState.IN_FIGHT || !this.in.remove(bot))
                return;
            first = this.botsA.contains(bot);
            boolean left = false;
            for (final Player p : this.getFighters())
                left |= this.onFirstTeam(p) == first;
            for (final SumoBot mate : first ? this.botsA : this.botsB)
                left |= this.in.contains(mate);
            if (left) {
                bot.remove();
                this.retarget();
                for (final Player p : this.getFighters())
                    p.sendMessage(MiniMessage.miniMessage().deserialize("<gray>The " + bot.getLevel().coloured() + " <gray>bot is out!"));
                return;
            }
            this.fightState = FightState.ENDING;
        }
        this.result(!first);
    }

    @Override
    public Arena arena() {
        return this.getArena();
    }

    @Override
    public Fight fight() {
        return this;
    }

    @Override
    public Set<String> skinsTaken() {
        return this.skins;
    }

    /** A player on the other team, still fighting. */
    @Override
    public boolean canHit(final Player player, final SumoBot bot) {
        return this.getState() == FightState.IN_FIGHT && this.isFighter(player) && this.onFirstTeam(player) != this.botsA.contains(bot);
    }

    /** Outlined as its team's players are. */
    @Override
    public NamedTextColor teamColour(final SumoBot bot) {
        return FightGlow.teamOutline(this, this.botsA.contains(bot));
    }

    /** As its team's players are to them: green on their side and red on the other (if sides are shown), to fighters. */
    @Override
    public NamedTextColor colourFor(final SumoBot bot, final Player viewer) {
        if (!FightGlow.showsSides() || !this.getMembers().contains(viewer))
            return null;
        return this.onFirstTeam(viewer) == this.botsA.contains(bot) ? NamedTextColor.GREEN : NamedTextColor.RED;
    }

    /** To everyone in it (a rage message, the result). */
    @Override
    public void tell(final Component message) {
        for (final Player p : this.getMembers())
            Threads.entity(p, () -> {
                if (p.isOnline()) {
                    p.sendMessage(message);
                    p.playSound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.4f);
                }
            });
    }
}
