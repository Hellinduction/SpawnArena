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
import org.bukkit.potion.PotionEffect;

import java.lang.reflect.Method;
import java.util.*;

/**
 * A match between two teams: one player each for 1v1, two each for 2v2. Team A spawns at the
 * arena's spawn point 1, team B at spawn point 2. Every way of losing (death, liquid, ring-out,
 * leaving, quitting) goes through {@link #eliminate}; a team loses once all its players are out.
 */
public class Fight {
    UUID uuid = UUID.randomUUID();
    // Read and changed from several threads on Folia (each player's, the countdown's)
    volatile FightState fightState = FightState.COUNTDOWN;
    /** Duels (from a duel request) never change stats or pay rewards. */
    private boolean duel;

    Arena arena;
    private FightMode mode = FightMode.ONE_V_ONE;
    private final List<Player> teamA = new ArrayList<>();
    private final List<Player> teamB = new ArrayList<>();
    /** Where each fighter actually spawns (2v2 teammates stand side by side), set when the fight starts */
    protected final Map<UUID, Location> spawnSpots = new java.util.concurrent.ConcurrentHashMap<>();
    /** Coin flip per match for which team gets which spawn, so join order doesn't decide sides */
    private final boolean swapSides = java.util.concurrent.ThreadLocalRandom.current().nextBoolean();
    /** Players still in the match; eliminated players are restored and leave this set. */
    protected final Set<Player> alive = Collections.synchronizedSet(new LinkedHashSet<>());
    protected final Map<UUID, PlayerPreFightData> preFightData = new java.util.concurrent.ConcurrentHashMap<>();
    /** Arrows already stuck in each player when they went in (Archer takes away only the match's own). */
    protected final Map<UUID, Integer> arrowsBefore = new java.util.concurrent.ConcurrentHashMap<>();
    protected final Set<UUID> restored = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Where each player was when they went out (before being restored and teleported away). */
    private final Map<UUID, Location> eliminatedAt = new java.util.concurrent.ConcurrentHashMap<>();
    /** Fighters still getting ready and moving into the arena; the fight goes live when this empties. */
    private final Set<Player> arriving = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * Players whose fight is over but whose inventory hasn't been put back yet (on Folia that can take a
     * tick when they're on another thread). They're treated like fighters meanwhile: no dropping items.
     */
    public static final Set<UUID> AWAITING_RESTORE = java.util.concurrent.ConcurrentHashMap.newKeySet();
    ScheduledTask starting;
    /** Bets taken from fighters for this fight (see Bets), until settled or refunded. */
    private final Map<UUID, Double> bets = new java.util.concurrent.ConcurrentHashMap<>();
    /** Counts the arena's ticks while the fight is live, to tell how laggy it was (see averageTps). */
    private volatile ScheduledTask tickCounter;
    private volatile long firstTickNanos, lastTickNanos;
    private volatile int ticksCounted;
    /** Fighters who quit (as opposed to their connection dropping): a lost bet doesn't come back for them. */
    protected final Set<UUID> quitters = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final FightStats stats = new FightStats();
    /** Crystal arenas: the snapshot, taken during the countdown; nobody is sent in until it's ready. */
    protected volatile java.util.concurrent.CompletableFuture<Boolean> arenaReady = java.util.concurrent.CompletableFuture.completedFuture(true);
    /** Random sumo: this match's variant (null when the arena doesn't use random sumo). */
    protected volatile SumoVariant variant;

    public SumoVariant getVariant() {
        return this.variant;
    }

    /** MULTI Ice Rink: the platform, shown as ice only to this match's players. */
    protected volatile java.util.concurrent.CompletableFuture<List<SumoIce.FakeBlock>> fakeIce;

    /** Shrinking Platform: drops the edge a ring at a time while the fight is on. */
    private volatile ScheduledTask shrinkTask;
    /** MULTI Ice Rink: shows the fake ice again every second (anything that resyncs the real floor is undone). */
    private volatile ScheduledTask iceTask;

    /** Picks this match's random sumo variant and gets Ice Rink's or Shrinking Platform's floor ready. */
    protected void pickVariant() {
        this.variant = SumoVariant.pickFor(this.arena, this.isFfa(), this.getMembers().stream().map(Player::getUniqueId).toList());
        if (this.variant == SumoVariant.SHRINK) {
            SumoIce.record(this.arena); // saved now, put back at the end
            return;
        }
        if (this.variant != SumoVariant.ICE)
            return;
        if (SumoVariant.iceIsClientSide(this.arena))
            this.fakeIce = SumoIce.findForFake(this.arena);
        else
            SumoIce.freeze(this.arena);
    }

    /** MULTI Ice Rink: shows this player the ice (once they're in). */
    public void showFakeIce(final Player player) {
        final java.util.concurrent.CompletableFuture<List<SumoIce.FakeBlock>> ice = this.fakeIce;
        if (ice != null)
            ice.thenAccept(blocks -> {
                if (this.isFighter(player)) // not someone who's already out by the time it's worked out
                    SumoIce.showIce(player, blocks);
            });
    }

    /** MULTI Ice Rink: whether this block is one of the fake ice blocks, or right next to one. */
    public boolean hasFakeIceNear(final org.bukkit.block.Block block) {
        final java.util.concurrent.CompletableFuture<List<SumoIce.FakeBlock>> ice = this.fakeIce;
        if (ice == null || !ice.isDone() || ice.isCompletedExceptionally())
            return false;
        for (final SumoIce.FakeBlock b : ice.join()) {
            final Location at = b.at();
            if (at.getWorld() == block.getWorld() && Math.abs(at.getBlockX() - block.getX()) <= 1
                    && Math.abs(at.getBlockY() - block.getY()) <= 1 && Math.abs(at.getBlockZ() - block.getZ()) <= 1)
                return true;
        }
        return false;
    }

    /** Hands out the arena's fight items: the random sumo variant's, or the Slap Fish. */
    protected void giveKit(final Player fighter) {
        if (this.variant != null)
            this.variant.equip(fighter, this.arena);
        else if (this.arena.isGiveKnockBackStick())
            fighter.getInventory().addItem(SlapFish.create());
        else
            return;
        holdKit(fighter);
    }

