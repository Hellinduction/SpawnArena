/*
 * Merged into SpawnArena from a separate plugin (11 Nov 2026). Its source was lost, so this was recovered by
 * decompiling the merged jar that ran live (hence the plain variable names); it behaves as that jar did
 * (checked with merged-modules-check.js). Started by SpawnArena.onEnable, see ArenaGuards / ArenaStatsModules.
 */
package net.bunnymc.arena.guards;

import java.io.File;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;

public abstract class GuardModule {
    public JavaPlugin owner;
    private FileConfiguration configuration;

    public void attach(JavaPlugin javaPlugin) {
        this.owner = javaPlugin;
    }

    public Server getServer() {
        return this.owner.getServer();
    }

    public Logger getLogger() {
        return this.owner.getLogger();
    }

    public PluginDescriptionFile getDescription() {
        return this.owner.getDescription();
    }

    public PluginCommand getCommand(String string) {
        return this.owner.getCommand(string);
    }

    public File getDataFolder() {
        return new File(this.owner.getDataFolder().getParentFile(), this.getClass().getSimpleName());
    }

    public void reloadConfig() {
        this.configuration = YamlConfiguration.loadConfiguration(new File(this.getDataFolder(), "config.yml"));
    }

    public FileConfiguration getConfig() {
        if (this.configuration == null) {
            this.reloadConfig();
        }
        return this.configuration;
    }

    /**
     * Writes the module's default config.yml (kept in SpawnArena's jar under merged/&lt;module&gt;/, as the original plugin
     * had it) if there isn't one yet, as the original plugin did; then loads it.
     */
    public void saveDefaultConfig() {
        final File file = new File(this.getDataFolder(), "config.yml");
        if (!file.exists())
            try (java.io.InputStream in = this.owner.getResource("merged/" + this.getClass().getSimpleName() + "/config.yml")) {
                if (in != null) {
                    this.getDataFolder().mkdirs();
                    java.nio.file.Files.copy(in, file.toPath());
                }
            } catch (final java.io.IOException e) {
                this.getLogger().warning("Couldn't write the default config of " + this.getClass().getSimpleName() + ": " + e);
            }
        this.reloadConfig();
    }

    public abstract void onEnable();

    public void onDisable() {
    }
}
