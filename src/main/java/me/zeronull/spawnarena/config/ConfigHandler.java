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

    public void savePreFightConfig(PreFightConfig config) {
        YamlConfigurationProperties properties = PreFightConfig.PROPERTIES;

        SpawnArena plugin = SpawnArena.getPlugin(SpawnArena.class);
        Path configFile = new File(plugin.getDataFolder(), "prefights.yml").toPath();

        YamlConfigurations.save(
                configFile,
                PreFightConfig.class,
                config,
                properties
        );
    }

    public void updatePreFightConfig() {
        YamlConfigurationProperties properties = PreFightConfig.PROPERTIES;

        SpawnArena plugin = SpawnArena.getPlugin(SpawnArena.class);
        Path configFile = new File(plugin.getDataFolder(), "prefights.yml").toPath();

        YamlConfigurations.update(
                configFile,
                PreFightConfig.class,
                properties
        );
    }

    public PreFightConfig getPreFightConfig() {
        YamlConfigurationProperties properties = PreFightConfig.PROPERTIES;

        SpawnArena plugin = SpawnArena.getPlugin(SpawnArena.class);
        Path configFile = new File(plugin.getDataFolder(), "prefights.yml").toPath();

        if (!configFile.toFile().exists())
            return null;

        PreFightConfig config = YamlConfigurations.load(
                configFile,
                PreFightConfig.class,
                properties
        );

        return config;
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
            arena.setWinReward(config.winReward, config.winRewardCooldownMinutes);
            arena.setLeaderReward(config.leaderRewardMultiplier, config.leaderRewardCooldownMinutes);

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

    public void register(final UUID uuid, final PlayerPreFightData data) {
        final PreFightConfig conf = getPreFightConfig();
        conf.map.put(uuid, data);
        savePreFightConfig(conf);
    }

//    public void setSpawnpoint(Location location, int spawnPointNumber) {
//        GameConfig config = ConfigHandler.getInstance().getCustomConfig();
//        if(spawnPointNumber == 1) {
//            config.spawnPoint1 = location;
//        } else if(spawnPointNumber == 2) {
//            config.spawnPoint2 = location;
//        }
//        ConfigHandler.getInstance().saveCustomConfig(config);
//    }

}