    /**
     * Puts the fight's item in their hand: switches to its hotbar slot, or (a full hotbar) swaps it into the first one.
     * Their own held slot and inventory are put back afterwards with everything else.
     */
    private static void holdKit(final Player fighter) {
        final org.bukkit.inventory.PlayerInventory inv = fighter.getInventory();
        int slot = -1;
        for (int i = 0; i < inv.getStorageContents().length; i++)
            if (SlapFish.isKitItem(inv.getItem(i))) {
                slot = i;
                break;
            }
        if (slot < 0)
            return;
        if (slot > 8) {
            final ItemStack kit = inv.getItem(slot), first = inv.getItem(0);
            inv.setItem(0, kit);
            inv.setItem(slot, first);
            slot = 0;
        }
        inv.setHeldItemSlot(slot);
    }

    public FightStats stats() {
        return this.stats;
    }

    public Map<UUID, Double> bets() {
        return this.bets;
    }

    /** Marks a fighter as having quit, before they're eliminated for it. */
    public void markQuit(final Player player) {
        this.quitters.add(player.getUniqueId());
    }

    public boolean hasQuit(final Player player) {
        return this.quitters.contains(player.getUniqueId());
    }

    /**
     * How fast the arena ticked while the fight was live (20 = no lag), or -1 if it hasn't been long enough
     * to tell. On Folia that's the arena's own region, not the whole server.
     */
    public double averageTps() {
        final int ticks = this.ticksCounted;
        final double seconds = (this.lastTickNanos - this.firstTickNanos) / 1e9;
        if (ticks < 2 || seconds < 2)
            return -1;
        return Math.min(20, (ticks - 1) / seconds);
    }

    private void startTickCounter() {
        final Location where = this.arena.spawnPoint1;
        if (where == null || where.getWorld() == null)
            return;
        this.tickCounter = Threads.locationTimer(where, task -> {
            final long now = System.nanoTime();
            if (this.ticksCounted == 0)
                this.firstTickNanos = now;
            this.lastTickNanos = now;
            this.ticksCounted++;
        }, 1L, 1L);
    }

    public boolean isDuel() {
        return this.duel;
    }

    /** A free-for-all session (see FfaFight): one per arena, players come and go, everyone fights everyone. */
    public boolean isFfa() {
        return false;
    }

    /**
     * {@code victim} was hurt by {@code attacker} (null: nobody, e.g. a fall), already checked to be opponents.
     * FFA sessions use it for kill credit and the combat timer.
     */
    public void onHurt(final Player attacker, final Player victim) {
    }

    /** A fighter teleported out of the arena themselves (a command or plugin): out, like any other way of losing. */
    public void leftArena(final Player player) {
        this.eliminate(player);
    }

    public Fight asDuel() {
        this.duel = true;
        return this;
    }

    public FightMode getMode() {
        return this.mode;
    }

    public FightState getState() {
        return fightState;
    }

    public Arena getArena() {
        return this.arena;
    }

    // ── Teams ───────────────────────────────────────────────────────────────

    /** Every player who started this match, eliminated or not. */
    public List<Player> getMembers() {
        final List<Player> members = new ArrayList<>(this.teamA);
        members.addAll(this.teamB);
        return members;
    }

    /** Players still fighting. */
    public List<Player> getFighters() {
        synchronized (this.alive) {
            return new ArrayList<>(this.alive);
        }
    }

    public boolean isFighter(final Player player) {
        return this.alive.contains(player);
    }

    public List<Player> getTeamOf(final Player player) {
        if (this.teamA.contains(player)) return this.teamA;
        if (this.teamB.contains(player)) return this.teamB;
        return Collections.emptyList();
    }

    private List<Player> otherTeam(final List<Player> team) {
        return team == this.teamA ? this.teamB : this.teamA;
    }

    public boolean areOpponents(final Player a, final Player b) {
        return (this.teamA.contains(a) && this.teamB.contains(b)) || (this.teamB.contains(a) && this.teamA.contains(b));
    }

    public boolean areTeammates(final Player a, final Player b) {
        return !a.equals(b) && ((this.teamA.contains(a) && this.teamA.contains(b)) || (this.teamB.contains(a) && this.teamB.contains(b)));
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /** Seconds of "Starting in..." between being paired and the fight (countdownSeconds in settings.yml). */
    public static int countdownSeconds() {
        return SpawnArena.settings == null ? 3 : Math.max(0, SpawnArena.settings.countdownSeconds);
    }

    public void initiateFight(final Player fighter1, final Player fighter2, final Arena arena) {
        initiateTeamFight(List.of(fighter1), List.of(fighter2), arena);
    }

    public void initiateTeamFight(final List<Player> teamA, final List<Player> teamB, final Arena arena) {
        this.arena = arena;
        this.mode = teamA.size() >= 2 ? FightMode.TWO_V_TWO : FightMode.ONE_V_ONE;
        this.teamA.addAll(teamA);
        this.teamB.addAll(teamB);
        this.alive.addAll(teamA);
        this.alive.addAll(teamB);
        arena.addFight(this);
        // Crystal arenas: the snapshot is taken while everyone waits for the countdown (nobody can be in the
        // arena then: it's empty, and a new match only starts once the last one's reset is done)
        if (arena.isCrystalPvp())
            this.arenaReady = CrystalPvp.takeSnapshot(this);
        this.pickVariant();
        if (this.variant != null)
            for (final Player p : this.getMembers())
                p.sendMessage(MiniMessage.miniMessage().deserialize(this.variant.announce(this.arena)));

        if (this.mode == FightMode.TWO_V_TWO)
            this.announceTeams();

        // A ping so they notice they've been matched, only for them
        for (final Player p : this.getMembers())
            Threads.entity(p, () -> p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f));

        final int[] counter = {countdownSeconds()};
        this.starting = Threads.globalTimer(task -> {
            if (this.fightState != FightState.COUNTDOWN) {
                task.cancel();
                return;
            }
            if (onlineCheck())
                return;

            if (counter[0] == 0) {
                task.cancel();
                startFight();
            } else {
                sendFightersMessage("&6Starting in " + counter[0] + "...");
                counter[0]--;
            }
        }, 20L, 20L);
    }

