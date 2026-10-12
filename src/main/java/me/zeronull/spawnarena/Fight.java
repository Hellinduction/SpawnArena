package me.zeronull.spawnarena;

import me.nahu.scheduler.wrapper.runnable.WrappedRunnable;
import me.nahu.scheduler.wrapper.task.WrappedTask;
import me.zeronull.spawnarena.events.ArenaProjectileDupeFix;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;

import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;

public class Fight {
    private static final ChatColor[] VIBRANT_COLORS = {
            ChatColor.RED,
            ChatColor.BLUE,
            ChatColor.GREEN,
            ChatColor.YELLOW,
            ChatColor.AQUA,
            ChatColor.WHITE,
            ChatColor.LIGHT_PURPLE,
            ChatColor.GRAY,
            ChatColor.GOLD,
            ChatColor.BLUE
    };

    UUID uuid = UUID.randomUUID();
    FightState fightState = FightState.COUNTDOWN;
    Arena arena;
    PlayerPreFightData preFightData1;
    PlayerPreFightData preFightData2;
    Player fighter1;
    Player fighter2;
    WrappedTask starting;
    ChatColor color;

    private Map<UUID, ChatColor> teams = new HashMap<>(); // UUID -> Original ChatColor

    public boolean bothFightersOnline() {
        return (fighter1.isOnline() && fighter2.isOnline());
    }

    public FightState getState() {
        return fightState;
    }

    /**
     * Cancels the fight. Pretty much just endFight without restoring inventories
     * Good for canceling without restoring
     */
    public void cancelFight() {
        this.fightState = FightState.ENDING;

        ArenaProjectileDupeFix.discardProjectiles(fighter1);
        ArenaProjectileDupeFix.discardProjectiles(fighter2);

        fighter1 = null;
        fighter2 = null;

        this.fightState = FightState.OVER;

        this.arena.removeFight(this);
        this.tryCancel(this.starting);
        this.performShowLogic();

//        this.arena.arenaState = ArenaState.EMPTY;
    }

    public boolean isFighter(Player player) {
        return this.getFighters().contains(player);
    }

    public void initiateFight(Player fighter1, Player fighter2, Arena arena) {
        this.arena = arena;
        arena.addFight(this);
        this.fighter1 = fighter1;
        this.fighter2 = fighter2;

        Plugin plugin = SpawnArena.getPlugin(SpawnArena.class);
        this.starting = new WrappedRunnable() {
            int counter = 5;

            @Override
            public void run() {
                final boolean onlineCheck = onlineCheck(this);

                if (onlineCheck)
                    return;

                if (counter == 0) {
                    startFight();
                    this.cancel();
                } else {
                    sendFightersMessage(ChatColor.translateAlternateColorCodes('&', "&6Starting in " + counter + "..."));
                    counter--;
                }
            }

        }.runTaskTimer(SpawnArena.scheduler, 20L, 20L);
    }

    private void tryCancel(final WrappedTask task) {
        if (task != null && !task.isCancelled())
            task.cancel();
    }

    private boolean onlineCheck(final WrappedRunnable runnable) {
        for (final Player fighter : this.getFighters()) {
            if (fighter.isOnline())
                continue;

            final String displayName = fighter.getDisplayName();
            this.getOtherFighter(fighter).sendMessage(ChatColor.translateAlternateColorCodes('&', "&c" + displayName + " has left... cancelling fight"));
            cancelFight();
            arena.queue.tryStartFight();
            runnable.cancel();
            return true;
        }

        return false;
    }

    private void sendFightersMessage(final String message) {
        for (final Player fighter : this.getFighters()) {
            if (!this.isAvailable(fighter))
                continue;

            fighter.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        }
    }

    private void performOnFighters(final Consumer<Player> action) {
        for (final Player fighter : this.getFighters()) {
            if (!this.isAvailable(fighter))
                continue;

            action.accept(fighter);
        }
    }

    private boolean isAvailable(final Player p) {
        return p != null && p.isOnline();
    }

    public List<Player> getFighters() {
        return Arrays.asList(this.fighter1, this.fighter2);
    }

    public Player getOtherFighter(final Player fighter) {
        for (final Player p : this.getFighters()) {
            if (p.equals(fighter))
                continue;

            return p;
        }

        return null; // The other fighter could not be found
    }

