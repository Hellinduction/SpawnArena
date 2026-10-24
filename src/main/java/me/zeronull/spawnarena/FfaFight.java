package me.zeronull.spawnarena;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.zeronull.spawnarena.events.ArenaProjectileDupeFix;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A free-for-all session: the one running "fight" in an FFA arena, which players join straight away with
 * /joinarenaqueue and leave by dying or /leavearena (out of combat only). Everyone is everyone's opponent, so all
 * the usual fight handling (deaths turned into a way out, keeping inside the arena, crystal PvP, dupe fixes...)
 * applies as it is. Each player's own things are saved when they join and given back, as they were, when they're
 * out. Kills, deaths and kill streaks are counted apart from the arena's normal stats; nothing else is.
 *
 * <p>Locking: the arena's monitor first, then the session's, never the other way round.
 */
public final class FfaFight extends Fight {
    /** Why someone is out. */
    private enum Out { DIED, QUIT, FLED, LEFT }

    /** Players anywhere in the middle of joining a free-for-all (saved, being sent in, not fighting yet). */
    private static final Set<UUID> JOINING = ConcurrentHashMap.newKeySet();
    private static final NamespacedKey PROTECTED_KEY = new NamespacedKey(SpawnArena.INSTANCE, "ffa_spawn_protection");

    /** Being got ready and sent in: not fighters yet, so nothing treats them as in the arena. */
    private final Set<Player> joining = ConcurrentHashMap.newKeySet();
    /** victim -> attacker -> when they last hurt them: everyone in the combat window gets the kill. */
    private final Map<UUID, Map<UUID, Long>> hitsOn = new ConcurrentHashMap<>();
    /** When each player last hit, or was hit by, another player. */
    private final Map<UUID, Long> lastCombat = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> streaks = new ConcurrentHashMap<>();
    /** Spawn protection, until it runs out or they attack (the value tells one protection from the next). */
    private final Map<UUID, Object> protectedPlayers = new ConcurrentHashMap<>();
    private volatile ScheduledTask ticker;
    /** Crystal free-for-all: when the arena is next put back (crystalFfaResetMinutes), whether that's been warned of. */
    private volatile long nextReset;
    private volatile boolean resetWarned, resetting;

    private FfaFight(final Arena arena) {
        this.arena = arena;
        this.fightState = FightState.IN_FIGHT;
        this.stats().start();
    }

    @Override
    public boolean isFfa() {
        return true;
    }

    // ── Joining ─────────────────────────────────────────────────────────────

    /** The free-for-all running in this arena, or null. */
    public static FfaFight sessionOf(final Arena arena) {
        for (final Fight fight : arena.getFights())
            if (fight instanceof FfaFight ffa && ffa.getState() == FightState.IN_FIGHT)
                return ffa;
        return null;
    }

    /** The free-for-all this player is fighting in, or null. */
    public static FfaFight of(final Player player) {
        final Arena arena = SpawnArena.arenas == null ? null : SpawnArena.arenas.of(player);
        return arena != null && arena.getFight(player) instanceof FfaFight ffa ? ffa : null;
    }

    public static boolean isJoining(final Player player) {
        return JOINING.contains(player.getUniqueId());
    }

