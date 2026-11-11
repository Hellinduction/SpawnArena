/*
 * Merged into SpawnArena from a separate plugin (11 Nov 2026). Its source was lost, so this was recovered by
 * decompiling the merged jar that ran live (hence the plain variable names); it behaves as that jar did
 * (checked with merged-modules-check.js). Started by SpawnArena.onEnable, see ArenaGuards / ArenaStatsModules.
 */
package dev.pearlblock;

import java.util.Iterator;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.Event.Result;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;

public class PearlListener implements Listener {
   private final PearlBlock plugin;
   private final WorldGuardHook wgHook;
   private final PearlTracker tracker;

   public PearlListener(PearlBlock var1, WorldGuardHook var2, PearlTracker var3) {
      this.plugin = var1;
      this.wgHook = var2;
      this.tracker = var3;
   }

   @EventHandler(
      priority = EventPriority.HIGH,
      ignoreCancelled = true
   )
   public void onInteract(PlayerInteractEvent var1) {
      if (var1.getAction() == Action.RIGHT_CLICK_AIR || var1.getAction() == Action.RIGHT_CLICK_BLOCK) {
         ItemStack var2 = var1.getItem();
         if (var2 != null && this.plugin.getBlockedMaterials().contains(var2.getType())) {
            Player var3 = var1.getPlayer();
            if (!this.wgHook.hasBypass(var3)) {
               if (this.wgHook.worldHasRegions(var3.getWorld())) {
                  if (this.wgHook.isBlocked(var3.getLocation(), var3)) {
                     var1.setUseItemInHand(Result.DENY);
                     var3.sendActionBar((Component)this.plugin.getActionBarMsg());
                     var3.getScheduler().run(this.plugin.owner, (var1x) -> {
                        var3.updateInventory();
                     }, (Runnable)null);
                  }

               }
            }
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGH,
      ignoreCancelled = true
   )
   public void onLaunch(ProjectileLaunchEvent var1) {
      Projectile var4 = var1.getEntity();
      if (this.plugin.isBlockedType(var4.getType())) {
         if (this.wgHook.worldHasRegions(var4.getWorld())) {
            ProjectileSource var5 = var4.getShooter();
            Player var3;
            if (!(var5 instanceof Player) || !this.wgHook.hasBypass(var3 = (Player)var5)) {
               var5 = var4.getShooter();
               if (var5 instanceof Player && this.wgHook.isBlocked((var3 = (Player)var5).getLocation(), var3)) {
                  var1.setCancelled(true);
                  var3.sendActionBar((Component)this.plugin.getActionBarMsg());
                  final Player thrower = var3;
                  thrower.getScheduler().run(this.plugin.owner, (var1x) -> {
                     thrower.updateInventory();
                  }, (Runnable)null);
               } else if (this.wgHook.hasBlockedRegionNearby(var4.getLocation(), this.plugin.getTrackRadius())) {
                  ProjectileSource var6 = var4.getShooter();
                  Player var2;
                  Player var7 = var6 instanceof Player ? (var2 = (Player)var6) : null;
                  this.tracker.track(var4, var7);
               }
            }
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGH,
      ignoreCancelled = true
   )
   public void onHit(ProjectileHitEvent var1) {
      Projectile var5 = var1.getEntity();
      if (this.plugin.isBlockedType(var5.getType())) {
         if (this.wgHook.worldHasRegions(var5.getWorld())) {
            ProjectileSource var6 = var5.getShooter();
            Player var4;
            if (!(var6 instanceof Player) || !this.wgHook.hasBypass(var4 = (Player)var6)) {
               ProjectileSource var7 = var5.getShooter();
               Player var2;
               Player var3 = var7 instanceof Player ? (var2 = (Player)var7) : null;
               if (this.wgHook.isBlocked(var5.getLocation(), var3)) {
                  var1.setCancelled(true);
                  if (this.tracker.isTracked(var5.getUniqueId())) {
                     this.tracker.removeTracked(var5);
                  } else {
                     var5.remove();
                     if (this.plugin.isLogRemovals()) {
                        Location var9 = var5.getLocation();
                        this.plugin.getLogger().info(String.format("Removed untracked %s at hit in %s (%.1f, %.1f, %.1f)", var5.getType().name().toLowerCase(), var9.getWorld() != null ? var9.getWorld().getName() : "?", var9.getX(), var9.getY(), var9.getZ()));
                     }
                  }
               }

            }
         }
      }
   }

   @EventHandler
   public void onEntitiesLoad(EntitiesLoadEvent var1) {
      if (this.wgHook.worldHasRegions(var1.getWorld())) {
         Iterator var2 = var1.getEntities().iterator();

         while(var2.hasNext()) {
            Entity var3 = (Entity)var2.next();
            Projectile var4;
            if (var3 instanceof Projectile && this.plugin.isBlockedType((var4 = (Projectile)var3).getType()) && this.wgHook.isBlocked(var4.getLocation())) {
               var4.remove();
               Location var5 = var4.getLocation();
               this.plugin.getLogger().info(String.format("Removed persisted %s at %s (%.1f, %.1f, %.1f)", var4.getType().name().toLowerCase(), var1.getWorld().getName(), var5.getX(), var5.getY(), var5.getZ()));
            }
         }

      }
   }
}
