package me.zeronull.spawnarena.config;

import de.exlll.configlib.YamlConfigurationProperties;
import de.exlll.configlib.YamlConfigurations;
import me.zeronull.spawnarena.*;
import me.zeronull.spawnarena.config.impl.ArenaConfig;
import me.zeronull.spawnarena.config.impl.DatabaseConfig;
import me.zeronull.spawnarena.config.impl.PreFightConfig;
import me.zeronull.spawnarena.config.impl.SettingsConfig;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ConfigHandler {
    private static ConfigHandler config;

    private ConfigHandler() {
    }

    public static ConfigHandler getInstance() {
        if (!(config instanceof ConfigHandler)) {
            config = new ConfigHandler();
        }

        return config;
    }

    // prefights.yml is kept in memory and written by one background thread: a slow disk (or antivirus holding
    // the file) once froze the whole server for 20+ seconds when this was saved on the main thread
    private PreFightConfig preFights;
    private final java.util.concurrent.ExecutorService preFightWriter = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "SpawnArena prefights.yml writer");
        t.setDaemon(true);
        return t;
    });
    private final java.util.concurrent.atomic.AtomicReference<byte[]> pendingPreFights = new java.util.concurrent.atomic.AtomicReference<>();

    private static Path preFightFile() {
        return new File(SpawnArena.INSTANCE.getDataFolder(), "prefights.yml").toPath();
    }

    /** Serializes now (cheap, no disk), writes in the background. Only the newest snapshot gets written. */
    public synchronized void savePreFightConfig(PreFightConfig config) {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        YamlConfigurations.write(out, PreFightConfig.class, config, PreFightConfig.PROPERTIES);
        if (this.pendingPreFights.getAndSet(out.toByteArray()) == null)
            this.preFightWriter.execute(this::writePendingPreFights);
    }

    private void writePendingPreFights() {
        final byte[] bytes = this.pendingPreFights.getAndSet(null);
        if (bytes == null)
            return;
        final Path file = preFightFile();
        final Path tmp = file.resolveSibling("prefights.yml.tmp");
        try {
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.write(tmp, bytes);
            // Swapped in whole, so a crash mid-write never leaves a half-written file (where the filesystem allows it)
            try {
                java.nio.file.Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (final java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final Exception e) {
            SpawnArena.INSTANCE.getLogger().log(java.util.logging.Level.SEVERE, "Couldn't save prefights.yml", e);
        }
    }

    /** Waits for the last save to reach the disk (on shutdown). */
    public void flushPreFights() {
        this.preFightWriter.shutdown();
        try {
            if (!this.preFightWriter.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS))
                SpawnArena.INSTANCE.getLogger().warning("prefights.yml took over 30s to save on shutdown");
        } catch (final InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        this.writePendingPreFights(); // in case the writer was stuck and never picked the last one up
    }

    /** The saved pre-fight data (read from disk once, then kept in memory). Never null. */
    public synchronized PreFightConfig getPreFightConfig() {
        if (this.preFights == null) {
            final Path file = preFightFile();
            this.preFights = file.toFile().exists()
                    ? YamlConfigurations.load(file, PreFightConfig.class, PreFightConfig.PROPERTIES)
                    : new PreFightConfig();
            // Read from other threads too (Folia, /restore); entries that couldn't be read come back as null
            final java.util.Map<UUID, PlayerPreFightData> map = new java.util.concurrent.ConcurrentHashMap<>();
            if (this.preFights.map != null)
                this.preFights.map.forEach((uuid, data) -> { if (uuid != null && data != null) map.put(uuid, data); });
            this.preFights.map = map;
        }
        return this.preFights;
    }

    public List<Arena> registerArenasFromConfig() {
        final List<Arena> arenas = new ArrayList<>();
        final File[] arenaConfigs = SavableArena.ARENAS_DIR.listFiles();

        if (arenaConfigs == null || arenaConfigs.length == 0)
            return new ArrayList<>();

        for (final File configFile : arenaConfigs) {
            final ArenaConfig config = SavableArena.getArenaConfig(configFile);
            final Arena arena = SavableArena.deserialize(config);

            final ArenaQueue queue = new ArenaQueue(arena);
            arena.setQueue(queue);

            arena.setShouldClearItems(config.shouldClearItems);
            arena.setAllowDamage(config.allowDamage);
            arena.setAllowPvp(config.allowPvp);
            arena.setDeathOnTouchLiquid(config.deathOnTouchLiquid);
            arena.setWinOnStepStonePressurePlate(config.winOnStepStonePressurePlate);
            arena.setGiveKnockBackStick(config.giveKnockBackStick);
            arena.setArenaMode(config.arenaMode);
            arena.setCrystalPvp(config.crystalPvp);
            arena.setFfa(config.ffa);
            arena.setRandomSumo(config.randomSumo);
            arena.setWinReward(config.winReward, config.winRewardCooldownMinutes);
            arena.setLeaderReward(config.leaderRewardMultiplier, config.leaderRewardCooldownMinutes);
            arena.setPodiumRewards(config.secondRewardMultiplier, config.secondRewardCooldownMinutes,
                    config.thirdRewardMultiplier, config.thirdRewardCooldownMinutes);
            arena.setEgapCooldownSeconds(config.egapCooldownSeconds);

            arenas.add(arena);
        }

        return arenas;
    }

    public SettingsConfig getSettingsConfig() {
        final SpawnArena plugin = SpawnArena.getPlugin(SpawnArena.class);
        final java.nio.file.Path file = new File(plugin.getDataFolder(), "settings.yml").toPath();
        YamlConfigurations.update(file, SettingsConfig.class, SettingsConfig.PROPERTIES);
        return YamlConfigurations.load(file, SettingsConfig.class, SettingsConfig.PROPERTIES);
    }

    public void saveSettingsConfig(final SettingsConfig settings) {
        final SpawnArena plugin = SpawnArena.getPlugin(SpawnArena.class);
        final java.nio.file.Path file = new File(plugin.getDataFolder(), "settings.yml").toPath();
        YamlConfigurations.save(file, SettingsConfig.class, settings, SettingsConfig.PROPERTIES);
    }

    public DatabaseConfig getDatabaseConfig() {
        final SpawnArena plugin = SpawnArena.getPlugin(SpawnArena.class);
        final java.nio.file.Path file = new File(plugin.getDataFolder(), "database.yml").toPath();
        YamlConfigurations.update(file, DatabaseConfig.class, DatabaseConfig.PROPERTIES);
        return YamlConfigurations.load(file, DatabaseConfig.class, DatabaseConfig.PROPERTIES);
    }

    public synchronized void register(final UUID uuid, final PlayerPreFightData data) {
        final PreFightConfig conf = getPreFightConfig();
        conf.map.put(uuid, data);
        savePreFightConfig(conf);
    }


}