    /** Why this player can't join this free-for-all right now, or null if they can. */
    public static String cantJoin(final Player player, final Arena arena) {
        if (SpawnArena.settings != null && !SpawnArena.settings.arenasOpen)
            return "The arenas are closed right now. Try again later!";
        if (!arena.isFfa())
            return "That arena isn't a free-for-all.";
        if (!arena.isSetUp())
            return "That arena has not been fully setup!";
        if (isJoining(player))
            return "You're already on your way in.";
        if (Fight.AWAITING_RESTORE.contains(player.getUniqueId()))
            return "Your things from your last fight are still being given back. Try again in a moment.";
        if (SpawnArena.arenas.hasFighter(player))
            return of(player) != null && arena.equals(SpawnArena.arenas.of(player))
                    ? "You're already in this free-for-all. Use /leavearena to leave (out of combat)."
                    : "You are already in a fight!";
        final ArenaQueue queue = ArenaQueue.PLAYER_QUEUE_MAP.get(player);
        if (queue != null && queue.playerInQueue(player))
            return "You're waiting in the " + queue.getArena().getArenaName() + " queue. Leave it first with /joinarenaqueue "
                    + queue.getArena().getArenaName() + ".";
        if (player.isDead())
            return "You can't join while dead.";
        if (!player.getLocation().getWorld().getName().equalsIgnoreCase(Arena.SPAWN_WORLD))
            return "You must be at spawn to use this command!";
        if (player.getGameMode() == GameMode.SPECTATOR)
            return "You can't join while in spectator mode.";
        if (SpawnArena.settings != null && SpawnArena.settings.queueMaxDistance > 0
                && arena.distanceFrom(player.getLocation()) > SpawnArena.settings.queueMaxDistance)
            return "You are too far from that arena!";
        if (arena.isResetting() || (arena.isCrystalPvp() && CrystalPvp.isCleaning(arena)))
            return "That arena is being reset. Try again in a moment.";
        for (final Fight fight : arena.getFights())
            if (!(fight instanceof FfaFight) || fight.getState() != FightState.IN_FIGHT)
                return fight instanceof FfaFight ? "That arena is being reset. Try again in a moment."
                        : "There's already a fight in that arena. Wait for it to finish, then join!";
        return null;
    }

    /** Sends this player into the arena's free-for-all, starting one if there isn't one. */
    public static void join(final Player player, final Arena arena) {
        final FfaFight session;
        // Under the arena's lock: the free-for-all setting can't change, and a session can't end, part-way through
        synchronized (arena) {
            final String problem = cantJoin(player, arena);
            if (problem != null) {
                player.sendMessage(ChatColor.RED + problem);
                return;
            }
            FfaFight existing = sessionOf(arena);
            if (existing != null) {
                synchronized (existing) {
                    if (existing.getState() == FightState.IN_FIGHT)
                        existing.joining.add(player);
                    else
                        existing = null;
                }
            }
            if (existing == null) {
                existing = new FfaFight(arena);
                existing.joining.add(player);
                arena.addFight(existing);
                existing.begin();
            }
            session = existing;
            JOINING.add(player.getUniqueId());
        }
        DuelManager.cancelSentRequests(player, "joined a free-for-all");
        player.sendMessage(ChatColor.GREEN + "Joining the " + Arena.displayName(arena.getArenaName()) + " free-for-all...");
        session.arenaReady.whenComplete((ready, error) ->
                Threads.entity(player, () -> session.prepare(player, error == null && Boolean.TRUE.equals(ready))));
    }

    /** A new session: clears the arena of onlookers, records a crystal arena, and keeps everyone's action bar up to date. */
    private void begin() {
        Arena.ArenaUtils.kickOutLingeringPlayers(this.arena);
        if (this.arena.isCrystalPvp())
            this.arenaReady = CrystalPvp.takeSnapshot(this);
        this.nextReset = System.currentTimeMillis() + resetMillis();
        this.pickVariant();
        this.ticker = Threads.globalTimer(task -> this.tick(), 20L, 20L);
    }

    /** On the player's thread. */
    private void prepare(final Player player, final boolean arenaReady) {
        if (!arenaReady) {
            this.abortJoin(player, "The arena couldn't be got ready. Try again in a moment.");
            return;
        }
        if (!this.stillJoining(player)) {
            this.abortJoin(player, "That free-for-all just ended. Try joining again.");
            return;
        }
        // Pending duel requests are void once they're in a fight (cleared before the teleport in, so they don't
        // get a "cancelled because you teleported" message)
        DuelManager.clear(player);
        player.setInvulnerable(false);
        this.leaveParkour(player);
        player.closeInventory();

        final CompletableFuture<List<ItemStack>> recalled = recallTridents(player);
        if (recalled.isDone())
            this.sendIn(player, recalled.join());
        else
            recalled.whenComplete((items, error) -> Threads.entity(player, () -> this.sendIn(player, items == null ? List.of() : items)));
    }

    private boolean stillJoining(final Player player) {
        return player.isOnline() && this.getState() == FightState.IN_FIGHT && this.joining.contains(player) && arenasOpen();
    }

    private static boolean arenasOpen() {
        return SpawnArena.settings == null || SpawnArena.settings.arenasOpen;
    }

