package me.zeronull.spawnarena;

import me.nahu.scheduler.wrapper.runnable.WrappedRunnable;
import me.nahu.scheduler.wrapper.task.WrappedTask;
import me.zeronull.spawnarena.events.ArenaProjectileDupeFix;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.HumanEntity;
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
    FightState fightState = FightState.COUNTDOWN;
    /** Duels (from a duel request) never change stats or pay rewards. */
    private boolean duel;

    Arena arena;
    private FightMode mode = FightMode.ONE_V_ONE;
    private final List<Player> teamA = new ArrayList<>();
    private final List<Player> teamB = new ArrayList<>();
    /** Players still in the match; eliminated players are restored and leave this set. */
    private final Set<Player> alive = new LinkedHashSet<>();
    private final Map<UUID, PlayerPreFightData> preFightData = new HashMap<>();
    private final Set<UUID> restored = new HashSet<>();
    /** Where each player was when they went out (before being restored and teleported away). */
    private final Map<UUID, Location> eliminatedAt = new HashMap<>();
    WrappedTask starting;

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
        return new ArrayList<>(this.alive);
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

        this.starting = new WrappedRunnable() {
            int counter = 5;

            @Override
            public void run() {
                if (onlineCheck(this))
                    return;

                if (counter == 0) {
                    startFight();
                    this.cancel();
                } else {
                    sendFightersMessage("&6Starting in " + counter + "...");
                    counter--;
                }
            }
        }.runTaskTimer(SpawnArena.scheduler, 20L, 20L);
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

    private void tryCancel(final WrappedTask task) {
        if (task != null && !task.isCancelled())
            task.cancel();
    }

    private boolean onlineCheck(final WrappedRunnable runnable) {
        for (final Player fighter : this.getMembers()) {
            if (fighter.isOnline())
                continue;

            runnable.cancel();
            this.cancelBecause(fighter, fighter.getName() + " has left, so the fight was cancelled.");
            return true;
        }
        return false;
    }

    /**
     * Cancels a match still in its countdown because {@code culprit} left, teleported or wandered off.
     * Everyone else goes straight back into the queue (front of the line) instead of losing their spot.
     */
    public void cancelBecause(final Player culprit, final String messageForOthers) {
        final List<Player> others = new ArrayList<>();
        for (final Player p : this.getMembers())
            if (!p.equals(culprit) && p.isOnline())
                others.add(p);

        this.cancelFight();

        final boolean requeue = !this.duel && (SpawnArena.settings == null || SpawnArena.settings.arenasOpen);
        for (final Player p : others)
            p.sendMessage(ChatColor.RED + messageForOthers + (requeue ? ChatColor.GRAY + " You've been put back in the queue." : ""));

        if (requeue)
            this.arena.queue.requeue(others, this.mode);
        else
            this.arena.queue.tryStartFight();
    }

    private void sendFightersMessage(final String message) {
        for (final Player fighter : this.getFighters())
            if (isAvailable(fighter))
                fighter.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    private void performOnFighters(final Consumer<Player> action) {
        for (final Player fighter : this.getFighters())
            if (isAvailable(fighter))
                action.accept(fighter);
    }

    private static boolean isAvailable(final Player p) {
        return p != null && p.isOnline();
    }

    /** Cancels a match that hasn't started (or can't continue) without restoring inventories. */
    public void cancelFight() {
        this.fightState = FightState.ENDING;

        for (final Player p : this.getMembers())
            ArenaProjectileDupeFix.discardProjectiles(p);

        this.alive.clear();
        this.fightState = FightState.OVER;

        this.arena.removeFight(this);
        this.tryCancel(this.starting);
        this.performShowLogic();
    }

    public void startFight() {
        this.fightState = FightState.PRE_FIGHT_START;
        Arena.ArenaUtils.kickOutLingeringPlayers(this.arena);

        // Fighters' pending duel requests are void once they're in a fight (cleared silently, before
        // the teleport in, so they don't get a "cancelled because you teleported" message)
        this.performOnFighters(DuelManager::clear);

        this.performOnFighters(fighter -> fighter.setInvulnerable(false));
        this.performOnFighters(this::leaveParkour);
        this.performOnFighters(HumanEntity::closeInventory);
        // Extra inventory backup through an inventory-saving plugin, if the server has one with /invsave
        if (Bukkit.getServer().getCommandMap().getCommand("invsave") != null)
            this.performOnFighters(fighter -> Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), "invsave " + fighter.getName()));

        // Tridents thrown before the match (still flying or stuck somewhere) go back into the inventory
        // before it's saved; otherwise a loyalty trident returns into the fight inventory and is lost
        // when that inventory is replaced at the end
        final Map<UUID, List<ItemStack>> didntFit = new HashMap<>();
        for (final Player fighter : this.getFighters())
            didntFit.put(fighter.getUniqueId(), recallThrownTridents(fighter));

        for (final Player fighter : this.getFighters()) {
            final PlayerPreFightData data = new PlayerPreFightData(fighter);
            data.addExtraItems(didntFit.getOrDefault(fighter.getUniqueId(), List.of()));
            this.preFightData.put(fighter.getUniqueId(), data);
        }

        this.performOnFighters(fighter -> fighter.setGameMode(GameMode.SURVIVAL));
        this.performOnFighters(fighter -> fighter.setHealth(fighter.getMaxHealth()));
        this.performOnFighters(fighter -> fighter.setFoodLevel(20));

        if (this.arena.isShouldClearItems()) {
            this.performOnFighters(fighter -> fighter.getInventory().clear());
            this.performOnFighters(fighter -> {
                for (final PotionEffect effect : new ArrayList<>(fighter.getActivePotionEffects()))
                    fighter.removePotionEffect(effect.getType());
            });
        }

        if (this.arena.isGiveKnockBackStick()) {
            // A fish rather than a stick: Litematica uses the stick as its tool item
            final ItemStack fish = new ItemStack(Material.TROPICAL_FISH);
            final Enchantment knockback = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("knockback"));
            if (knockback != null)
                fish.addUnsafeEnchantment(knockback, 1);

            this.performOnFighters(fighter -> fighter.getInventory().addItem(fish));
        }

        for (final Player fighter : this.getFighters())
            this.arena.teleportFighter(fighter, this.getSpawnOf(fighter));

        if (this.mode == FightMode.TWO_V_TWO)
            TeamPicker.remember(this.teamA, this.teamB);

        this.performHideLogic();
        this.fightState = FightState.IN_FIGHT;
    }

    /**
     * Removes every trident this player has thrown that's still out in the world and puts it back in their
     * inventory. Returns whatever didn't fit (held and given back after the fight).
     */
    private static List<ItemStack> recallThrownTridents(final Player player) {
        final List<ItemStack> didntFit = new ArrayList<>();
        for (final World world : Bukkit.getWorlds()) {
            for (final org.bukkit.entity.Trident trident : world.getEntitiesByClass(org.bukkit.entity.Trident.class)) {
                if (!(trident.getShooter() instanceof Player shooter) || !shooter.getUniqueId().equals(player.getUniqueId()))
                    continue;
                if (trident.getPickupStatus() != org.bukkit.entity.AbstractArrow.PickupStatus.ALLOWED)
                    continue; // e.g. creative throws: nothing to give back

                final ItemStack item = trident.getItemStack();
                trident.remove();
                didntFit.addAll(player.getInventory().addItem(item).values());
            }
        }
        return didntFit;
    }

    /**
     * Takes a player out of the match: their team loses once nobody on it is left. Called for every way
     * of losing — death, liquid, ring-out, teleporting out, quitting.
     */
    public void eliminate(final Player player) {
        if (this.fightState != FightState.IN_FIGHT && this.fightState != FightState.PRE_FIGHT_START)
            return;
        if (!this.alive.remove(player))
            return;

        this.eliminatedAt.put(player.getUniqueId(), player.getLocation().clone());
        ArenaProjectileDupeFix.discardProjectiles(player);
        final List<Player> team = getTeamOf(player);
        final boolean teamOut = team.stream().noneMatch(this.alive::contains);

        if (teamOut) {
            this.finish(otherTeam(team), team);
            return;
        }

        // Teammate still fighting: this player is out now, the match goes on
        this.restore(player);
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
        this.finish(winners, otherTeam(winners));
    }

    private void finish(final List<Player> winners, final List<Player> losers) {
        this.announceResult(new ArrayList<>(winners), new ArrayList<>(losers));
        this.endFight();
    }

    /** Ends the match and restores everyone not yet restored. */
    public void endFight() {
        this.fightState = FightState.ENDING;

        for (final Player p : this.getMembers()) {
            ArenaProjectileDupeFix.discardProjectiles(p);
            this.restore(p);
        }
        this.alive.clear();

        this.fightState = FightState.OVER;
        this.arena.removeFight(this);

        this.tryCancel(this.starting);
        this.performShowLogic();

        // Reset before the next match is started, so the paste isn't racing its countdown
        if (this.getArena() instanceof SavableArena savableArena && this.getArena().getArenaMode() == ArenaMode.SINGLE) {
            try {
                savableArena.resetArena();
            } catch (final Exception e) {
                SpawnArena.INSTANCE.getLogger().log(java.util.logging.Level.SEVERE,
                        "Couldn't reset arena " + savableArena.getArenaName(), e);
            }
        }

        this.arena.queue.tryStartFight();
    }

    /** Restores a member's pre-fight state once; a failure is logged and never leaves the match stuck. */
    private void restore(final Player player) {
        if (!this.restored.add(player.getUniqueId()))
            return;
        final PlayerPreFightData data = this.preFightData.get(player.getUniqueId());
        if (data == null)
            return;
        try {
            data.restore();
        } catch (final Exception e) {
            SpawnArena.INSTANCE.getLogger().log(java.util.logging.Level.SEVERE,
                    "Couldn't restore " + data.username + " after their fight; use /restore " + data.username, e);
        }
    }

    // ── Results ─────────────────────────────────────────────────────────────

    private void announceResult(final List<Player> winners, final List<Player> losers) {
        final String arenaPart = "arena".equals(this.arena.getArenaName()) ? "" : this.arena.getArenaName() + " ";

        if (this.duel) {
            broadcast(MiniMessage.miniMessage().deserialize("<dark_red><winner> beat <loser> in a duel in the <arena>arena!",
                    Placeholder.component("winner", displayNames(winners)),
                    Placeholder.component("loser", displayNames(losers)),
                    Placeholder.parsed("arena", arenaPart)), winners, losers);
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

        int ws = 0;
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
                ws = Math.max(ws, SpawnArena.statsManager.recordWin(winner, this.arena.arenaName));
        }

        if (endedStreaks.isEmpty()) {
            // The win streak is only shown for 1v1
            final boolean showStreak = this.mode == FightMode.ONE_V_ONE && ws > 2;
            broadcast(MiniMessage.miniMessage().deserialize("<dark_red><winner> beat <loser> in the <arena>arena<ws_str>!",
                    Placeholder.component("winner", displayNames(winners)),
                    Placeholder.component("loser", displayNames(losers)),
                    Placeholder.parsed("arena", arenaPart),
                    Placeholder.parsed("ws_str", showStreak ? String.format(" with a winstreak of <white>%s<dark_red>", ws) : "")),
                    winners, losers);
        } else {
            // A long streak just ended: that replaces the usual result message
            for (final Map.Entry<Player, Integer> ended : endedStreaks.entrySet())
                this.announceStreakEnded(ended.getKey(), ended.getValue(), winners, losers);
        }

        if (Bukkit.getPluginManager().isPluginEnabled("Vault"))
            for (final Player winner : winners)
                if (winner.isOnline())
                    WinRewards.reward(winner, losers, this.arena, alts);

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
        if (where != null && where.getWorld() != null)
            where.getWorld().strikeLightningEffect(where);
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
        for (final Player p : Bukkit.getOnlinePlayers())
            if (!fighters.contains(p) && WinMessagePrefs.wantsWinMessages(p)) {
                p.sendMessage(message);
                received.add(p);
            }
        return received;
    }

    // ── Spawns ──────────────────────────────────────────────────────────────

    /** This fighter's spawn point: spawn 1 for team A, spawn 2 for team B; null if not in this match. */
    public Location getSpawnOf(final Player fighter) {
        if (this.teamA.contains(fighter)) return this.arena.spawnPoint1;
        if (this.teamB.contains(fighter)) return this.arena.spawnPoint2;
        return null;
    }

    /** True when {@code to} is this fighter's own spawn point, i.e. the plugin's start-of-fight teleport. */
    public boolean isSpawnTeleport(final Player fighter, final Location to) {
        final Location spawn = this.getSpawnOf(fighter);
        return spawn != null && to != null && spawn.getWorld().equals(to.getWorld()) && spawn.distanceSquared(to) < 1.0;
    }

    public void teleportToSpawn(final Player fighter) {
        final Location spawn = this.getSpawnOf(fighter);
        if (spawn != null)
            FoliaUtils.teleport(fighter, spawn);
    }

    // ── Visibility (MULTI arenas hide other matches) ────────────────────────

    private void performHideLogic() {
        for (final Player fighter : this.getMembers()) {
            for (final Player other : this.arena.getOtherPlayersInArena(this)) {
                if (!isAvailable(fighter) || !isAvailable(other))
                    continue;
                fighter.hidePlayer(SpawnArena.INSTANCE, other);
                other.hidePlayer(SpawnArena.INSTANCE, fighter);
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
            fighter.showPlayer(SpawnArena.INSTANCE, other);
            other.showPlayer(SpawnArena.INSTANCE, fighter);
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
