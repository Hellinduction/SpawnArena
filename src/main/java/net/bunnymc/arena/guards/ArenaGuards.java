/*
 * Merged into SpawnArena from a separate plugin (11 Nov 2026). Its source was lost, so this was recovered by
 * decompiling the merged jar that ran live (hence the plain variable names); it behaves as that jar did
 * (checked with merged-modules-check.js). Started by SpawnArena.onEnable, see ArenaGuards / ArenaStatsModules.
 */
package net.bunnymc.arena.guards;

import dev.pearlblock.PearlBlock;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class ArenaGuards {
    private static final List<GuardModule> modules = new ArrayList<GuardModule>();

    public static void start(JavaPlugin javaPlugin) {
        if (!javaPlugin.isEnabled() || !modules.isEmpty()) {
            return;
        }
        File file = new File(javaPlugin.getDataFolder(), "guards.yml");
        YamlConfiguration yamlConfiguration = YamlConfiguration.loadConfiguration(file);
        List<GuardModule> list = List.of(new FightTeleportGuard(), new CrystalFfaLiquids(), new PearlBlock());
        try {
            String string;
            for (GuardModule guardModule : list) {
                string = guardModule.getClass().getSimpleName();
                yamlConfiguration.addDefault(string, true);
            }
            yamlConfiguration.options().copyDefaults(true);
            yamlConfiguration.save(file);
            for (GuardModule guardModule : list) {
                string = guardModule.getClass().getSimpleName();
                if (!yamlConfiguration.getBoolean(string)) continue;
                guardModule.attach(javaPlugin);
                modules.add(guardModule);
                guardModule.onEnable();
                javaPlugin.getLogger().info("Integrated guard enabled: " + string);
            }
        }
        catch (Exception exception) {
            ArenaGuards.stop();
            throw new IllegalStateException("Could not start integrated arena protection", exception);
        }
    }

    public static void stop() {
        for (int i = modules.size() - 1; i >= 0; --i) {
            GuardModule guardModule = modules.get(i);
            try {
                guardModule.onDisable();
                continue;
            }
            catch (Exception exception) {
                guardModule.getLogger().log(Level.SEVERE, "Guard shutdown failed", exception);
            }
        }
        modules.clear();
    }
}
