/*
 * Merged into SpawnArena from a separate plugin (11 Nov 2026). Its source was lost, so this was recovered by
 * decompiling the merged jar that ran live (hence the plain variable names); it behaves as that jar did
 * (checked with merged-modules-check.js). Started by SpawnArena.onEnable, see ArenaGuards / ArenaStatsModules.
 */
package dev.pearlblock;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;

public class PearlTracker {
   static final boolean IS_FOLIA;
   private final ConcurrentHashMap<UUID, PearlState> tracked = new ConcurrentHashMap();
   private final ConcurrentHashMap<UUID, Long> lastLogMs = new ConcurrentHashMap();
   private static final long LOG_COOLDOWN_MS = 3000L;
   private volatile boolean shutdown = false;
   private final PearlBlock plugin;
   private final WorldGuardHook wgHook;

   public PearlTracker(PearlBlock var1, WorldGuardHook var2) {
      this.plugin = var1;
      this.wgHook = var2;
   }

   public boolean isTracked(UUID var1) {
      return this.tracked.containsKey(var1);
   }

   public int size() {
      return this.tracked.size();
   }

   public void track(Projectile var1, Player var2) {
      if (!this.shutdown) {
         UUID var3 = var1.getUniqueId();
         if (!this.tracked.containsKey(var3)) {
            PearlState var4 = new PearlState(var2 != null ? var2.getUniqueId() : null, var2 != null ? var2.getName() : "(dispenser)", System.currentTimeMillis(), var1.getLocation());
            this.tracked.put(var3, var4);
            int var5 = this.plugin.getCheckIntervalTicks();
            ScheduledTask var6 = var1.getScheduler().runAtFixedRate(this.plugin.owner, (var3x) -> {
               if (this.shutdown) {
                  var3x.cancel();
                  this.tracked.remove(var3);
               } else if (var1.isValid() && !var1.isDead()) {
                  PearlState state = (PearlState)this.tracked.get(var3);
                  if (state == null) {
                     var3x.cancel();
                  } else {
                     Location here = var1.getLocation();
                     long age = System.currentTimeMillis() - state.startMs;
                     boolean nearRegion = this.wgHook.hasBlockedRegionNearby(here, 2);
                     if (age > this.plugin.getMaxLifetimeMs() && !nearRegion) {
                        this.tracked.remove(var3);
                        var3x.cancel();
                     } else {
                        Player thrower = state.throwerId != null ? Bukkit.getPlayer(state.throwerId) : null;
                        if (this.checkSegment(state.lastPos, here, thrower)) {
                           this.doRemove(var1, state, "segment");
                           this.tracked.remove(var3);
                           var3x.cancel();
                        } else if (this.wgHook.isBlocked(here, thrower)) {
                           this.doRemove(var1, state, "position");
                           this.tracked.remove(var3);
                           var3x.cancel();
                        } else {
                           state.lastPos = here;
                        }
                     }
                  }
               } else {
                  this.tracked.remove(var3);
                  var3x.cancel();
               }
            }, () -> {
               this.tracked.remove(var3);
            }, 1L, (long)var5);
            if (var6 == null) {
               this.tracked.remove(var3);
            }

         }
      }
   }

   public void removeTracked(Projectile var1) {
      PearlState var2 = (PearlState)this.tracked.remove(var1.getUniqueId());
      this.doRemove(var1, var2, "hit");
   }

   public void stopTracking(UUID var1) {
      this.tracked.remove(var1);
   }

   public void shutdown() {
      this.shutdown = true;
      this.tracked.clear();
   }

   private void doRemove(Projectile var1, PearlState var2, String var3) {
      var1.remove();
      if (var2 != null) {
         Player var4;
         if (this.plugin.isNotifyThrower() && var2.throwerId != null && (var4 = Bukkit.getPlayer(var2.throwerId)) != null && var4.isOnline()) {
            var4.sendMessage((Component)this.plugin.getRemovalMsg());
         }

         if (this.plugin.isLogRemovals()) {
            UUID var5 = var2.throwerId != null ? var2.throwerId : new UUID(0L, 0L);
            long var6 = System.currentTimeMillis();
            Long var8 = (Long)this.lastLogMs.get(var5);
            if (var8 == null || var6 - var8 >= 3000L) {
               this.lastLogMs.put(var5, var6);
               Location var9 = var1.getLocation();
               this.plugin.getLogger().info(String.format("Removed %s from %s in %s (%.1f, %.1f, %.1f) [%s]", var1.getType().name().toLowerCase(), var2.throwerName, var9.getWorld() != null ? var9.getWorld().getName() : "?", var9.getX(), var9.getY(), var9.getZ(), var3));
            }
         }

      }
   }

   private boolean checkSegment(Location var1, Location var2, Player var3) {
      if (var1.getWorld() != null && var1.getWorld().equals(var2.getWorld())) {
         double var4 = var1.distance(var2);
         if (var4 < 1.0) {
            return false;
         } else {
            int var6 = (int)Math.ceil(var4);
            double var7 = (var2.getX() - var1.getX()) / (double)var6;
            double var9 = (var2.getY() - var1.getY()) / (double)var6;
            double var11 = (var2.getZ() - var1.getZ()) / (double)var6;

            for(int var13 = 1; var13 <= var6; ++var13) {
               Location var14 = new Location(var1.getWorld(), var1.getX() + var7 * (double)var13, var1.getY() + var9 * (double)var13, var1.getZ() + var11 * (double)var13);
               if (this.wgHook.isBlocked(var14, var3)) {
                  return true;
               }
            }

            return false;
         }
      } else {
         return false;
      }
   }

   static {
      boolean var0;
      try {
         Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
         var0 = true;
      } catch (ClassNotFoundException var2) {
         var0 = false;
      }

      IS_FOLIA = var0;
   }

   private static final class PearlState {
      final UUID throwerId;
      final String throwerName;
      final long startMs;
      volatile Location lastPos;

      PearlState(UUID var1, String var2, long var3, Location var5) {
         this.throwerId = var1;
         this.throwerName = var2;
         this.startMs = var3;
         this.lastPos = var5;
      }
   }
}
