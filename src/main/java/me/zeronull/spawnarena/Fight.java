package me.zeronull.spawnarena;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.zeronull.spawnarena.events.ArenaProjectileDupeFix;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;

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
    private final Map<UUID, Location> spawnSpots = new java.util.concurrent.ConcurrentHashMap<>();
    /** Coin flip per match for which team gets which spawn, so join order doesn't decide sides */
    private final boolean swapSides = java.util.concurrent.ThreadLocalRandom.current().nextBoolean();
    /** Players still in the match; eliminated players are restored and leave this set. */
    private final Set<Player> alive = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Map<UUID, PlayerPreFightData> preFightData = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<UUID> restored = java.util.concurrent.ConcurrentHashMap.newKeySet();
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
    private final Set<UUID> quitters = java.util.concurrent.ConcurrentHashMap.newKeySet();

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

        if (this.mode == FightMode.TWO_V_TWO)
            this.announceTeams();

        final int[] counter = {5};
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
        // Crystal arenas: remember how the arena looks before anyone can change it
        if (this.arena.isCrystalPvp())
            CrystalPvp.takeSnapshot(this);

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
        spots.completeOnTimeout(null, 3, java.util.concurrent.TimeUnit.SECONDS).whenComplete((ignored, error) -> {
            if (error != null)
                SpawnArena.INSTANCE.getLogger().log(java.util.logging.Level.WARNING, "Couldn't place 2v2 teammates side by side", error);
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

        // Tridents thrown before the match (still flying or stuck somewhere) go back into the inventory
        // before it's saved; otherwise a loyalty trident returns into the fight inventory and is lost
        // when that inventory is replaced at the end. Each trident is taken on its own thread.
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

        if (recalled.isEmpty()) {
            this.prepareAndSend(fighter, List.of());
            return;
        }
        // Safety net: a trident whose thread never runs the task (e.g. it unloaded) mustn't stall the start
        final java.util.concurrent.CompletableFuture<Void> all = java.util.concurrent.CompletableFuture
                .allOf(recalled.toArray(new java.util.concurrent.CompletableFuture[0]))
                .completeOnTimeout(null, 2, java.util.concurrent.TimeUnit.SECONDS);
        all.whenComplete((ignored, error) -> {
            final List<ItemStack> items = new ArrayList<>();
            for (final java.util.concurrent.CompletableFuture<ItemStack> f : recalled)
                if (f.isDone() && !f.isCompletedExceptionally() && f.join() != null)
                    items.add(f.join());
            Threads.entity(fighter, () -> this.prepareAndSend(fighter, items));
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

        if (this.arena.isGiveKnockBackStick()) {
            // A fish rather than a stick: Litematica uses the stick as its tool item
            final ItemStack fish = new ItemStack(Material.TROPICAL_FISH);
            final Enchantment knockback = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("knockback"));
            if (knockback != null)
                fish.addUnsafeEnchantment(knockback, 1);
            fish.editMeta(meta -> meta.displayName(MiniMessage.miniMessage().deserialize(
                    "<!italic><aqua><obfuscated>|</obfuscated></aqua><yellow><bold>Slap Fish</bold></yellow><aqua><obfuscated>|</obfuscated></aqua>")));
            fighter.getInventory().addItem(fish);
        }

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

        // Crystal arenas: take out everything placed during the fight
        CrystalPvp.cleanup(this);

        // Reset before the next match is started, so the paste isn't racing its countdown
        if (this.getArena() instanceof SavableArena savableArena && this.getArena().getArenaMode() == ArenaMode.SINGLE) {
            java.util.concurrent.CompletableFuture<Void> reset;
            try {
                reset = savableArena.resetArena();
            } catch (final Exception e) {
                SpawnArena.INSTANCE.getLogger().log(java.util.logging.Level.SEVERE,
                        "Couldn't reset arena " + savableArena.getArenaName(), e);
                reset = java.util.concurrent.CompletableFuture.completedFuture(null);
            }
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
    private void restore(final Player player) {
        // Died for real: put back once they've respawned (a dead player can't be teleported or given items)
        if (player.isOnline() && player.isDead()) {
            Threads.globalLater(() -> Threads.entity(player, () -> this.restore(player)), 5L);
            return;
        }
        try {
            FightGlow.remove(player);
            me.zeronull.spawnarena.events.EgapCooldown.clear(player);
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

        if (this.duel) {
            // Duels don't count for anything, so only the duelists are told
            final Component message = MiniMessage.miniMessage().deserialize("<dark_red><winner> beat <loser> in a duel in the <arena>arena!",
                    Placeholder.component("winner", displayNames(winners)),
                    Placeholder.component("loser", displayNames(losers)),
                    Placeholder.parsed("arena", arenaPart));
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
        }

        if (endedStreaks.isEmpty()) {
            broadcast(MiniMessage.miniMessage().deserialize("<dark_red><winner> beat <loser> in the <arena>arena<ws_str>!",
                    Placeholder.component("winner", displayNames(winners)),
                    Placeholder.component("loser", displayNames(losers)),
                    Placeholder.parsed("arena", arenaPart),
                    Placeholder.parsed("ws_str", streakPart(winnerStreaks))),
                    winners, losers);
        } else {
            // A long streak just ended: that replaces the usual result message
            for (final Map.Entry<Player, Integer> ended : endedStreaks.entrySet())
                this.announceStreakEnded(ended.getKey(), ended.getValue(), winners, losers);
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
    private void announceStreakEnded(final Player loser, final int streak, final List<Player> winners, final List<Player> losers) {
        final Component message = MiniMessage.miniMessage().deserialize(
                "<dark_red><bold>STREAK ENDED!</bold></dark_red> <gold><winner></gold> <red>ended <loser><possessive> "
                        + "<white><bold><streak></bold></white>-win streak in <arena>!",
                Placeholder.component("loser", loser.displayName()),
                // "Chris'" rather than "Chris's"
                Placeholder.unparsed("possessive", Grammar.possessiveSuffix(loser.getName())),
                Placeholder.component("winner", displayNames(winners)),
                Placeholder.unparsed("streak", String.valueOf(streak)),
                Placeholder.unparsed("arena", Arena.displayName(this.arena.getArenaName())));

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
     * " with a winstreak of 5" (1v1), or " with winstreaks of 5 & 1" (2v2, in the same order as the winners'
     * names). Empty unless a winner is on a streak of more than 2.
     */
    private static String streakPart(final List<Integer> streaks) {
        if (streaks.stream().noneMatch(s -> s > 2))
            return "";
        if (streaks.size() == 1)
            return " with a winstreak of <white>" + streaks.get(0) + "<dark_red>";
        final List<String> parts = new ArrayList<>();
        for (final int s : streaks)
            parts.add("<white>" + s + "<dark_red>");
        return " with winstreaks of " + String.join(" & ", parts);
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
    private static List<Player> broadcast(final Component message, final List<Player> winners, final List<Player> losers) {
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

    private void leaveParkour(final Player player) {
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
