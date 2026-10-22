package me.zeronull.spawnarena;

import de.exlll.configlib.ConfigLib;
import de.exlll.configlib.YamlConfigurationProperties;
import de.exlll.configlib.YamlConfigurations;
import me.zeronull.spawnarena.config.impl.ArenaConfig;
import org.bukkit.Location;

import java.io.File;
import java.util.function.Consumer;

public final class SavableArena extends Arena {
    public static final File ARENAS_DIR = new File(SpawnArena.getPlugin(SpawnArena.class).getDataFolder(), "arenas");
    private final String configName;

    public SavableArena(final String arenaName) {
        super(arenaName);
        this.configName = String.format("%s.yml", super.getArenaName());
    }

    public static SavableArena deserialize(final File configFile) {
        final ArenaConfig config = getArenaConfig(configFile);
        return deserialize(config);
    }

    public static SavableArena deserialize(final ArenaConfig config) {
        final SavableArena arena = new SavableArena(config.arenaName);

        arena.setSpawnPoint1(config.spawnPoint1);
        arena.setSpawnPoint2(config.spawnPoint2);
        arena.exitLocation = config.exitLocation;

        arena.setShouldClearItems(config.shouldClearItems);
        arena.setAllowDamage(config.allowDamage);
        arena.setAllowPvp(config.allowPvp);
        arena.setDeathOnTouchLiquid(config.deathOnTouchLiquid);
        arena.setWinOnStepStonePressurePlate(config.winOnStepStonePressurePlate);
        arena.setGiveKnockBackStick(config.giveKnockBackStick);

        return arena;
    }

    public static ArenaConfig getArenaConfig(final File configFile) {
        final YamlConfigurationProperties properties = ConfigLib.BUKKIT_DEFAULT_PROPERTIES.toBuilder()
                .build();

        final ArenaConfig config = YamlConfigurations.load(
                configFile.toPath(),
                ArenaConfig.class,
                properties
        );

        return config;
    }

    /**
     * Rewrites an arena's file with every current option and comment, keeping its values: options added in newer
     * versions show up, and old comments get replaced. Done when arenas load (startup and /arenareload).
     */
    public static void refreshArenaConfig(final File configFile) {
        try {
            createArenaConfig(configFile);
        } catch (final RuntimeException e) {
            SpawnArena.INSTANCE.getLogger().warning("Couldn't refresh " + configFile.getName() + " with the current options: " + e.getMessage());
        }
    }

    public static void createArenaConfig(final File configFile) {
        final YamlConfigurationProperties properties = ConfigLib.BUKKIT_DEFAULT_PROPERTIES.toBuilder()
                .build();

        YamlConfigurations.update(
                configFile.toPath(),
                ArenaConfig.class,
                properties
        );
    }

    public static void saveArenaConfig(final File configFile, final ArenaConfig config) {
        final YamlConfigurationProperties properties = ConfigLib.BUKKIT_DEFAULT_PROPERTIES.toBuilder()
                .build();

        YamlConfigurations.save(
                configFile.toPath(),
                ArenaConfig.class,
                config,
                properties
        );
    }

    @Override
    public void setSpawnPoint1(final Location spawnPoint1) {
        this.save(config -> config.spawnPoint1 = spawnPoint1);
        super.setSpawnPoint1(spawnPoint1);
    }

    @Override
    public void setSpawnPoint2(final Location spawnPoint2) {
        this.save(config -> config.spawnPoint2 = spawnPoint2);
        super.setSpawnPoint2(spawnPoint2);
    }

    @Override
    public ArenaOptions setShouldClearItems(final boolean value) {
        this.save(config -> config.shouldClearItems = value);
        return super.setShouldClearItems(value);
    }

    @Override
    public ArenaOptions setAllowDamage(final boolean value) {
        this.save(config -> config.allowDamage = value);
        return super.setAllowDamage(value);
    }

    @Override
    public ArenaOptions setAllowPvp(final boolean value) {
        this.save(config -> config.allowPvp = value);
        return super.setAllowPvp(value);
    }

    @Override
    public ArenaOptions setDeathOnTouchLiquid(final boolean value) {
        this.save(config -> config.deathOnTouchLiquid = value);
        return super.setDeathOnTouchLiquid(value);
    }

    @Override
    public ArenaOptions setWinOnStepStonePressurePlate(final boolean value) {
        this.save(config -> config.winOnStepStonePressurePlate = value);
        return super.setWinOnStepStonePressurePlate(value);
    }

    @Override
    public ArenaOptions setGiveKnockBackStick(final boolean value) {
        this.save(config -> config.giveKnockBackStick = value);
        return super.setGiveKnockBackStick(value);
    }

    public void setExitLocation(final Location location) {
        this.save(config -> config.exitLocation = location);
        this.exitLocation = location;
    }

    public ArenaOptions setPasteLocation(final Location location) {
        this.save(config -> config.pasteLocation = location);
        return this;
    }

    public ArenaOptions setSchematic(final String schematicPath) {
        this.save(config -> config.schematic = schematicPath);
        return this;
    }

    /** Re-reads this arena's file and applies it without writing anything back. */
    /** Read on reload while a fight is running here, applied when the arena's last fight ends. */
    private volatile ArenaConfig pendingReload;

