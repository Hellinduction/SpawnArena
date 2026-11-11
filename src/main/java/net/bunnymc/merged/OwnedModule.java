/*
 * Merged into SpawnArena from a separate plugin (11 Nov 2026). Its source was lost, so this was recovered by
 * decompiling the merged jar that ran live (hence the plain variable names); it behaves as that jar did
 * (checked with merged-modules-check.js). Started by SpawnArena.onEnable, see ArenaGuards / ArenaStatsModules.
 */
package net.bunnymc.merged;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;

public abstract class OwnedModule {
   protected final JavaPlugin owner;
   private final String name;
   private FileConfiguration config;

   protected OwnedModule(JavaPlugin var1, String var2) {
      this.owner = var1;
      this.name = var2;
   }

   public Server getServer() {
      return this.owner.getServer();
   }

   public Logger getLogger() {
      return this.owner.getLogger();
   }

   public File getDataFolder() {
      return new File(this.owner.getDataFolder().getParentFile(), this.name);
   }

   public FileConfiguration getConfig() {
      if (this.config == null) {
         this.reloadConfig();
      }

      return this.config;
   }

   public void reloadConfig() {
      this.config = YamlConfiguration.loadConfiguration(new File(this.getDataFolder(), "config.yml"));

      try {
         InputStream var1 = this.getResource("config.yml");

         try {
            if (var1 != null) {
               this.config.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(var1, StandardCharsets.UTF_8)));
            }
         } catch (Throwable var5) {
            if (var1 != null) {
               try {
                  var1.close();
               } catch (Throwable var4) {
                  var5.addSuppressed(var4);
               }
            }

            throw var5;
         }

         if (var1 != null) {
            var1.close();
         }

      } catch (IOException var6) {
         throw new IllegalStateException(var6);
      }
   }

   public void saveDefaultConfig() {
      if (!(new File(this.getDataFolder(), "config.yml")).exists()) {
         this.saveResource("config.yml", false);
      }

   }

   public InputStream getResource(String var1) {
      return this.owner.getResource("merged/" + this.name + "/" + var1);
   }

   public void saveResource(String var1, boolean var2) {
      try {
         InputStream var3 = this.getResource(var1);

         try {
            if (var3 == null) {
               throw new IllegalStateException("Missing resource " + var1);
            }

            Path var4 = this.getDataFolder().toPath().resolve(var1);
            Files.createDirectories(var4.getParent());
            if (var2) {
               Files.copy(var3, var4, new CopyOption[]{StandardCopyOption.REPLACE_EXISTING});
            } else if (!Files.exists(var4, new LinkOption[0])) {
               Files.copy(var3, var4, new CopyOption[0]);
            }
         } catch (Throwable var7) {
            if (var3 != null) {
               try {
                  var3.close();
               } catch (Throwable var6) {
                  var7.addSuppressed(var6);
               }
            }

            throw var7;
         }

         if (var3 != null) {
            var3.close();
         }

      } catch (IOException var8) {
         throw new IllegalStateException(var8);
      }
   }

   public PluginCommand getCommand(String var1) {
      return this.owner.getCommand(var1);
   }

   public PluginDescriptionFile getDescription() {
      return this.owner.getDescription();
   }

   public void saveConfig() {
      try {
         this.getDataFolder().mkdirs();
         this.getConfig().save(new File(this.getDataFolder(), "config.yml"));
      } catch (IOException var2) {
         throw new IllegalStateException(var2);
      }
   }

   public boolean isEnabled() {
      return this.owner.isEnabled();
   }

   public abstract void onEnable();

   public void onDisable() {
   }
}