    private void announceTeams() {
        for (final Player p : this.getMembers()) {
            final List<Player> mine = getTeamOf(p);
            final List<Player> theirs = otherTeam(mine);
            p.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<green>Your team: <white><mine></white></green> <gray>vs</gray> <red><theirs></red>",
                    Placeholder.unparsed("mine", names(mine)),
                    Placeholder.unparsed("theirs", names(theirs))));
        }
    }

    private static String names(final List<Player> players) {
        final List<String> names = new ArrayList<>();
        for (final Player p : players)
            names.add(p.getName());
        return String.join(" & ", names);
    }

    private void tryCancel(final ScheduledTask task) {
        if (task != null && !task.isCancelled())
            task.cancel();
    }

    private boolean onlineCheck() {
        for (final Player fighter : this.getMembers()) {
            if (fighter.isOnline())
                continue;

            this.cancelBecause(fighter, fighter.getName() + " has left, so the fight was cancelled.");
            return true;
        }
        return false;
    }

    /**
     * Cancels a match still in its countdown because {@code culprit} left, teleported or wandered off.
     * Everyone else goes straight back into the queue (front of the line) instead of losing their spot.
     * Returns false if the match had already started or ended, in which case nothing happened.
     */
    public boolean cancelBecause(final Player culprit, final String messageForOthers) {
        final List<Player> others = new ArrayList<>();
        for (final Player p : this.getMembers())
            if (!p.equals(culprit) && p.isOnline())
                others.add(p);

        if (!this.cancelFight())
            return false; // already started or ended (another thread got there first)

        final boolean requeue = !this.duel && (SpawnArena.settings == null || SpawnArena.settings.arenasOpen);
        for (final Player p : others)
            p.sendMessage(ChatColor.RED + messageForOthers + (requeue ? ChatColor.GRAY + " You've been put back in the queue." : ""));

        if (requeue)
            this.arena.queue.requeue(others, this.mode);
        else
            this.arena.queue.tryStartFight();
        return true;
    }

    private void sendFightersMessage(final String message) {
        for (final Player fighter : this.getFighters())
            if (isAvailable(fighter))
                fighter.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    private static boolean isAvailable(final Player p) {
        return p != null && p.isOnline();
    }

    /**
     * Cancels a match that's still counting down (nothing to restore yet). Returns false if it has
     * already started or ended, in which case nothing happens.
     */
    public synchronized boolean cancelFight() {
        if (this.fightState != FightState.COUNTDOWN)
            return false;
        this.fightState = FightState.ENDING;

        for (final Player p : this.getMembers())
            ArenaProjectileDupeFix.discardProjectiles(p);

        this.alive.clear();
        this.fightState = FightState.OVER;

        this.arena.removeFight(this);
        this.tryCancel(this.starting);
        this.performShowLogic();
        CrystalPvp.cleanup(this); // nothing can be placed before the fight starts, but just in case
        if (this.variant == SumoVariant.ICE || this.variant == SumoVariant.SHRINK)
            SumoIce.thaw(this.arena);
        return true;
    }

    /**
     * Called by the countdown (global thread). Each fighter is then got ready on their own thread (on
     * Paper that's all immediate, in this order); the fight goes live once everyone has arrived.
     */
    public void startFight() {
        synchronized (this) {
            if (this.fightState != FightState.COUNTDOWN)
                return;
            this.fightState = FightState.PRE_FIGHT_START;
            this.arriving.addAll(this.getFighters());
        }
        Arena.ArenaUtils.kickOutLingeringPlayers(this.arena);
        // Crystal arenas: the snapshot started with the countdown and is almost always ready by now; if it isn't,
        // the fighters are only sent in once it is (and the fight is called off if it failed)
        final java.util.concurrent.CompletableFuture<Boolean> arenaReady = this.arenaReady;

        // Extra inventory backup through an inventory-saving plugin, if the server has one with /invsave.
        // Console commands belong on the global thread, which is where the countdown runs.
        if (Bukkit.getServer().getCommandMap().getCommand("invsave") != null)
            for (final Player fighter : this.getFighters())
                Threads.global(() -> Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), "invsave " + fighter.getName()));

        // 2v2 side-by-side spots are worked out where the spawns are (block checks), then everyone goes
        final java.util.concurrent.CompletableFuture<Void> spots = this.mode == FightMode.TWO_V_TWO
                ? java.util.concurrent.CompletableFuture.allOf(
                        Threads.supplyAt(this.getSpawnOf(this.teamA.get(0)), () -> { this.placeSideBySide(this.teamA); return null; }),
                        Threads.supplyAt(this.getSpawnOf(this.teamB.get(0)), () -> { this.placeSideBySide(this.teamB); return null; }))
                : java.util.concurrent.CompletableFuture.completedFuture(null);

        // If a spawn's area doesn't answer in time, start anyway (teammates just share the spawn point)
        final java.util.concurrent.CompletableFuture<Void> placed = spots.completeOnTimeout(null, 3, java.util.concurrent.TimeUnit.SECONDS)
                .exceptionally(error -> {
                    SpawnArena.INSTANCE.getLogger().log(java.util.logging.Level.WARNING, "Couldn't place 2v2 teammates side by side", error);
                    return null;
                });
        placed.thenCombine(arenaReady, (ignored, ready) -> ready).whenComplete((ready, error) -> {
            if (!Boolean.TRUE.equals(ready)) {
                // No snapshot, no fight: nobody has been sent in or set up yet
                for (final Player p : this.getMembers())
                    if (p.isOnline())
                        p.sendMessage(ChatColor.RED + "The arena couldn't be got ready, so the fight was called off. Try again in a moment.");
                this.endFight();
                return;
            }
            for (final Player fighter : new ArrayList<>(this.arriving))
                Threads.entity(fighter, () -> this.getReady(fighter));
        });
    }

    /** On the fighter's thread: recall their tridents, then snapshot, set up and send them in. */
    private void getReady(final Player fighter) {
        if (this.fightState != FightState.PRE_FIGHT_START || !this.isFighter(fighter) || !fighter.isOnline()) {
            this.arrived(fighter);
            return;
        }

        // Pending duel requests are void once they're in a fight (cleared silently, before the teleport
        // in, so they don't get a "cancelled because you teleported" message)
        DuelManager.clear(fighter);
        fighter.setInvulnerable(false);
        this.leaveParkour(fighter);
        fighter.closeInventory();

        final java.util.concurrent.CompletableFuture<List<ItemStack>> recalled = recallTridents(fighter);
        if (recalled.isDone()) {
            this.prepareAndSend(fighter, recalled.join());
            return;
        }
        recalled.whenComplete((items, error) -> Threads.entity(fighter, () -> this.prepareAndSend(fighter, items == null ? List.of() : items)));
    }

    /**
     * Tridents thrown before the match (still flying or stuck somewhere) go back into the inventory before it's
     * saved; otherwise a loyalty trident returns into the fight inventory and is lost when that inventory is
     * replaced at the end. Each trident is taken on its own thread. Completes with the tridents' items (at most
     * 2 seconds later: a trident whose thread never runs the task, e.g. it unloaded, mustn't stall anything).
     */
    protected static java.util.concurrent.CompletableFuture<List<ItemStack>> recallTridents(final Player fighter) {
        final List<java.util.concurrent.CompletableFuture<ItemStack>> recalled = new ArrayList<>();
        for (final org.bukkit.entity.Trident trident : me.zeronull.spawnarena.events.ThrownTridents.take(fighter)) {
            final java.util.concurrent.CompletableFuture<ItemStack> item = new java.util.concurrent.CompletableFuture<>();
            recalled.add(item);
            Threads.entity(trident, () -> {
                if (!trident.isValid() || trident.getPickupStatus() != org.bukkit.entity.AbstractArrow.PickupStatus.ALLOWED) {
                    item.complete(null); // gone already, or e.g. a creative throw: nothing to give back
                    return;
                }
                final ItemStack stack = trident.getItemStack();
                trident.remove();
                item.complete(stack);
            });
        }
        if (recalled.isEmpty())
            return java.util.concurrent.CompletableFuture.completedFuture(List.of());

        return java.util.concurrent.CompletableFuture
                .allOf(recalled.toArray(new java.util.concurrent.CompletableFuture[0]))
                .completeOnTimeout(null, 2, java.util.concurrent.TimeUnit.SECONDS)
                .handle((ignored, error) -> {
                    final List<ItemStack> items = new ArrayList<>();
                    for (final java.util.concurrent.CompletableFuture<ItemStack> f : recalled)
                        if (f.isDone() && !f.isCompletedExceptionally() && f.join() != null)
                            items.add(f.join());
                    return items;
                });
    }

    private void prepareAndSend(final Player fighter, final List<ItemStack> recalledTridents) {
        if (this.fightState != FightState.PRE_FIGHT_START || !this.isFighter(fighter) || !fighter.isOnline()) {
            // Gave them their tridents back anyway: they were taken out of the world
            for (final ItemStack item : recalledTridents)
                fighter.getInventory().addItem(item).values().forEach(left -> fighter.getWorld().dropItemNaturally(fighter.getLocation(), left));
            this.arrived(fighter);
            return;
        }

        final List<ItemStack> didntFit = new ArrayList<>();
        for (final ItemStack item : recalledTridents)
            didntFit.addAll(fighter.getInventory().addItem(item).values());

        final PlayerPreFightData data = new PlayerPreFightData(fighter);
        data.addExtraItems(didntFit);
        this.preFightData.put(fighter.getUniqueId(), data);
        this.arrowsBefore.put(fighter.getUniqueId(), fighter.getArrowsInBody());

        // Their /arenabet bet: taken if this fight can pay out, otherwise cancelled (duels included)
        if (Bukkit.getPluginManager().isPluginEnabled("Vault"))
            Bets.placeIfReady(fighter, this);

        fighter.setGameMode(GameMode.SURVIVAL);
        fighter.setHealth(fighter.getMaxHealth());
        fighter.setFoodLevel(20);

        if (this.arena.isShouldClearItems()) {
            fighter.getInventory().clear();
            for (final PotionEffect effect : new ArrayList<>(fighter.getActivePotionEffects()))
                fighter.removePotionEffect(effect.getType());
        }

        this.giveKit(fighter);

        this.arena.teleportFighter(fighter, this.getSpawnOf(fighter)).whenComplete((ok, error) -> this.arrived(fighter));
    }

    /** One fighter is in (or dropped out); once nobody is still on their way, the fight goes live. */
    private void arrived(final Player fighter) {
        synchronized (this) {
            this.arriving.remove(fighter);
            if (!this.arriving.isEmpty() || this.fightState != FightState.PRE_FIGHT_START)
                return;
            this.fightState = FightState.IN_FIGHT;
        }
        this.stats.start();
        for (final Player p : this.getFighters())
            this.showFakeIce(p);
        if (this.fakeIce != null || this.variant == SumoVariant.MOON)
            for (final Player p : this.getFighters())
                AnticheatExempt.add(p); // it sees the real floor (Ice Rink in MULTI), or flags Moon Gravity's jumps
        if (this.fakeIce != null)
            this.iceTask = Threads.globalTimer(task -> {
                if (this.fightState != FightState.IN_FIGHT) {
                    task.cancel();
                    return;
                }
                for (final Player p : this.getFighters())
                    this.showFakeIce(p);
            }, 20L, 20L);
        if (this.variant == SumoVariant.SHRINK)
            this.shrinkTask = SumoShrink.start(this);
        if (this.mode == FightMode.TWO_V_TWO)
            Threads.async(() -> TeamPicker.remember(this.teamA, this.teamB));
        this.performHideLogic();
        this.startTickCounter();
        FightGlow.start(this);
    }

    /**
     * Takes a player out of the match: their team loses once nobody on it is left. Called for every way
     * of losing — death, liquid, ring-out, teleporting out, quitting.
     */
    public void eliminate(final Player player) {
        final List<Player> team;
        final boolean teamOut;
        synchronized (this) {
            if (this.fightState != FightState.IN_FIGHT && this.fightState != FightState.PRE_FIGHT_START)
                return;
            if (!this.alive.remove(player))
                return;

            this.eliminatedAt.put(player.getUniqueId(), player.getLocation().clone());
            team = getTeamOf(player);
            teamOut = team.stream().noneMatch(this.alive::contains);
            if (teamOut)
                this.fightState = FightState.ENDING; // claimed: nothing else can finish this fight now
        }
        ArenaProjectileDupeFix.discardProjectiles(player);
        // Dropped out while still being sent in: don't hold up the start
        if (this.arriving.contains(player))
            this.arrived(player);

        if (teamOut) {
            this.finish(otherTeam(team), team);
            return;
        }

        // Teammate still fighting: this player is out now, the match goes on
        AWAITING_RESTORE.add(player.getUniqueId());
        Threads.entity(player, () -> this.restore(player));
        this.showTo(player);
        if (player.isOnline())
            player.sendMessage(ChatColor.RED + "You were eliminated! Your teammate is still fighting.");
        for (final Player other : this.getFighters())
            other.sendMessage(ChatColor.GRAY + player.getName() + " was eliminated!");
    }

    /** Ends the match with {@code player}'s team as the winner (e.g. stone pressure plate). */
    public void winForTeamOf(final Player player) {
        final List<Player> winners = getTeamOf(player);
        if (winners.isEmpty())
            return;
        synchronized (this) {
            if (this.fightState != FightState.IN_FIGHT)
                return;
            this.fightState = FightState.ENDING;
        }
        this.finish(winners, otherTeam(winners));
    }

    /**
     * A fighter's connection dropped mid-fight (see LagOuts). If nobody on their team is left fighting
     * (1v1, or the last of a 2v2 team), the match ends as a draw: no stats, no rewards, every bet back.
     * Otherwise they're out like any other elimination, but their own bet comes back.
     */
    public void lagOut(final Player player) {
        final boolean lastOfTeam;
        synchronized (this) {
            if (this.fightState != FightState.IN_FIGHT && this.fightState != FightState.PRE_FIGHT_START)
                return;
            if (!this.alive.contains(player))
                return;
            lastOfTeam = getTeamOf(player).stream().filter(this.alive::contains).count() == 1;
            if (lastOfTeam)
                this.fightState = FightState.ENDING; // claimed for the draw
        }

        if (!lastOfTeam) {
            if (Bukkit.getPluginManager().isPluginEnabled("Vault"))
                Bets.refundOne(this, player, "your connection dropped");
            this.eliminate(player);
            return;
        }

        if (!this.bets.isEmpty() && Bukkit.getPluginManager().isPluginEnabled("Vault"))
            Bets.refundAll(this, player.getName() + "'s connection dropped, so it's a draw");

        final net.kyori.adventure.title.Title title = net.kyori.adventure.title.Title.title(
                MiniMessage.miniMessage().deserialize("<yellow><bold>Draw"),
                MiniMessage.miniMessage().deserialize("<gray><name> lost connection · nothing changed",
                        Placeholder.unparsed("name", player.getName())),
                net.kyori.adventure.title.Title.Times.times(
                        java.time.Duration.ofMillis(250), java.time.Duration.ofMillis(2500), java.time.Duration.ofMillis(750)));
        for (final Player p : this.getMembers())
            if (!p.equals(player) && p.isOnline())
                p.showTitle(title);
        this.endFight();
    }

    private void finish(final List<Player> winners, final List<Player> losers) {
        this.announceResult(new ArrayList<>(winners), new ArrayList<>(losers));
        this.endFight();
        showResultTitles(winners, losers);
    }

    /** "Victory!" / "You Lose!" on screen for everyone who was in the match, eliminated teammates included. */
    private static void showResultTitles(final List<Player> winners, final List<Player> losers) {
        final net.kyori.adventure.title.Title.Times times = net.kyori.adventure.title.Title.Times.times(
                java.time.Duration.ofMillis(250), java.time.Duration.ofMillis(2500), java.time.Duration.ofMillis(750));
        for (final Player p : winners)
            if (p.isOnline())
                p.showTitle(net.kyori.adventure.title.Title.title(
                        MiniMessage.miniMessage().deserialize("<green><bold>Victory!"),
                        MiniMessage.miniMessage().deserialize("<gray>You beat </gray><loser>", Placeholder.component("loser", displayNames(losers))),
                        times));
        for (final Player p : losers)
            if (p.isOnline())
                p.showTitle(net.kyori.adventure.title.Title.title(
                        MiniMessage.miniMessage().deserialize("<red><bold>You Lose!"),
                        MiniMessage.miniMessage().deserialize("<gray>Beaten by </gray><winner>", Placeholder.component("winner", displayNames(winners))),
                        times));
    }

    /** Ends the match and restores everyone not yet restored (each on their own thread). */
    public void endFight() {
        synchronized (this) {
            if (this.fightState == FightState.OVER)
                return;
            this.fightState = FightState.ENDING;
        }

        for (final Player p : this.getMembers()) {
            AnticheatExempt.remove(p.getUniqueId());
            ArenaProjectileDupeFix.discardProjectiles(p);
            if (!this.restored.contains(p.getUniqueId()))
                AWAITING_RESTORE.add(p.getUniqueId());
            Threads.entity(p, () -> this.restore(p));
        }
        this.alive.clear();

        // Bets that weren't settled (fight ended without a result, e.g. shutdown) go back
        if (!this.bets.isEmpty() && Bukkit.getPluginManager().isPluginEnabled("Vault"))
            Bets.refundAll(this, "the fight ended without a result");

        this.fightState = FightState.OVER;
        this.arena.removeFight(this);

        this.tryCancel(this.starting);
        this.tryCancel(this.tickCounter);
        FightGlow.end(this);
        this.performShowLogic();

        // Crystal arenas: everything put back as it was when the fight started; Ice Rink: the floor back too
        final ScheduledTask shrink = this.shrinkTask;
        if (shrink != null && !shrink.isCancelled())
            shrink.cancel();
        this.tryCancel(this.iceTask);
        SumoShrink.clear(this);
        final java.util.concurrent.CompletableFuture<Void> cleaned = this.variant == SumoVariant.ICE || this.variant == SumoVariant.SHRINK
                ? CrystalPvp.cleanup(this).thenCompose(ignored -> SumoIce.thaw(this.arena))
                : CrystalPvp.cleanup(this);

        // Reset before the next match is started, so the paste isn't racing its countdown (and after the
        // crystal clean-up, so the two don't fight over the same blocks)
        if (this.getArena() instanceof SavableArena savableArena && this.getArena().getArenaMode() == ArenaMode.SINGLE) {
            final java.util.concurrent.CompletableFuture<Void> reset = cleaned.thenCompose(ignored -> {
                try {
                    return savableArena.resetArena();
                } catch (final Exception e) {
                    SpawnArena.INSTANCE.getLogger().log(java.util.logging.Level.SEVERE,
                            "Couldn't reset arena " + savableArena.getArenaName(), e);
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                }
            });
            // A crystal arena is pasted back in full: nobody fights in it until that's done (its reward
            // hologram says it's resetting meanwhile)
            if (this.arena.isCrystalPvp() && !reset.isDone()) {
                this.arena.setResetting(true);
                reset.orTimeout(2, java.util.concurrent.TimeUnit.MINUTES).whenComplete((ok, error) -> {
                    if (error != null)
                        SpawnArena.INSTANCE.getLogger().warning("Resetting arena " + this.arena.getArenaName()
                                + " took over 2 minutes; letting fights start again anyway.");
                    this.arena.setResetting(false);
                    Threads.global(() -> this.arena.queue.tryStartFight());
                });
            }
        }

        this.arena.queue.tryStartFight();
    }

    /** Hands this item back with their own inventory once the fight's over (saved, so a crash doesn't lose it). */
    public void returnAfterFight(final Player player, final org.bukkit.inventory.ItemStack item) {
        final PlayerPreFightData data = this.preFightData.get(player.getUniqueId());
        if (data != null)
            data.keepForAfter(item);
        else
            player.getInventory().addItem(item);
    }

    /** Restores a member's pre-fight state once; a failure is logged and never leaves the match stuck. */
    protected void restore(final Player player) {
        // Died for real: put back once they've respawned (a dead player can't be teleported or given items)
        if (player.isOnline() && player.isDead()) {
            Threads.globalLater(() -> Threads.entity(player, () -> this.restore(player)), 5L);
            return;
        }
        try {
            FightGlow.remove(player);
            me.zeronull.spawnarena.events.EgapCooldown.clear(player);
            // The arrows stuck in them from the match (any they came in with stay, unless they've fallen out already)
            if (this.variant == SumoVariant.ARCHER && player.getArrowsInBody() > this.arrowsBefore.getOrDefault(player.getUniqueId(), 0))
                player.setArrowsInBody(this.arrowsBefore.getOrDefault(player.getUniqueId(), 0));
            final java.util.concurrent.CompletableFuture<List<SumoIce.FakeBlock>> ice = this.fakeIce;
            if (ice != null && ice.isDone() && !ice.isCompletedExceptionally())
                SumoIce.showReal(player, ice.join()); // the real floor again, before they're sent back
            AnticheatExempt.remove(player.getUniqueId());
            if (!this.restored.add(player.getUniqueId()))
                return;
            final PlayerPreFightData data = this.preFightData.get(player.getUniqueId());
            if (data == null)
                return;
            try {
                data.restore();
            } catch (final Exception e) {
                SpawnArena.INSTANCE.getLogger().log(java.util.logging.Level.SEVERE,
                        "Couldn't restore " + data.username + " after their fight; it'll be retried when they next join (or use /restore "
                                + data.username + ")", e);
            }
        } finally {
            AWAITING_RESTORE.remove(player.getUniqueId());
        }
    }

    // ── Results ─────────────────────────────────────────────────────────────

    private void announceResult(final List<Player> winners, final List<Player> losers) {
        final String arenaPart = "arena".equals(this.arena.getArenaName()) ? "" : this.arena.getArenaName() + " ";

        // Hovering over the result shows the fight's stats (and, once it's worked out, everyone's Elo change)
        net.kyori.adventure.text.event.HoverEvent<Component> statsHover =
                net.kyori.adventure.text.event.HoverEvent.showText(this.stats.card(this, winners, losers));
        // The losers went out on their own (fell, drowned, their own blast...) without the winners ever touching
        // them: said so instead of "beat". Not for quitting, which has its own meaning
        final boolean selfOut = this.stats.untouched(losers) && losers.stream().noneMatch(this::hasQuit);

        if (this.duel) {
            // Duels don't count for anything, so only the duelists are told
            final Component message = MiniMessage.miniMessage().deserialize(selfOut
                            ? "<dark_red><loser> took themselves out in a duel in the <arena>arena, <winner> wins!"
                            : "<dark_red><winner> beat <loser> in a duel in the <arena>arena!",
                    Placeholder.component("winner", displayNames(winners)),
                    Placeholder.component("loser", displayNames(losers)),
                    Placeholder.parsed("arena", arenaPart)).hoverEvent(statsHover);
            for (final Player p : this.getMembers())
                if (p.isOnline())
                    p.sendMessage(message);
            return; // duels: no stats, no rewards
        }

        // Matches where any winner has shared an IP with any loser (alts) can't pump stats or rewards
        final boolean checkIps = SpawnArena.settings != null
                && (SpawnArena.settings.blockSameIpStats || SpawnArena.settings.blockSameIpRewards);
        boolean alts = false;
        if (checkIps) {
            outer:
            for (final Player w : winners)
                for (final Player l : losers)
                    if (SpawnArena.statsManager.areAlts(w, l)) {
                        alts = true;
                        break outer;
                    }
        }
        final boolean countStats = !(alts && SpawnArena.settings.blockSameIpStats);

        final List<Integer> winnerStreaks = new ArrayList<>();
        final Map<Player, Integer> endedStreaks = new LinkedHashMap<>();
        if (countStats) {
            final int threshold = SpawnArena.settings == null ? 0 : SpawnArena.settings.streakEndAnnounceThreshold;
            for (final Player loser : losers) {
                // Read before recordLoss resets it
                final int streak = SpawnArena.statsManager.getWinStreak(loser.getUniqueId(), this.arena.arenaName);
                if (threshold > 0 && streak >= threshold)
                    endedStreaks.put(loser, streak);
                SpawnArena.statsManager.recordLoss(loser, this.arena.arenaName);
            }
            for (final Player winner : winners)
                winnerStreaks.add(SpawnArena.statsManager.recordWin(winner, this.arena.arenaName));

            final Map<UUID, int[]> elo = SpawnArena.statsManager.recordElo(winners, losers, this.arena.arenaName);
            for (final Player p : this.getMembers()) {
                final int[] change = elo.get(p.getUniqueId());
                if (change != null && p.isOnline())
                    p.sendMessage(MiniMessage.miniMessage().deserialize("<gray>Elo: <white><elo></white> (<change>)",
                            Placeholder.unparsed("elo", String.valueOf(change[0])),
                            Placeholder.parsed("change", change[1] >= 0 ? "<green>+" + change[1] + "</green>" : "<red>" + change[1] + "</red>")));
            }
            if (!elo.isEmpty())
                statsHover = net.kyori.adventure.text.event.HoverEvent.showText(this.stats.card(this, winners, losers, elo));
        }

        if (endedStreaks.isEmpty()) {
            broadcast(MiniMessage.miniMessage().deserialize(selfOut
                            ? "<dark_red><loser> took themselves out in the <arena>arena, <winner> wins<ws_str>!"
                            : "<dark_red><winner> beat <loser> in the <arena>arena<ws_str>!",
                    Placeholder.component("winner", displayNames(winners)),
                    Placeholder.component("loser", displayNames(losers)),
                    Placeholder.parsed("arena", arenaPart),
                    Placeholder.parsed("ws_str", streakPart(winnerStreaks))).hoverEvent(statsHover),
                    winners, losers);
        } else {
            // A long streak just ended: that replaces the usual result message
            for (final Map.Entry<Player, Integer> ended : endedStreaks.entrySet())
                this.announceStreakEnded(ended.getKey(), ended.getValue(), winners, losers, statsHover, selfOut);
        }

        if (Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            final Map<UUID, Double> paid = new HashMap<>();
            for (final Player winner : winners)
                if (winner.isOnline())
                    paid.put(winner.getUniqueId(), WinRewards.reward(winner, losers, this.arena, alts));
            // A win that didn't pay out (alts, same opponent too often, daily cap) refunds the winner's bet
            if (countStats)
                Bets.settle(this, winners, paid, losers);
            else
                Bets.refundAll(this, "this fight didn't count");
        }

        // Shown on the reward hologram's "Last win" line rather than in chat
        if (!countStats)
            for (final Player winner : winners)
                SpawnArena.statsManager.setLastResult(winner.getUniqueId(), this.arena.getArenaName(),
                        "Not counted: same IP as your opponent");
    }

    /**
     * The dramatic announcement when a long win streak gets ended, with a (harmless) lightning strike where
     * that player went down. Its thunder is vanilla's, so only players near enough to see it hear it.
     */
    private void announceStreakEnded(final Player loser, final int streak, final List<Player> winners, final List<Player> losers,
                                     final net.kyori.adventure.text.event.HoverEvent<Component> statsHover, final boolean selfOut) {
        // Worded and coloured like the usual "X beat Y in the sumo arena!" result; only the headline and the streak stand out
        final Component message = MiniMessage.miniMessage().deserialize(selfOut
                        ? "<dark_red><bold>STREAK ENDED!</bold> <loser> took themselves out in the <arena>arena, losing their "
                                + "<white><bold><streak></bold></white>-win streak. <winner> wins!"
                        : "<dark_red><bold>STREAK ENDED!</bold> <winner> ended <loser><possessive> "
                                + "<white><bold><streak></bold></white>-win streak in the <arena>arena!",
                Placeholder.component("loser", loser.displayName()),
                // "Chris'" rather than "Chris's"
                Placeholder.unparsed("possessive", Grammar.possessiveSuffix(loser.getName())),
                Placeholder.component("winner", displayNames(winners)),
                Placeholder.unparsed("streak", String.valueOf(streak)),
                Placeholder.unparsed("arena", "arena".equals(this.arena.getArenaName()) ? "" : this.arena.getArenaName() + " ")).hoverEvent(statsHover);

        broadcast(message, winners, losers);

        final Location where = this.eliminatedAt.getOrDefault(loser.getUniqueId(), loser.getLocation());
        if (where == null || where.getWorld() == null)
            return;
        // The other matches in a MULTI arena are hidden from this one, so its lightning is hidden from them too
        final List<Player> otherMatches = this.arena.getArenaMode() == ArenaMode.MULTI
                ? this.arena.getOtherPlayersInArena(this) : List.of();
        Threads.location(where, () -> {
            if (otherMatches.isEmpty())
                where.getWorld().strikeLightningEffect(where);
            else
                strikeLightningHiddenFrom(where, otherMatches);
        });
    }

    /**
     * A harmless lightning bolt that some players never see (or hear: the thunder is played by each client from
     * the bolt). It has to be hidden before it enters the world, as it's sent to everyone nearby right then, and
     * the API's effect-only strike can't be: so it's spawned hidden and made effect-only through the server
     * itself. If that ever fails, it falls back to an ordinary effect strike hidden straight afterwards.
     */
    private static void strikeLightningHiddenFrom(final Location where, final List<Player> hiddenFrom) {
        final org.bukkit.entity.LightningStrike bolt;
        try {
            bolt = where.getWorld().spawn(where, org.bukkit.entity.LightningStrike.class, strike -> {
                strike.setVisibleByDefault(false);
                try {
                    final Object handle = strike.getClass().getMethod("getHandle").invoke(strike);
                    handle.getClass().getMethod("setVisualOnly", boolean.class).invoke(handle, true);
                } catch (final ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            });
        } catch (final RuntimeException e) {
            final org.bukkit.entity.LightningStrike fallback = where.getWorld().strikeLightningEffect(where);
            for (final Player other : hiddenFrom)
                if (other.isOnline())
                    Threads.entity(other, () -> other.hideEntity(SpawnArena.INSTANCE, fallback));
            return;
        }
        final java.util.Set<java.util.UUID> hidden = new java.util.HashSet<>();
        for (final Player p : hiddenFrom)
            hidden.add(p.getUniqueId());
        for (final Player viewer : where.getWorld().getPlayers())
            if (!hidden.contains(viewer.getUniqueId()))
                Threads.entity(viewer, () -> viewer.showEntity(SpawnArena.INSTANCE, bolt));
    }

    /**
     * " with a win streak of 5" (1v1), or " with win streaks of 5 & 1" (2v2, in the same order as the winners'
     * names). Empty unless a winner is on a streak of more than 2.
     */
    private static String streakPart(final List<Integer> streaks) {
        if (streaks.stream().noneMatch(s -> s > 2))
            return "";
        if (streaks.size() == 1)
            return " with a win streak of <white>" + streaks.get(0) + "<dark_red>";
        final List<String> parts = new ArrayList<>();
        for (final int s : streaks)
            parts.add("<white>" + s + "<dark_red>");
        return " with win streaks of " + String.join(" & ", parts);
    }

    private static Component displayNames(final List<Player> players) {
        final List<Component> names = new ArrayList<>();
        for (final Player p : players)
            names.add(p.displayName());
        return Component.join(JoinConfiguration.separator(Component.text(" & ")), names);
    }

    /**
     * Fighters always see their own result; everyone else only if broadcasting is on and they haven't
     * opted out (/arenawins). Returns the players who received it.
     */
    static List<Player> broadcast(final Component message, final List<Player> winners, final List<Player> losers) {
        final Set<Player> fighters = new HashSet<>(winners);
        fighters.addAll(losers);
        final List<Player> received = new ArrayList<>();

        for (final Player p : fighters)
            if (p.isOnline()) {
                p.sendMessage(message);
                received.add(p);
            }

        if (SpawnArena.settings != null && !SpawnArena.settings.broadcastWins)
            return received;

        Bukkit.getConsoleSender().sendMessage(message);

        // With the Velocity bridge, the whole network sees it (everyone but the fighters, who already have)
        if (ProxyBridge.broadcast(message, new ArrayList<>(received), received))
            return received;

        for (final Player p : Bukkit.getOnlinePlayers())
            if (!fighters.contains(p) && WinMessagePrefs.wantsWinMessages(p)) {
                p.sendMessage(message);
                received.add(p);
            }
        return received;
    }

    // ── Spawns ──────────────────────────────────────────────────────────────

    /** Where this fighter spawns: their team's spawn point (sides picked at random), or their own spot beside it in 2v2; null if not in this match. */
    public Location getSpawnOf(final Player fighter) {
        final Location spot = this.spawnSpots.get(fighter.getUniqueId());
        if (spot != null)
            return spot;
        final Location first = this.swapSides ? this.arena.spawnPoint2 : this.arena.spawnPoint1;
        final Location second = this.swapSides ? this.arena.spawnPoint1 : this.arena.spawnPoint2;
        if (this.teamA.contains(fighter)) return first;
        if (this.teamB.contains(fighter)) return second;
        return null;
    }

    /**
     * Puts two teammates one block either side of their spawn (a block's gap between them), sideways to
     * the way it faces. If either spot isn't safe, they both use the spawn itself as before.
     */
    private void placeSideBySide(final List<Player> team) {
        if (team.size() != 2)
            return;
        final Location spawn = this.getSpawnOf(team.get(0));
        if (spawn == null || spawn.getWorld() == null)
            return;

        // Sideways from the spawn's facing, snapped to the nearest axis
        final double yaw = Math.toRadians(spawn.getYaw());
        final double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        final int sx, sz;
        if (Math.abs(fx) >= Math.abs(fz)) { sx = 0; sz = 1; } else { sx = 1; sz = 0; }

        final Location left = spawn.clone().add(sx, 0, sz);
        final Location right = spawn.clone().add(-sx, 0, -sz);
        if (!this.isSafeSpawn(left) || !this.isSafeSpawn(right))
            return;

        this.spawnSpots.put(team.get(0).getUniqueId(), left);
        this.spawnSpots.put(team.get(1).getUniqueId(), right);
    }

    /** Room to stand (feet and head clear, no liquid), solid ground that won't hurt, and inside the arena. */
    private boolean isSafeSpawn(final Location loc) {
        final org.bukkit.block.Block feet = loc.getBlock();
        final org.bukkit.block.Block head = feet.getRelative(org.bukkit.block.BlockFace.UP);
        final org.bukkit.block.Block ground = feet.getRelative(org.bukkit.block.BlockFace.DOWN);
        if (!feet.isPassable() || !head.isPassable() || feet.isLiquid() || head.isLiquid())
            return false;
        if (!feet.getCollisionShape().getBoundingBoxes().isEmpty() || !head.getCollisionShape().getBoundingBoxes().isEmpty())
            return false;
        if (!ground.getType().isSolid() || ground.isLiquid())
            return false;
        switch (ground.getType()) {
            case MAGMA_BLOCK, CACTUS, CAMPFIRE, SOUL_CAMPFIRE, POWDER_SNOW -> { return false; }
            default -> {}
        }
        switch (feet.getType()) {
            case FIRE, SOUL_FIRE, SWEET_BERRY_BUSH, WITHER_ROSE, COBWEB, POWDER_SNOW, LAVA, WATER -> { return false; }
            default -> {}
        }
        return !Arena.ArenaUtils.WORLD_EDIT_SUPPORT || this.arena.equals(SpawnArena.arenas.of(loc));
    }

    /** True when {@code to} is this fighter's own spawn point, i.e. the plugin's start-of-fight teleport. */
    public boolean isSpawnTeleport(final Player fighter, final Location to) {
        final Location spawn = this.getSpawnOf(fighter);
        return spawn != null && to != null && spawn.getWorld().equals(to.getWorld()) && spawn.distanceSquared(to) < 1.0;
    }

    public void teleportToSpawn(final Player fighter) {
        final Location spawn = this.getSpawnOf(fighter);
        if (spawn != null)
            Threads.teleport(fighter, spawn);
    }

    // ── Visibility (MULTI arenas hide other matches) ────────────────────────

    private void performHideLogic() {
        for (final Player fighter : this.getMembers()) {
            for (final Player other : this.arena.getOtherPlayersInArena(this)) {
                if (!isAvailable(fighter) || !isAvailable(other))
                    continue;
                Threads.entity(fighter, () -> fighter.hidePlayer(SpawnArena.INSTANCE, other));
                Threads.entity(other, () -> other.hidePlayer(SpawnArena.INSTANCE, fighter));
            }
        }
    }

    private void performShowLogic() {
        for (final Player fighter : this.getMembers())
            this.showTo(fighter);
    }

    private void showTo(final Player fighter) {
        for (final Player other : this.arena.getOtherPlayers(this)) {
            if (!isAvailable(fighter) || !isAvailable(other))
                continue;
            Threads.entity(fighter, () -> fighter.showPlayer(SpawnArena.INSTANCE, other));
            Threads.entity(other, () -> other.showPlayer(SpawnArena.INSTANCE, fighter));
        }
    }

    protected void leaveParkour(final Player player) {
        if (!Bukkit.getPluginManager().isPluginEnabled("Parkour"))
            return;

        try {
            final Class<?> clazz = Class.forName("io.github.a5h73y.parkour.Parkour");
            final Method instanceMethod = clazz.getDeclaredMethod("getInstance");
            final Object main = instanceMethod.invoke(null);

            final Method playerManagerMethod = clazz.getDeclaredMethod("getPlayerManager");
            final Object playerManager = playerManagerMethod.invoke(main);

            final Method parkourSessionManagerMethod = clazz.getDeclaredMethod("getParkourSessionManager");
            final Object parkourSessionManager = parkourSessionManagerMethod.invoke(main);

            final Class<?> parkourSessionManagerClass = parkourSessionManager.getClass();
            final Method isPlayingMethod = parkourSessionManagerClass.getDeclaredMethod("isPlayingParkourCourse", Player.class);
            final boolean isPlaying = (boolean) isPlayingMethod.invoke(parkourSessionManager, player);

            if (isPlaying) {
                final Class<?> playerManagerClass = playerManager.getClass();
                final Method leaveCourseMethod = playerManagerClass.getDeclaredMethod("leaveCourse", Player.class);
                leaveCourseMethod.invoke(playerManager, player);
            }
        } catch (Exception e) {
            SpawnArena.INSTANCE.getLogger().warning("Couldn't remove " + player.getName() + " from their Parkour course: " + e);
        }
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof Fight fight))
            return false;
        return fight.uuid.equals(this.uuid);
    }

    @Override
    public int hashCode() {
        return this.uuid.hashCode();
    }
}