    /** On the player's thread: saves everything they have, sets them up and teleports them in. */
    private void sendIn(final Player player, final List<ItemStack> recalledTridents) {
        if (!this.stillJoining(player)) {
            // Their tridents were taken out of the world, so they go back to them anyway
            for (final ItemStack item : recalledTridents)
                player.getInventory().addItem(item).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            this.abortJoin(player, "That free-for-all just ended. Try joining again.");
            return;
        }

        final List<ItemStack> didntFit = new ArrayList<>();
        for (final ItemStack item : recalledTridents)
            didntFit.addAll(player.getInventory().addItem(item).values());

        final PlayerPreFightData data = new PlayerPreFightData(player);
        data.addExtraItems(didntFit);
        final UUID id = player.getUniqueId();
        this.preFightData.put(id, data);
        this.arrowsBefore.put(id, player.getArrowsInBody());
        this.restored.remove(id); // they may have been in this session before
        this.quitters.remove(id);

        player.setGameMode(GameMode.SURVIVAL);
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);
        player.setFireTicks(0);
        if (this.arena.isShouldClearItems()) {
            player.getInventory().clear();
            for (final PotionEffect effect : new ArrayList<>(player.getActivePotionEffects()))
                player.removePotionEffect(effect.getType());
        }
        this.giveKit(player);

