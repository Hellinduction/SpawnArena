package me.zeronull.spawnarena;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * A practice sumo match against a {@link SumoBot}: the usual countdown, kit, teleport in and everything given back
 * after, with the bot at the other spawn point. It counts for nothing (no wins, losses, streaks, Elo, rewards, bets,
 * broadcast or fight time); only a "bots beaten" count per difficulty is kept.
 */
public final class BotFight extends Fight implements SumoBot.BotMatch {
    private final BotDifficulty level;
    private final Player player;
    private volatile SumoBot bot;

    private BotFight(final Player player, final BotDifficulty level) {
        this.player = player;
        this.level = level;
    }

    public BotDifficulty getLevel() {
        return this.level;
    }

    // ── As its bot sees it ──────────────────────────────────────────────────

    @Override
    public FightState state() {
        return this.getState();
    }

    @Override
    public boolean inPlay() {
        return this.player.isOnline() && this.isFighter(this.player);
    }

    @Override
    public void out(final SumoBot bot) {
        this.botOut();
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
    public void tell(final net.kyori.adventure.text.Component message) {
        Threads.entity(this.player, () -> {
            if (!this.player.isOnline())
                return;
            this.player.sendMessage(message);
            this.player.playSound(this.player, org.bukkit.Sound.ENTITY_WITHER_SPAWN, 0.4f, 1.6f);
        });
    }

    /** Where its bot is now, or null if it isn't in yet (or any more). */
    public Location botLocation() {
        final SumoBot b = this.bot;
        final org.bukkit.entity.Mannequin body = b == null ? null : b.getBody();
        return body == null ? null : body.getLocation();
    }

    /** Whether bot matches can be played in this arena: a sumo arena (touching water loses) that isn't a free-for-all. */
    public static boolean available(final Arena arena) {
        return arena != null && arena.isDeathOnTouchLiquid() && !arena.isFfa() && arena.isSetUp();
    }

    /** Starts a practice match against a bot; returns why it couldn't, or null. */
    public static String start(final Player player, final Arena arena, final BotDifficulty level) {
        if (!available(arena))
            return "Bot matches are only in sumo arenas.";
        final ArenaQueue queued = ArenaQueue.PLAYER_QUEUE_MAP.get(player);
        if (queued != null && queued.playerInQueue(player))
            return "Leave the " + queued.getArena().getArenaName() + " queue first.";
        final String problem = me.zeronull.spawnarena.commands.impl.JoinArenaQueueCommand.cantJoin(player, arena);
        if (problem != null)
            return problem;
        final Arena where = arena.arenaForNewFight();
        if (where == null)
            return "That arena is busy right now. Try again in a moment.";
        BotMemory.load(player); // (what bots remember of them: on their thread, before the bot's made)
        final BotFight fight = new BotFight(player, level);
        fight.asDuel(); // nothing at stake: no requeue, no bets, no lag-out used up
        fight.initiateTeamFight(List.of(player), List.of(), where);
        player.sendMessage(MiniMessage.miniMessage().deserialize(
                "<gray>Practice match against " + (level == BotDifficulty.EASY ? "an " : "a ") + level.coloured() + " <gray>bot. It doesn't count for stats."));
        return null;
    }

    /** No countdown: it starts as soon as it's picked. */
    @Override
    protected int countdown() {
        return 0;
    }

    @Override
    protected void pickVariant() {
        // Combo: fists and no hit delay; otherwise the arena's own kit (the Slap Fish), whatever random sumo would pick
        this.variant = this.level.combo ? SumoVariant.COMBO : null;
    }

    /** The bot comes in at the other spawn point, facing them. */
    @Override
    protected void onLive() {
        final Arena arena = this.getArena();
        final Location a = arena.spawnPoint1, b = arena.spawnPoint2;
        if (a == null || b == null) {
            this.endFight();
            return;
        }
        final Location mine = this.getSpawnOf(this.player);
        final Location other = mine != null && mine.getBlockX() == a.getBlockX() && mine.getBlockZ() == a.getBlockZ() ? b : a;
        final Location centre = a.clone().add(b).multiply(0.5);
        final SumoBot made = new SumoBot(this, this.player, this.level, centre);
        this.bot = made;
        Threads.location(other, () -> {
            if (this.getState() == FightState.IN_FIGHT)
                made.spawn(other);
        });
    }

    /** The player is out (in the water, out of the arena...): the bot won. */
    @Override
    protected void finish(final List<Player> winners, final List<Player> losers) {
        this.result(false);
    }

    /** The bot went in the water or off the edge: the player won. */
    void botOut() {
        synchronized (this) {
            if (this.fightState != FightState.IN_FIGHT)
                return;
            this.fightState = FightState.ENDING;
        }
        this.result(true);
    }

    private void result(final boolean won) {
        final Player p = this.player;
        final SumoBot bot = this.bot;
        if (bot != null)
            bot.remember(!won);
        final String level = this.level.coloured();
        final net.kyori.adventure.title.Title.Times times = net.kyori.adventure.title.Title.Times.times(
                java.time.Duration.ofMillis(250), java.time.Duration.ofMillis(2500), java.time.Duration.ofMillis(750));
        if (p.isOnline())
            p.showTitle(net.kyori.adventure.title.Title.title(
                    MiniMessage.miniMessage().deserialize(won ? "<green><bold>Victory!" : "<red><bold>You Lose!"),
                    MiniMessage.miniMessage().deserialize(won ? "<gray>You beat the " + level + " <gray>bot" : "<gray>The " + level + " <gray>bot beat you"),
                    times));
        if (won && SpawnArena.statsManager != null) {
            final UUID id = p.getUniqueId();
            final String arena = this.getArena().getArenaName(), key = this.level.name();
            Threads.async(() -> {
                final int beaten = SpawnArena.statsManager.addBotWin(id, arena, key);
                if (p.isOnline())
                    p.sendMessage(MiniMessage.miniMessage().deserialize("<green>You beat the " + level + " <green>bot! <gray>Bots beaten on "
                            + level + "<gray>: <white>" + beaten));
            });
        } else if (p.isOnline())
            p.sendMessage(ChatColor.GRAY + "The bot got you that time. Try again from the menu!");
        this.endFight();
    }

    @Override
    public void endFight() {
        final SumoBot b = this.bot;
        this.bot = null;
        if (b != null)
            b.remove();
        super.endFight();
    }

    /** Practice: no fight time counted. */
    @Override
    protected void countFightTime(final UUID id) {
        this.fightingSince.remove(id);
    }
}