    public void startFight() {
        this.fightState = FightState.PRE_FIGHT_START;
        Arena.ArenaUtils.kickOutLingeringPlayers(this.arena);

        this.color = this.getRandomColor();

        this.performOnFighters(fighter -> fighter.setInvulnerable(false));
        this.performOnFighters(this::leaveParkour);
        this.performOnFighters(HumanEntity::closeInventory);
        this.performOnFighters(fighter -> Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), "invsave " + fighter.getName()));

        preFightData1 = new PlayerPreFightData(fighter1);
        preFightData2 = new PlayerPreFightData(fighter2);

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

        this.arena.teleportFighters(fighter1, fighter2);

        this.performHideLogic();
        this.fightState = FightState.IN_FIGHT;
    }

    private ChatColor getRandomColor() {
        final Random random = new Random();
        final int index = random.nextInt(VIBRANT_COLORS.length);
        return VIBRANT_COLORS[index];
    }

    public void clearTeams() {
        this.performOnFighters(fighter -> fighter.setGlowing(false));
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

    private void performHideLogic() {
        for (final Player fighter : this.getFighters()) {
            for (final Player other : this.arena.getOtherPlayersInArena(this)) {
                if (fighter == null || other == null)
                    continue;

                if (!fighter.isOnline() || !other.isOnline())
                    continue;

                fighter.hidePlayer(SpawnArena.INSTANCE, other);
                other.hidePlayer(SpawnArena.INSTANCE, fighter);
            }
        }
    }

    private void performShowLogic() {
        for (final Player fighter : this.getFighters()) {
            for (final Player other : this.arena.getOtherPlayers(this)) {
                if (fighter == null || other == null)
                    continue;

                if (!fighter.isOnline() || !other.isOnline())
                    continue;

                fighter.showPlayer(SpawnArena.INSTANCE, other);
                other.showPlayer(SpawnArena.INSTANCE, fighter);
            }
        }
    }

    /** This fighter's spawn point in the arena, or null if they aren't in this fight. */
    public Location getSpawnOf(final Player fighter) {
        return fighter.equals(this.fighter1) ? this.arena.spawnPoint1
                : fighter.equals(this.fighter2) ? this.arena.spawnPoint2 : null;
    }

    /** True when {@code to} is this fighter's own spawn point, i.e. the plugin's start-of-fight teleport. */
    public boolean isSpawnTeleport(final Player fighter, final Location to) {
        final Location spawn = this.getSpawnOf(fighter);

        return spawn != null && to != null && spawn.getWorld().equals(to.getWorld()) && spawn.distanceSquared(to) < 1.0;
    }

    public void teleportToSpawn(final Player fighter) {
        final UUID uuid = fighter.getUniqueId();

        if (this.fighter1 != null && this.fighter1.getUniqueId().equals(uuid))
            FoliaUtils.teleport(fighter, this.arena.spawnPoint1);
        else if (this.fighter2 != null && this.fighter2.getUniqueId().equals(uuid))
            FoliaUtils.teleport(fighter, this.arena.spawnPoint2);
    }

    /**
     * Ends the fight and restores inventories
     */
    public void endFight() {
        this.fightState = FightState.ENDING;

        ArenaProjectileDupeFix.discardProjectiles(fighter1);
        ArenaProjectileDupeFix.discardProjectiles(fighter2);

        // One failed restore must not leave the fight stuck in the arena forever;
        // the snapshot stays in prefights.yml for /restore
        for (final PlayerPreFightData data : new PlayerPreFightData[]{this.preFightData1, this.preFightData2}) {
            if (data == null)
                continue;
            try {
                data.restore();
            } catch (final Exception e) {
                SpawnArena.INSTANCE.getLogger().log(java.util.logging.Level.SEVERE,
                        "Couldn't restore " + data.username + " after their fight; use /restore " + data.username, e);
            }
        }

        this.fightState = FightState.OVER;
        this.arena.removeFight(this);
//        this.arena.arenaState = ArenaState.EMPTY;

//        this.arena.arenaState = ArenaState.EMPTY;
        this.arena.queue.tryStartFight();
        this.tryCancel(this.starting);
        this.performShowLogic();
        this.clearTeams();

        if (this.getArena() instanceof SavableArena && this.getArena().getArenaMode() == ArenaMode.SINGLE) {
            final SavableArena savableArena = (SavableArena) this.getArena();
            try {
                savableArena.resetArena();
            } catch (final Exception e) {
                SpawnArena.INSTANCE.getLogger().log(java.util.logging.Level.SEVERE,
                        "Couldn't reset arena " + savableArena.getArenaName(), e);
            }
        }
    }

    public void announceWinner(Player whoDied) {
        Player winner;
        Component winnerName;

        Player loser;
        Component loserName;

        if (fighter1.equals(whoDied)) {
            winner = fighter2;
            winnerName = fighter2.displayName();

            loser = fighter1;
            loserName = fighter1.displayName();
        } else {
            winner = fighter1;
            winnerName = fighter1.displayName();

            loser = fighter2;
            loserName = fighter2.displayName();
        }

        this.handleDeath(loser);
        final int ws = this.handleVictory(winner);

        final Component result = MiniMessage.miniMessage().deserialize("<dark_red><winner> beat <loser> in the <arena>arena<ws_str>!",
                Placeholder.component("winner", winnerName),
                Placeholder.component("loser", loserName),
                Placeholder.parsed("arena", "arena".equals(this.arena.getArenaName()) ? "" : this.arena.getArenaName() + " "),
                Placeholder.parsed("ws_str", ws > 2 ? String.format(" with a winstreak of <white>%s<dark_red>", ws) : "")
        );

        if (SpawnArena.settings == null || SpawnArena.settings.broadcastWins) {
            Bukkit.broadcast(result);
        } else {
            if (winner.isOnline()) winner.sendMessage(result);
            if (loser.isOnline()) loser.sendMessage(result);
        }

        if (winner.isOnline() && Bukkit.getPluginManager().isPluginEnabled("Vault"))
            WinRewards.reward(winner, loser, this.arena);
    }

    private void handleDeath(final Player loser) {
        SpawnArena.statsManager.recordLoss(loser, this.arena.arenaName);
    }

    public Arena getArena() {
        return this.arena;
    }

    /** Returns the new win streak. */
    private int handleVictory(final Player winner) {
        return SpawnArena.statsManager.recordWin(winner, this.arena.arenaName);
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof Fight fight))
            return false;

        return fight.uuid.equals(this.uuid);
    }

}
