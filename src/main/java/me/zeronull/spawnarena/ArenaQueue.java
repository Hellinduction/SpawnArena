package me.zeronull.spawnarena;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An arena's queues: one per mode (1v1, 2v2). A player can only be in one queue at a time.
 * Synchronized: on Folia players join and leave from their own threads.
 */
public class ArenaQueue {
    public static final Map<Player, ArenaQueue> PLAYER_QUEUE_MAP = new java.util.concurrent.ConcurrentHashMap<>();

    private final Arena arena;
    private final Map<FightMode, List<Player>> queues = new EnumMap<>(FightMode.class);
    /** 1v1: how many matches have started without this player while they were waiting longer */
    private final Map<Player, Integer> skips = new java.util.HashMap<>();
    /** Skipped this many times, a player goes next */
    private static final int MAX_SKIPS = 2;

    public ArenaQueue(final Arena arena) {
        this.arena = arena;
        for (final FightMode mode : FightMode.values())
            this.queues.put(mode, new ArrayList<>());
    }

    public Arena getArena() {
        return this.arena;
    }

    public synchronized void removePlayer(final Player player) {
        for (final List<Player> queue : this.queues.values())
            queue.remove(player);
        this.skips.remove(player);
        PLAYER_QUEUE_MAP.remove(player);
    }

    /** Players waiting across all modes. */
    public synchronized int size() {
        int total = 0;
        for (final List<Player> queue : this.queues.values())
            total += queue.size();
        return total;
    }

    public synchronized int size(final FightMode mode) {
        return this.queues.get(mode).size();
    }

    public synchronized boolean playerInQueue(final Player player) {
        return this.modeOf(player) != null;
    }

    /** Which of this arena's queues the player is in, or null. */
    public synchronized FightMode modeOf(final Player player) {
        for (final Map.Entry<FightMode, List<Player>> entry : this.queues.entrySet())
            if (entry.getValue().contains(player))
                return entry.getKey();
        return null;
    }

    /** Empties every queue and returns who was in them. */
    public synchronized List<Player> clear() {
        final List<Player> removed = new ArrayList<>();
        for (final List<Player> queue : this.queues.values())
            removed.addAll(queue);
        removed.forEach(this::removePlayer);
        return removed;
    }

    public void addPlayerToQueue(final Player player) {
        this.addPlayerToQueue(player, FightMode.ONE_V_ONE);
    }

    public synchronized void addPlayerToQueue(final Player player, final FightMode mode) {
        this.removePlayer(player);
        this.queues.get(mode).add(player);
        PLAYER_QUEUE_MAP.put(player, this);
        this.tryStartFight();
    }

    /** Puts players from a cancelled countdown back in line ahead of everyone else, then tries to start. */
    public synchronized void requeue(final List<Player> players, final FightMode mode) {
        final List<Player> queue = this.queues.get(mode);
        int index = 0;
        for (final Player p : players) {
            this.removePlayer(p);
            if (mode == FightMode.ONE_V_ONE)
                queue.add(p); // 1v1 pairs from the end of the list
            else
                queue.add(index++, p); // 2v2 takes from the front
            PLAYER_QUEUE_MAP.put(p, this);
        }
        this.tryStartFight();
    }

    public synchronized void tryStartFight() {
        if (SpawnArena.settings != null && !SpawnArena.settings.arenasOpen)
            return;

        // 1v1: the two most recent joiners fight (so friends who queue together face each other), unless
        // someone has been skipped MAX_SKIPS times: then they go next, against the next-longest waiter.
        // The list is in join order, oldest first.
        final List<Player> solo = this.queues.get(FightMode.ONE_V_ONE);
        if (solo.size() >= 2 && this.canStart()) {
            final Player overdue = solo.stream().filter(p -> this.skips.getOrDefault(p, 0) >= MAX_SKIPS).findFirst().orElse(null);
            final Player p1, p2;
            if (overdue != null) {
                p1 = overdue;
                p2 = solo.stream().filter(p -> p != overdue).findFirst().orElseThrow();
            } else {
                p1 = solo.get(solo.size() - 1);
                p2 = solo.get(solo.size() - 2);
            }
            // Everyone who joined before the newer of these two (and isn't one of them) got skipped
            final List<Player> skipped = new ArrayList<>(solo.subList(0, Math.max(solo.indexOf(p1), solo.indexOf(p2))));
            skipped.remove(p1);
            skipped.remove(p2);

            solo.remove(p1);
            solo.remove(p2);
            this.skips.remove(p1);
            this.skips.remove(p2);
            PLAYER_QUEUE_MAP.remove(p1);
            PLAYER_QUEUE_MAP.remove(p2);
            for (final Player waiting : skipped)
                this.skips.merge(waiting, 1, Integer::sum);

            new Fight().initiateFight(p1, p2, this.arena);
        }

        // 2v2: the four who have waited longest, split into teams
        final List<Player> duo = this.queues.get(FightMode.TWO_V_TWO);
        if (duo.size() >= 4 && this.canStart()) {
            final List<Player> four = new ArrayList<>(duo.subList(0, 4));
            for (final Player p : four) {
                duo.remove(p);
                PLAYER_QUEUE_MAP.remove(p);
            }
            final List<List<Player>> teams = TeamPicker.split(four);
            new Fight().initiateTeamFight(teams.get(0), teams.get(1), this.arena);
        }
    }

    private boolean canStart() {
        return !this.arena.isResetting() && (this.arena.getFights().isEmpty() || this.arena.getArenaMode() == ArenaMode.MULTI);
    }
}
