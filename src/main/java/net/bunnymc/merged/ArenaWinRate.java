/*
 * Merged into SpawnArena from a separate plugin (11 Nov 2026). Its source was lost, so this was recovered by
 * decompiling the merged jar that ran live (hence the plain variable names); it behaves as that jar did
 * (checked with merged-modules-check.js). Started by SpawnArena.onEnable, see ArenaGuards / ArenaStatsModules.
 */
package net.bunnymc.merged;

import de.oliver.fancyholograms.api.FancyHologramsPlugin;
import de.oliver.fancyholograms.api.HologramManager;
import de.oliver.fancyholograms.api.data.HologramData;
import de.oliver.fancyholograms.api.data.TextHologramData;
import de.oliver.fancyholograms.api.hologram.Hologram;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class ArenaWinRate extends OwnedModule implements CommandExecutor {
   private int commandHandlers;
   private final AtomicBoolean updating = new AtomicBoolean();
   private final Map<String, List<String>> boards = new LinkedHashMap();
   private int minimum;

   public ArenaWinRate(JavaPlugin var1) {
      super(var1, "ArenaWinRate");
   }

   public void onEnable() {
      this.saveDefaultConfig();
      this.minimum = Math.max(1, this.getConfig().getInt("minimum-matches", 20));
      this.getCommand("arenawinrate").setExecutor(this);
      this.getServer().getScheduler().runTaskTimerAsynchronously(this.owner, this::update, 20L, 600L);
   }

   public void onDisable() {
      FancyHologramsPlugin.get().getHologramManager().saveHolograms();
   }

   public static double percent(int var0, int var1) {
      long var2 = (long)var0 + (long)var1;
      return var2 <= 0L ? 0.0 : 100.0 * (double)var0 / (double)var2;
   }

   static String title(String var0) {
      return var0.equals("sumo") ? "Sumo" : (var0.equals("crystalpvp") ? "CrystalPvP" : (var0.equals("arena") ? "Arena" : var0));
   }

   void update() {
      if (this.updating.compareAndSet(false, true)) {
         try {
            Plugin var1 = Bukkit.getPluginManager().getPlugin("SpawnArena");
            if (var1 != null && var1.isEnabled()) {
               Class var2 = var1.getClass();
               Object var3 = var2.getField("statsManager").get((Object)null);
               Field var4 = var3.getClass().getDeclaredField("dataSource");
               var4.setAccessible(true);
               DataSource var5 = (DataSource)var4.get(var3);
               String var6 = (String)var3.getClass().getMethod("getServerId").invoke(var3);
               Method var7 = var3.getClass().getMethod("nameOf", UUID.class);
               long var8 = ((Number)var3.getClass().getMethod("getLossesSince").invoke(var3)).longValue();
               LinkedHashMap var10 = new LinkedHashMap();
               Connection var11 = var5.getConnection();

               try {
                  PreparedStatement var12 = var11.prepareStatement("SELECT uuid,wins,losses FROM arena_stats WHERE server_id=? AND arena=? AND wins>=0 AND losses>0 AND wins+losses>=? ORDER BY (100.0*wins/(wins+losses)) DESC,(wins+losses) DESC,wins DESC,uuid ASC LIMIT 10");

                  String var14;
                  ArrayList var15;
                  try {
                     for(Iterator var13 = List.of("sumo", "arena", "crystalpvp").iterator(); var13.hasNext(); var10.put(var14, var15)) {
                        var14 = (String)var13.next();
                        var12.setString(1, var6);
                        var12.setString(2, var14);
                        var12.setInt(3, this.minimum);
                        var15 = new ArrayList();
                        var15.add("<light_purple><bold>" + title(var14) + " WIN RATE</bold></light_purple>");
                        var15.add("<gray>Minimum " + this.minimum + " matches to qualify</gray>");
                        int var16 = 0;
                        ResultSet var17 = var12.executeQuery();

                        try {
                           while(var17.next()) {
                              UUID var18 = UUID.fromString(var17.getString("uuid"));
                              String var19 = (String)var7.invoke(var3, var18);
                              if (var19 == null || var19.isBlank()) {
                                 var19 = var18.toString().substring(0, 8);
                              }

                              var19 = var19.replace("<", "").replace(">", "");
                              int var20 = var17.getInt("wins");
                              int var21 = var17.getInt("losses");
                              Locale var39 = Locale.ROOT;
                              Object[] var10003 = new Object[5];
                              ++var16;
                              var10003[0] = var16;
                              var10003[1] = var19;
                              var10003[2] = percent(var20, var21);
                              var10003[3] = var20;
                              var10003[4] = var21;
                              var15.add(String.format(var39, "<light_purple>%d.</light_purple> <yellow>%s</yellow> <white>%.2f%%</white> <gray>(%dW / %dL)</gray>", var10003));
                           }
                        } catch (Throwable var34) {
                           if (var17 != null) {
                              try {
                                 var17.close();
                              } catch (Throwable var33) {
                                 var34.addSuppressed(var33);
                              }
                           }

                           throw var34;
                        }

                        if (var17 != null) {
                           var17.close();
                        }

                        if (var16 == 0) {
                           var15.add("<gray>No qualifying players yet</gray>");
                        }

                        if (var8 > 0L) {
                           var15.add("<dark_gray>Older losses may be missing</dark_gray>");
                        }
                     }
                  } catch (Throwable var35) {
                     if (var12 != null) {
                        try {
                           var12.close();
                        } catch (Throwable var32) {
                           var35.addSuppressed(var32);
                        }
                     }

                     throw var35;
                  }

                  if (var12 != null) {
                     var12.close();
                  }
               } catch (Throwable var36) {
                  if (var11 != null) {
                     try {
                        var11.close();
                     } catch (Throwable var31) {
                        var36.addSuppressed(var31);
                     }
                  }

                  throw var36;
               }

               if (var11 != null) {
                  var11.close();
               }

               if (this.isEnabled()) {
                  Bukkit.getScheduler().runTask(this.owner, () -> {
                     if (this.isEnabled()) {
                        for (final Object e : var10.entrySet()) {
                           final Map.Entry entry = (Map.Entry) e;
                           this.render((String) entry.getKey(), (List) entry.getValue());
                        }

                        this.boards.clear();
                        this.boards.putAll(var10);
                     }
                  });
               }

               return;
            }
         } catch (Exception var37) {
            Logger var10000 = this.getLogger();
            String var10001 = var37.getClass().getSimpleName();
            var10000.warning("Win-rate refresh failed: " + var10001 + ": " + var37.getMessage());
            return;
         } finally {
            this.updating.set(false);
         }

      }
   }

   /** FancyHolograms 2.12+: drops its cached text so the new lines show (older versions haven't got it, and don't need it). */
   private static void clearTextCache(final Hologram hologram) {
      try {
         hologram.getClass().getMethod("clearTextCache").invoke(hologram);
      } catch (final ReflectiveOperationException ignored) {
         // an older FancyHolograms
      }
   }

   void render(String var1, List<String> var2) {
      HologramManager var4 = FancyHologramsPlugin.get().getHologramManager();
      String var3;
      Hologram var5 = var4.getHologram(var3 = "arena_winrate_" + var1).orElse(null);
      if (var5 == null) {
         ConfigurationSection var6 = this.getConfig().getConfigurationSection("locations." + var1);
         if (var6 == null) {
            return;
         }

         World var7 = Bukkit.getWorld(var6.getString("world", "Spawn"));
         if (var7 == null) {
            return;
         }

         Location var8 = new Location(var7, var6.getDouble("x"), var6.getDouble("y"), var6.getDouble("z"));
         TextHologramData var9 = new TextHologramData(var3, var8);
         var9.setPersistent(true);
         var9.setTextShadow(true);
         var9.setTextUpdateInterval(-1);
         var9.setText(var2);
         var5 = var4.create(var9);
         var4.addHologram(var5);
         var4.saveHolograms();
      } else {
         HologramData var10 = var5.getData();
         if (var10 instanceof TextHologramData) {
            TextHologramData var11 = (TextHologramData)var10;
            var11.setText(var2);
            clearTextCache(var5);
            var5.queueUpdate();
         }
      }

   }

   public boolean onCommand(CommandSender var1, Command var2, String var3, String[] var4) {
      if (!var1.hasPermission("arenawinrate.admin")) {
         var1.sendMessage("You do not have permission.");
         return true;
      } else {
         Iterator var5 = this.boards.entrySet().iterator();

         while(var5.hasNext()) {
            Map.Entry var6 = (Map.Entry)var5.next();
            var1.sendMessage("Win rate " + (String)var6.getKey() + ":");
            Iterator var7 = ((List)var6.getValue()).iterator();

            while(var7.hasNext()) {
               String var8 = (String)var7.next();
               var1.sendMessage(var8.replaceAll("<[^>]*>", ""));
            }
         }

         if (this.boards.isEmpty()) {
            var1.sendMessage("The first refresh has not completed yet.");
         }

         return true;
      }
   }

   static record Entry(String name, int wins, int losses) {
      Entry(String name, int wins, int losses) {
         this.name = name;
         this.wins = wins;
         this.losses = losses;
      }

      public String name() {
         return this.name;
      }

      public int wins() {
         return this.wins;
      }

      public int losses() {
         return this.losses;
      }
   }
}
