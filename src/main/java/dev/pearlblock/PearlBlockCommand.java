/*
 * Merged into SpawnArena from a separate plugin (11 Nov 2026). Its source was lost, so this was recovered by
 * decompiling the merged jar that ran live (hence the plain variable names); it behaves as that jar did
 * (checked with merged-modules-check.js). Started by SpawnArena.onEnable, see ArenaGuards / ArenaStatsModules.
 */
package dev.pearlblock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;

public class PearlBlockCommand implements CommandExecutor, TabCompleter {
   private final PearlBlock plugin;
   private final WorldGuardHook wgHook;
   private final PearlTracker tracker;

   public PearlBlockCommand(PearlBlock var1, WorldGuardHook var2, PearlTracker var3) {
      this.plugin = var1;
      this.wgHook = var2;
      this.tracker = var3;
   }

   public boolean onCommand(CommandSender var1, Command var2, String var3, String[] var4) {
      if (!var1.hasPermission("pearlblock.admin")) {
         var1.sendMessage((Component)Component.text("No permission.", (TextColor)NamedTextColor.RED));
         return true;
      } else if (var4.length == 0) {
         this.sendUsage(var1, var3);
         return true;
      } else {
         switch (var4[0].toLowerCase()) {
            case "reload":
               this.plugin.loadConfigValues();
               var1.sendMessage((Component)Component.text("PearlBlock config reloaded.", (TextColor)NamedTextColor.GREEN));
               break;
            case "clear":
               int var7 = -1;
               if (var4.length >= 2) {
                  try {
                     var7 = Integer.parseInt(var4[1]);
                  } catch (NumberFormatException var9) {
                     var1.sendMessage((Component)Component.text("Invalid radius.", (TextColor)NamedTextColor.RED));
                     return true;
                  }
               }

               if (PearlTracker.IS_FOLIA) {
                  this.doClearFolia(var1, var7);
               } else {
                  this.doClearPaper(var1, var7);
               }
               break;
            default:
               this.sendUsage(var1, var3);
         }

         return true;
      }
   }

   private void doClearPaper(CommandSender var1, int var2) {
      int var3 = 0;
      if (var2 > 0 && var1 instanceof Player var9) {
         Iterator var10 = var9.getWorld().getNearbyEntities(var9.getLocation(), (double)var2, (double)var2, (double)var2).iterator();

         while(var10.hasNext()) {
            Entity var11 = (Entity)var10.next();
            Projectile var12;
            if (var11 instanceof Projectile && this.plugin.isBlockedType((var12 = (Projectile)var11).getType()) && this.wgHook.isBlocked(var12.getLocation())) {
               this.tracker.stopTracking(var12.getUniqueId());
               var12.remove();
               ++var3;
            }
         }
      } else {
         Iterator var4 = this.plugin.getServer().getWorlds().iterator();

         label45:
         while(true) {
            World var5;
            do {
               if (!var4.hasNext()) {
                  break label45;
               }

               var5 = (World)var4.next();
            } while(!this.wgHook.worldHasRegions(var5));

            Iterator var6 = var5.getEntities().iterator();

            while(var6.hasNext()) {
               Entity var7 = (Entity)var6.next();
               Projectile var8;
               if (var7 instanceof Projectile && this.plugin.isBlockedType((var8 = (Projectile)var7).getType()) && this.wgHook.isBlocked(var8.getLocation())) {
                  this.tracker.stopTracking(var8.getUniqueId());
                  var8.remove();
                  ++var3;
               }
            }
         }
      }

      this.sendCount(var1, var3);
   }

   private void doClearFolia(CommandSender var1, int var2) {
      if (var2 > 0 && !(var1 instanceof Player)) {
         var1.sendMessage((Component)Component.text("Radius mode requires a player sender on Folia.", (TextColor)NamedTextColor.RED));
      } else {
         var1.sendMessage((Component)Component.text("Scanning for ender pearls…", (TextColor)NamedTextColor.GRAY));
         Bukkit.getAsyncScheduler().runNow(this.plugin.owner, (var3) -> {
            ArrayList var4 = new ArrayList();
            Iterator var5 = this.plugin.getServer().getWorlds().iterator();

            while(true) {
               World var6;
               do {
                  if (!var5.hasNext()) {
                     if (var4.isEmpty()) {
                        this.sendCount(var1, 0);
                        return;
                     }

                     AtomicInteger var15 = new AtomicInteger();
                     AtomicInteger var16 = new AtomicInteger(var4.size());
                     Iterator var17 = var4.iterator();

                     while(var17.hasNext()) {
                        Chunk var18 = (Chunk)var17.next();
                        Bukkit.getRegionScheduler().execute(this.plugin.owner, var18.getWorld(), var18.getX(), var18.getZ(), () -> {
                           Entity[] entities = var18.getEntities();
                           int count = entities.length;

                           for(int k = 0; k < count; ++k) {
                              Entity entity = entities[k];
                              Projectile projectile;
                              if (entity instanceof Projectile && this.plugin.isBlockedType((projectile = (Projectile)entity).getType()) && this.wgHook.isBlocked(projectile.getLocation())) {
                                 this.tracker.stopTracking(projectile.getUniqueId());
                                 projectile.remove();
                                 var15.incrementAndGet();
                              }
                           }

                           if (var16.decrementAndGet() == 0) {
                              this.sendCount(var1, var15.get());
                           }

                        });
                     }

                     return;
                  }

                  var6 = (World)var5.next();
               } while(!this.wgHook.worldHasRegions(var6));

               Chunk[] var7 = var6.getLoadedChunks();
               int var8 = var7.length;

               for(int var9 = 0; var9 < var8; ++var9) {
                  Chunk var10 = var7[var9];
                  if (var2 > 0 && var1 instanceof Player var11) {
                     int var12 = (var2 >> 4) + 1;
                     int var13 = var11.getLocation().getBlockX() >> 4;
                     int var14 = var11.getLocation().getBlockZ() >> 4;
                     if (Math.abs(var10.getX() - var13) > var12 || Math.abs(var10.getZ() - var14) > var12) {
                        continue;
                     }
                  }

                  var4.add(var10);
               }
            }
         });
      }
   }

   private void sendCount(CommandSender var1, int var2) {
      var1.sendMessage((Component)Component.text("Removed " + var2 + " ender pearl(s) from protected regions.", (TextColor)NamedTextColor.GREEN));
   }

   private void sendUsage(CommandSender var1, String var2) {
      var1.sendMessage((Component)Component.text("/" + var2 + " reload | clear [radius]", (TextColor)NamedTextColor.YELLOW));
   }

   public List<String> onTabComplete(CommandSender var1, Command var2, String var3, String[] var4) {
      if (!var1.hasPermission("pearlblock.admin")) {
         return List.of();
      } else {
         return var4.length == 1 ? Arrays.asList("reload", "clear") : List.of();
      }
   }
}