    /**
     * Re-reads this arena's file (errors are thrown now). If a fight is running here, the new settings wait
     * until it's over, so e.g. crystal PvP, the mode or the spawns can't change mid-fight. Returns true if
     * applied now, false if waiting.
     */
    public boolean reloadFromDisk() {
        CrystalPvp.forgetSnapshot(this);
        refreshArenaConfig(this.getConfigFile());
        final ArenaConfig config = getArenaConfig(this.getConfigFile());
        final boolean becameFfa;
        // Checked and applied under the arena's lock, which joining a free-for-all and starting a match also take,
        // so nothing can start between "it's empty" and the new settings
        synchronized (this) {
            if (!this.getFights().isEmpty()) {
                this.pendingReload = config;
                return false;
            }
            becameFfa = this.apply(config);
        }
        if (becameFfa)
            this.dropQueueForFfa();
        return true;
    }

    /** Called when a fight leaves this arena: applies a reload that was waiting for the arena to be free. */
    @Override
    public void removeFight(final Fight fight) {
        final ArenaConfig pending;
        final boolean becameFfa;
        synchronized (this) {
            super.removeFight(fight);
            pending = this.getFights().isEmpty() ? this.pendingReload : null;
            if (pending != null)
                this.pendingReload = null;
            becameFfa = pending != null && this.apply(pending);
        }
        if (pending != null)
            SpawnArena.INSTANCE.getLogger().info("Applied the reloaded settings for arena " + this.getArenaName() + " now that its fight is over.");
        if (becameFfa)
            this.dropQueueForFfa();
    }

    /** Anyone waiting for a normal match here can't get one now that it's a free-for-all. */
    private void dropQueueForFfa() {
        if (this.queue == null)
            return;
        for (final org.bukkit.entity.Player p : this.queue.clear())
            p.sendMessage(org.bukkit.ChatColor.RED + "The " + this.getArenaName() + " arena is now a free-for-all, so you were taken out of its queue. "
                    + "Run /joinarenaqueue " + this.getArenaName() + " to go in.");
    }

    /** Returns true if this switched free-for-all on. Call holding the arena's lock. */
    private boolean apply(final ArenaConfig config) {
        final boolean wasFfa = this.isFfa();
        super.setSpawnPoint1(config.spawnPoint1);
        super.setSpawnPoint2(config.spawnPoint2);
        this.exitLocation = config.exitLocation;
        super.setShouldClearItems(config.shouldClearItems);
        super.setAllowDamage(config.allowDamage);
        super.setAllowPvp(config.allowPvp);
        super.setDeathOnTouchLiquid(config.deathOnTouchLiquid);
        super.setWinOnStepStonePressurePlate(config.winOnStepStonePressurePlate);
        super.setGiveKnockBackStick(config.giveKnockBackStick);
        super.setArenaMode(config.arenaMode);
        super.setCrystalPvp(config.crystalPvp);
        super.setFfa(config.ffa);
        super.setRandomSumo(config.randomSumo);
        this.setWinReward(config.winReward, config.winRewardCooldownMinutes);
        this.setLeaderReward(config.leaderRewardMultiplier, config.leaderRewardCooldownMinutes);
        this.setPodiumRewards(config.secondRewardMultiplier, config.secondRewardCooldownMinutes,
                config.thirdRewardMultiplier, config.thirdRewardCooldownMinutes);
        this.setEgapCooldownSeconds(config.egapCooldownSeconds);
        this.setDisplayName(config.displayName);
        return config.ffa && !wasFfa;
    }

    public File getConfigFile() {
        final File configFile = new File(ARENAS_DIR, this.configName);
        return configFile;
    }

    public void save(final Consumer<ArenaConfig> action) {
        final File configFile = this.getConfigFile();
        final ArenaConfig config = getArenaConfig(configFile);

        action.accept(config);
        saveArenaConfig(configFile, config);
    }

    /**
     * Pastes the arena's schematic back after a fight. Crystal arenas get the whole thing, air included, as a
     * safety net for anything the crystal cleanup missed; other arenas only get their blocks put back (air is
     * skipped, like //paste -a), which fixes anything broken and is quicker. Completes once the paste is done,
     * straight away if the arena has no schematic.
     */
    public java.util.concurrent.CompletableFuture<Void> resetArena() {
        final ArenaConfig config = getArenaConfig(this.getConfigFile());
        final Location loc = config.pasteLocation;

        if (loc == null)
            return java.util.concurrent.CompletableFuture.completedFuture(null);

        if (config.schematic == null || config.schematic.isEmpty())
            return java.util.concurrent.CompletableFuture.completedFuture(null);

        // Older configs store just the schematic's name; newer ones its path
        File schematic = new File(config.schematic);
        if (!schematic.exists())
            schematic = WorldEditUtils.getSchematic(config.schematic);

        if (!schematic.exists())
            throw new IllegalStateException("Schematic '" + config.schematic + "' for arena " + this.getArenaName()
                    + " was not found in the WorldEdit/FastAsyncWorldEdit schematics folders");

        return WorldEditUtils.paste(loc, schematic, !this.isCrystalPvp());
    }
}