        final Location spawn = this.pickSpawn();
        this.spawnSpots.put(id, spawn);
        this.arena.teleportFighter(player, spawn).whenComplete((ok, error) ->
                Threads.entity(player, () -> this.arrived(player, error == null && Boolean.TRUE.equals(ok))));
    }

    /** On the player's thread, once the teleport in is done (or failed). */
    private void arrived(final Player player, final boolean teleported) {
        final boolean admitted;
        final int count;
        synchronized (this) {
            admitted = teleported && player.isOnline() && this.getState() == FightState.IN_FIGHT && arenasOpen() && this.joining.remove(player);
            if (admitted) {
                this.alive.add(player);
                this.fightingSince.put(player.getUniqueId(), System.currentTimeMillis());
            }
            else
                this.joining.remove(player);
            count = this.alive.size();
        }
        JOINING.remove(player.getUniqueId());

        if (!admitted) {
            // Saved but never let in: everything goes straight back (a logged-out player gets it on their next join)
            this.restored.remove(player.getUniqueId());
            Fight.AWAITING_RESTORE.add(player.getUniqueId());
            this.restore(player);
            if (player.isOnline())
                player.sendMessage(ChatColor.RED + (teleported ? "That free-for-all just ended. Try joining again."
                        : "Couldn't get you into the arena. Try again in a moment."));
            this.endIfEmpty();
            return;
        }

        this.protect(player);
        this.showFakeIce(player);
        if (this.fakeIce != null || this.variant == SumoVariant.MOON)
            AnticheatExempt.add(player); // it sees the real floor (Ice Rink in MULTI), or flags Moon Gravity's jumps
        final String arenaName = Arena.displayName(this.arena.getArenaName());
        if (this.variant != null)
            player.sendMessage(MiniMessage.miniMessage().deserialize(this.variant.announce(this.arena)));
        player.sendMessage(MiniMessage.miniMessage().deserialize(
                "<green>You're in the <white><arena></white> free-for-all! Everyone here is your opponent.</green> "
                        + "<gray>Your things are kept: you get them back as they were when you die or leave. Leave with "
                        + "<yellow>/leavearena</yellow> once you're out of combat.",
                Placeholder.unparsed("arena", arenaName)));
        this.tellInside(MiniMessage.miniMessage().deserialize("<gray><name> joined the free-for-all <dark_gray>(<count>)",
                Placeholder.component("name", player.displayName()),
                Placeholder.unparsed("count", Grammar.count(count, "player"))), player);
    }

    /** Called from wherever the join was given up: they never got in, so there's nothing of theirs to give back. */
    private void abortJoin(final Player player, final String message) {
        synchronized (this) {
            this.joining.remove(player);
        }
        JOINING.remove(player.getUniqueId());
        if (message != null && player.isOnline())
            player.sendMessage(ChatColor.RED + message);
        this.endIfEmpty();
    }

    /** The spawn point furthest from everyone already fighting (either one when nobody is). */
    private Location pickSpawn() {
        return this.pickSpawn(null);
    }

    /** As {@link #pickSpawn()}, not counting {@code except} (the one being placed) among those fighting. */
    private Location pickSpawn(final Player except) {
        final Location a = this.arena.spawnPoint1, b = this.arena.spawnPoint2;
        if (a == null || b == null)
            return a != null ? a : b;
        final List<Player> fighting = new ArrayList<>(this.getFighters());
        fighting.remove(except);
        if (fighting.isEmpty())
            return java.util.concurrent.ThreadLocalRandom.current().nextBoolean() ? a : b;
        return nearestFighter(a, fighting) >= nearestFighter(b, fighting) ? a : b;
    }

    private static double nearestFighter(final Location spawn, final List<Player> fighting) {
        double nearest = Double.MAX_VALUE;
        for (final Player p : fighting) {
            final Location at = p.getLocation(); // a read of where they are; fine from any thread
            if (at.getWorld() != null && at.getWorld().equals(spawn.getWorld()))
                nearest = Math.min(nearest, at.distanceSquared(spawn));
        }
        return nearest;
    }

    // ── Spawn protection ────────────────────────────────────────────────────

    /** On the player's thread. */
    private void protect(final Player player) {
        final int seconds = SpawnArena.settings == null ? 3 : SpawnArena.settings.ffaSpawnProtectionSeconds;
        if (seconds <= 0)
            return;
        final Object token = new Object();
        this.protectedPlayers.put(player.getUniqueId(), token);
        // Marked on the player too, so a crash mid-protection can't leave them invulnerable (see clearLeftoverProtection)
        player.getPersistentDataContainer().set(PROTECTED_KEY, PersistentDataType.BYTE, (byte) 1);
        player.setInvulnerable(true);
        Threads.entityLater(player, () -> {
            if (this.protectedPlayers.remove(player.getUniqueId(), token))
                clearProtection(player);
        }, seconds * 20L);
    }

    /** Safe to call from any thread, more than once. */
    private void unprotect(final Player player) {
        if (this.protectedPlayers.remove(player.getUniqueId()) == null)
            return;
        Threads.entity(player, () -> clearProtection(player));
    }

    private static void clearProtection(final Player player) {
        if (player.getPersistentDataContainer().has(PROTECTED_KEY, PersistentDataType.BYTE)) {
            player.getPersistentDataContainer().remove(PROTECTED_KEY);
            player.setInvulnerable(false);
        }
    }

    /** On join: spawn protection a crash or restart cut short is taken off. */
    public static void clearLeftoverProtection(final Player player) {
        clearProtection(player);
    }

    // ── Combat ──────────────────────────────────────────────────────────────

    @Override
    public boolean areOpponents(final Player a, final Player b) {
        return !a.equals(b) && this.alive.contains(a) && this.alive.contains(b);
    }

    @Override
    public boolean areTeammates(final Player a, final Player b) {
        return false;
    }

    @Override
    public List<Player> getTeamOf(final Player player) {
        return this.alive.contains(player) ? List.of(player) : List.of();
    }

    /** Everyone in the session now, joining or fighting (not those who have left). */
    @Override
    public List<Player> getMembers() {
        final List<Player> members = this.getFighters();
        for (final Player p : this.joining)
            if (!members.contains(p))
                members.add(p);
        return members;
    }

    @Override
    public void onHurt(final Player attacker, final Player victim) {
        if (attacker == null || attacker.equals(victim) || !this.alive.contains(attacker) || !this.alive.contains(victim))
            return;
        final long now = System.currentTimeMillis();
        this.hitsOn.computeIfAbsent(victim.getUniqueId(), k -> new ConcurrentHashMap<>()).put(attacker.getUniqueId(), now);
        this.lastCombat.put(victim.getUniqueId(), now);
        this.lastCombat.put(attacker.getUniqueId(), now);
        this.unprotect(attacker); // attacking ends their own spawn protection
    }

    private static long combatMillis() {
        return (SpawnArena.settings == null ? 10 : Math.max(0, SpawnArena.settings.ffaCombatSeconds)) * 1000L;
    }

    /** Milliseconds of combat left for this player (0 = out of combat). */
    public long combatLeft(final Player player) {
        final Long last = this.lastCombat.get(player.getUniqueId());
        return last == null ? 0 : Math.max(0, last + combatMillis() - System.currentTimeMillis());
    }

    /** Everyone who hurt this player within the combat window, most recent first. */
    private List<UUID> creditFor(final Player victim) {
        final Map<UUID, Long> hits = this.hitsOn.remove(victim.getUniqueId());
        if (hits == null)
            return List.of();
        final long since = System.currentTimeMillis() - combatMillis();
        final List<Map.Entry<UUID, Long>> recent = new ArrayList<>();
        for (final Map.Entry<UUID, Long> e : hits.entrySet())
            if (e.getValue() >= since && !e.getKey().equals(victim.getUniqueId()))
                recent.add(e);
        recent.sort(Map.Entry.<UUID, Long>comparingByValue(Comparator.reverseOrder()));
        final List<UUID> credit = new ArrayList<>();
        for (final Map.Entry<UUID, Long> e : recent)
            credit.add(e.getKey());
        return credit;
    }

    // ── Leaving ─────────────────────────────────────────────────────────────

    /** /leavearena: out of combat, they leave with everything they came with. Returns false (and says why) if not. */
    public boolean tryLeave(final Player player) {
        final long left = this.combatLeft(player);
        if (left > 0) {
            player.sendMessage(ChatColor.RED + "You're in combat! You can leave in " + ((left + 999) / 1000) + "s.");
            return false;
        }
        return this.out(player, Out.LEFT);
    }

    /**
     * /arenalock: ends the session the same way a shutdown does (everyone gets their things back, nothing counts),
     * as a free-for-all never ends on its own. Returns how many players were in it.
     */
    public int closeAll(final String message) {
        final List<Player> members;
        synchronized (this.arena) {
            synchronized (this) {
                if (this.getState() != FightState.IN_FIGHT)
                    return 0;
                this.fightState = FightState.ENDING; // claimed: no one can join this session any more
                members = this.getMembers();
            }
        }
        for (final Player p : members)
            if (p.isOnline())
                p.sendMessage(ChatColor.RED + message);
        this.endFight();
        return members.size();
    }

    /** Death, liquid, ring-out, or logging out (then marked as quitting first). */
    @Override
    public void eliminate(final Player player) {
        this.out(player, this.hasQuit(player) ? Out.QUIT : Out.DIED);
    }

    /** Teleported away with a command or plugin. */
    @Override
    public void leftArena(final Player player) {
        this.out(player, Out.FLED);
    }

    /** No lag-out allowance in a free-for-all: logging out is logging out (a death only if in combat). */
    @Override
    public void lagOut(final Player player) {
        this.markQuit(player);
        this.out(player, Out.QUIT);
    }

    /** No pressure plate wins in a free-for-all. */
    @Override
    public void winForTeamOf(final Player player) {
    }

    /**
     * Takes them out of the session and gives everything back. Dying (or quitting/fleeing while in combat) is a
     * death, credited to everyone who hurt them within the combat window. Returns false if they weren't in it.
     */
    private boolean out(final Player player, final Out why) {
        final boolean inCombat = this.combatLeft(player) > 0;
        final int count;
        synchronized (this) {
            if (!this.alive.remove(player))
                return false;
            count = this.alive.size();
        }
        final UUID id = player.getUniqueId();
        this.countFightTime(id);
        final List<UUID> credit = this.creditFor(player);
        this.lastCombat.remove(id);
        final int lostStreak = this.streaks.getOrDefault(id, 0);
        this.streaks.remove(id);

        ArenaProjectileDupeFix.discardProjectiles(player);
        Fight.AWAITING_RESTORE.add(id);
        Threads.entity(player, () -> {
            this.unprotect(player);
            clearProtection(player);
            me.zeronull.spawnarena.events.EgapCooldown.clear(player);
            this.restore(player);
        });

        final boolean death = why == Out.DIED || (why != Out.LEFT && inCombat);
        if (death)
            this.death(player, why, credit, lostStreak);
        else if (why == Out.LEFT) {
            if (player.isOnline())
                player.sendMessage(ChatColor.YELLOW + "You left the free-for-all. Your things are back as they were.");
            this.tellInside(MiniMessage.miniMessage().deserialize("<gray><name> left the free-for-all <dark_gray>(<count>)",
                    Placeholder.component("name", player.displayName()),
                    Placeholder.unparsed("count", Grammar.count(count, "player"))), player);
        } else {
            this.tellInside(MiniMessage.miniMessage().deserialize("<gray><name> left the free-for-all <dark_gray>(<count>)",
                    Placeholder.component("name", player.displayName()),
                    Placeholder.unparsed("count", Grammar.count(count, "player"))), player);
        }
        this.endIfEmpty();
        return true;
    }

    private void death(final Player victim, final Out why, final List<UUID> credit, final int lostStreak) {
        final String arenaName = this.arena.getArenaName();
        final List<Player> killersHere = new ArrayList<>();
        final Map<UUID, Integer> newStreaks = new java.util.LinkedHashMap<>();
        for (final UUID killer : credit) {
            final Player p = Bukkit.getPlayer(killer);
            // Their streak only goes on while they're still in here
            final int streak = p != null && this.alive.contains(p) ? this.streaks.merge(killer, 1, Integer::sum) : 0;
            newStreaks.put(killer, streak);
            if (p != null)
                killersHere.add(p);
        }

        // Kills between alts don't count (they still show)
        final Player victimPlayer = victim;
        Threads.async(() -> {
            if (SpawnArena.statsManager == null)
                return;
            SpawnArena.statsManager.recordFfaDeath(victimPlayer.getUniqueId(), arenaName);
            final boolean checkAlts = SpawnArena.settings != null && SpawnArena.settings.blockSameIpStats;
            for (final Map.Entry<UUID, Integer> e : newStreaks.entrySet()) {
                final Player killer = Bukkit.getPlayer(e.getKey());
                if (checkAlts && killer != null && SpawnArena.statsManager.areAlts(killer, victimPlayer))
                    continue;
                SpawnArena.statsManager.recordFfaKill(e.getKey(), arenaName, e.getValue());
            }
        });

        // The message, to everyone in here, the victim and whoever got the kill
        final Component killers = this.names(credit, newStreaks);
        final String template = credit.isEmpty()
                ? switch (why) {
                    case QUIT -> "<red>☠</red> <gray><victim> logged out mid-fight.";
                    case FLED -> "<red>☠</red> <gray><victim> left mid-fight.";
                    default -> "<red>☠</red> <gray><victim> took themselves out.";
                }
                : switch (why) {
                    case QUIT -> "<red>⚔</red> <gray><victim> logged out mid-fight, so <killers> <get> the kill.";
                    case FLED -> "<red>⚔</red> <gray><victim> left mid-fight, so <killers> <get> the kill.";
                    default -> "<red>⚔</red> <gray><victim> was taken out by <killers>.";
                };
        final Component message = MiniMessage.miniMessage().deserialize(template,
                Placeholder.component("victim", victim.displayName().colorIfAbsent(net.kyori.adventure.text.format.NamedTextColor.WHITE)),
                Placeholder.component("killers", killers),
                Placeholder.unparsed("get", credit.size() == 1 ? "gets" : "get"));
        final List<Player> told = new ArrayList<>(this.getMembers());
        if (!told.contains(victim))
            told.add(victim);
        for (final Player k : killersHere)
            if (!told.contains(k))
                told.add(k);
        for (final Player p : told)
            if (p.isOnline())
                p.sendMessage(message);

        if (victim.isOnline())
            victim.showTitle(net.kyori.adventure.title.Title.title(
                    MiniMessage.miniMessage().deserialize("<red><bold>You died"),
                    credit.isEmpty() ? Component.empty()
                            : MiniMessage.miniMessage().deserialize("<gray>Taken out by </gray><killers>", Placeholder.component("killers", killers)),
                    net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(250),
                            java.time.Duration.ofMillis(2000), java.time.Duration.ofMillis(750))));

        // Long streaks are worth telling the server about
        final int every = SpawnArena.settings == null ? 5 : SpawnArena.settings.ffaStreakAnnounce;
        if (every <= 0)
            return;
        final String where = Arena.displayName(arenaName);
        if (lostStreak >= every) {
            final Player ender = killersHere.isEmpty() ? null : killersHere.get(0);
            broadcast(MiniMessage.miniMessage().deserialize(ender == null
                            ? "<dark_red><bold>STREAK ENDED!</bold> <victim> took themselves out in the <arena> free-for-all, losing their "
                                    + "<white><bold><streak></bold></white> kill streak."
                            : "<dark_red><bold>STREAK ENDED!</bold> <killer> ended <victim><possessive> "
                                    + "<white><bold><streak></bold></white> kill streak in the <arena> free-for-all!",
                    Placeholder.component("victim", victim.displayName()),
                    Placeholder.unparsed("possessive", Grammar.possessiveSuffix(victim.getName())),
                    Placeholder.component("killer", ender == null ? Component.empty() : ender.displayName()),
                    Placeholder.unparsed("streak", String.valueOf(lostStreak)),
                    Placeholder.unparsed("arena", where)), told, List.of());
        }
        for (final Map.Entry<UUID, Integer> e : newStreaks.entrySet()) {
            final Player killer = Bukkit.getPlayer(e.getKey());
            if (killer != null && e.getValue() >= every && e.getValue() % every == 0)
                broadcast(MiniMessage.miniMessage().deserialize(
                        "<gold><bold>KILL STREAK!</bold></gold> <yellow><killer></yellow> <gold>is on a <white><bold><streak></bold></white> "
                                + "kill streak in the <arena> free-for-all!",
                        Placeholder.component("killer", killer.displayName()),
                        Placeholder.unparsed("streak", String.valueOf(e.getValue())),
                        Placeholder.unparsed("arena", where)), told, List.of());
        }
    }

    /** "FatBlack (3 streak) & Bob", most recent hit first. Names of people who have since gone offline still show. */
    private Component names(final List<UUID> players, final Map<UUID, Integer> streaks) {
        final List<Component> parts = new ArrayList<>();
        for (final UUID id : players) {
            final Player online = Bukkit.getPlayer(id);
            final OfflinePlayer offline = online != null ? online : Bukkit.getOfflinePlayer(id);
            Component name = online != null ? online.displayName()
                    : Component.text(offline.getName() == null ? "someone" : offline.getName());
            name = name.colorIfAbsent(net.kyori.adventure.text.format.NamedTextColor.WHITE);
            final int streak = streaks.getOrDefault(id, 0);
            if (streak >= 2)
                name = name.append(MiniMessage.miniMessage().deserialize(" <dark_gray>(<gold><streak> streak</gold>)",
                        Placeholder.unparsed("streak", String.valueOf(streak))));
            parts.add(name);
        }
        return Component.join(JoinConfiguration.separator(Component.text(" & ", net.kyori.adventure.text.format.NamedTextColor.GRAY)), parts);
    }

    /** To everyone in the session (fighting or on their way in) but {@code except}. */
    private static long resetMillis() {
        final int minutes = SpawnArena.settings == null ? 5 : SpawnArena.settings.crystalFfaResetMinutes;
        return minutes <= 0 ? Long.MAX_VALUE / 2 : minutes * 60_000L;
    }

    /**
     * Crystal free-for-all: a session can go on for as long as people keep joining, so every crystalFfaResetMinutes
     * the arena is put back as it was (after a 10 second warning) with everyone still in it. Anyone the floor comes
     * back around is moved straight up to the nearest spot they fit.
     */
    private void resetIfDue() {
        if (!this.arena.isCrystalPvp() || this.resetting)
            return;
        final long now = System.currentTimeMillis();
        if (!this.resetWarned && now >= this.nextReset - 10_000) {
            this.resetWarned = true;
            this.tellInside(MiniMessage.miniMessage().deserialize(
                    "<yellow>The arena resets in 10 seconds: everything placed or dug, and dropped items, go back as they were."), null);
        }
        if (now < this.nextReset)
            return;
        this.resetting = true;
        CrystalPvp.cleanup(this)
                .thenCompose(ignored -> {
                    final List<java.util.concurrent.CompletableFuture<Boolean>> moved = new ArrayList<>();
                    for (final Player p : this.getFighters()) {
                        final java.util.concurrent.CompletableFuture<Boolean> done = new java.util.concurrent.CompletableFuture<>();
                        Threads.entity(p, () -> this.liftOut(p).whenComplete((ok, e) -> done.complete(true)));
                        moved.add(done);
                    }
                    return java.util.concurrent.CompletableFuture.allOf(moved.toArray(new java.util.concurrent.CompletableFuture[0]));
                })
                .thenCompose(ignored -> CrystalPvp.takeSnapshot(this))
                .whenComplete((ok, error) -> {
                    if (error != null)
                        SpawnArena.INSTANCE.getLogger().log(java.util.logging.Level.WARNING,
                                "Couldn't reset crystal free-for-all " + this.arena.getArenaName(), error);
                    this.nextReset = System.currentTimeMillis() + resetMillis();
                    this.resetWarned = false;
                    this.resetting = false;
                    this.tellInside(MiniMessage.miniMessage().deserialize("<yellow>The arena has been reset."), null);
                });
    }

    /**
     * On the player's thread: if the arena came back around them, up to the nearest spot above where they fit, or (no
     * room anywhere above, e.g. stuck in a wall) to the spawn point furthest from everyone else.
     */
    private java.util.concurrent.CompletableFuture<Boolean> liftOut(final Player player) {
        if (!player.isOnline() || !this.alive.contains(player) || !player.collidesAt(player.getLocation()))
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        final Location at = player.getLocation();
        final Integer top = this.arena.regionTopY();
        final int highest = top != null ? top : at.getWorld().getMaxHeight() - 2;
        for (int y = at.getBlockY() + 1; y <= highest; y++) {
            final Location spot = at.clone();
            spot.setY(y);
            if (!player.collidesAt(spot)) {
                player.setFallDistance(0);
                return Threads.teleport(player, spot);
            }
        }
        final Location spawn = this.pickSpawn(player);
        if (spawn == null)
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        player.setFallDistance(0);
        player.sendMessage(net.kyori.adventure.text.Component.text("You were stuck in the arena when it reset, so you were moved to a spawn point.",
                net.kyori.adventure.text.format.NamedTextColor.YELLOW));
        return Threads.teleport(player, spawn);
    }

    private void tellInside(final Component message, final Player except) {
        for (final Player p : this.getMembers())
            if (!p.equals(except) && p.isOnline())
                p.sendMessage(message);
    }

    // ── Ending ──────────────────────────────────────────────────────────────

    /** Ends the session once nobody is in it or on their way in (the arena is then cleaned up and reset). */
    private void endIfEmpty() {
        final boolean empty;
        synchronized (this.arena) {
            synchronized (this) {
                empty = this.getState() == FightState.IN_FIGHT && this.alive.isEmpty() && this.joining.isEmpty();
                if (empty)
                    this.fightState = FightState.ENDING; // claimed: no one can join this session any more
            }
        }
        if (empty)
            this.endFight();
    }

    @Override
    public void endFight() {
        final ScheduledTask task = this.ticker;
        if (task != null && !task.isCancelled())
            task.cancel();
        // Everyone still in (shutdown, or the arena being deleted) is taken out quietly with their things
        for (final Player p : this.getFighters()) {
            this.protectedPlayers.remove(p.getUniqueId());
            Threads.entity(p, () -> clearProtection(p));
        }
        super.endFight();
        for (final Player p : new ArrayList<>(this.joining))
            JOINING.remove(p.getUniqueId());
        this.joining.clear();
    }

    // ── Action bar ──────────────────────────────────────────────────────────

    private void tick() {
        if (this.getState() != FightState.IN_FIGHT)
            return;
        this.resetIfDue();
        final List<Player> fighting = this.getFighters();
        final int count = fighting.size();
        // MULTI Ice Rink: the fake ice again, in case anything showed them the real floor
        if (this.fakeIce != null)
            for (final Player p : fighting)
                this.showFakeIce(p);
        for (final Player p : fighting)
            Threads.entity(p, () -> {
                if (!this.alive.contains(p) || !p.isOnline())
                    return;
                final long combat = this.combatLeft(p);
                final String status = this.protectedPlayers.containsKey(p.getUniqueId()) ? "<aqua>Spawn protection</aqua>"
                        : combat > 0 ? "<red>In combat " + ((combat + 999) / 1000) + "s</red>"
                        : "<green>/leavearena to leave</green>";
                p.sendActionBar(MiniMessage.miniMessage().deserialize(
                        "<gold><bold>FFA</bold></gold> <dark_gray>·</dark_gray> <gray>Kill streak <white><streak></white> "
                                + "<dark_gray>·</dark_gray> <gray><count></gray> <dark_gray>·</dark_gray> " + status,
                        Placeholder.unparsed("streak", String.valueOf(this.streaks.getOrDefault(p.getUniqueId(), 0))),
                        Placeholder.unparsed("count", Grammar.count(count, "player"))));
            });
    }
}
