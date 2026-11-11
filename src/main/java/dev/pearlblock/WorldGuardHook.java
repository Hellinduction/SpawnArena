/*
 * Merged into SpawnArena from a separate plugin (11 Nov 2026). Its source was lost, so this was recovered by
 * decompiling the merged jar that ran live (hence the plain variable names); it behaves as that jar did
 * (checked with merged-modules-check.js). Started by SpawnArena.onEnable, see ArenaGuards / ArenaStatsModules.
 */
package dev.pearlblock;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag.State;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import com.sk89q.worldguard.session.SessionManager;
import java.util.Iterator;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

public class WorldGuardHook {
   private final PearlBlock plugin;
   private final RegionContainer container;
   private final SessionManager sessions;

   public WorldGuardHook(PearlBlock var1) {
      this.plugin = var1;
      WorldGuard var2 = WorldGuard.getInstance();
      this.container = var2.getPlatform().getRegionContainer();
      this.sessions = var2.getPlatform().getSessionManager();
   }

   public boolean isBlocked(Location var1, Player var2) {
      if (var1.getWorld() == null) {
         return false;
      } else {
         RegionManager var3 = this.container.get(BukkitAdapter.adapt(var1.getWorld()));
         if (var3 == null) {
            return false;
         } else {
            RegionQuery var4 = this.container.createQuery();
            LocalPlayer var5 = var2 != null ? WorldGuardPlugin.inst().wrapPlayer(var2) : null;
            return !var4.testState(BukkitAdapter.adapt(var1), var5, Flags.ENDERPEARL);
         }
      }
   }

   public boolean isBlocked(Location var1) {
      return this.isBlocked(var1, (Player)null);
   }

   public boolean hasBypass(Player var1) {
      return this.sessions.hasBypass(WorldGuardPlugin.inst().wrapPlayer(var1), BukkitAdapter.adapt(var1.getWorld()));
   }

   public boolean worldHasRegions(World var1) {
      RegionManager var2 = this.container.get(BukkitAdapter.adapt(var1));
      return var2 != null && var2.size() > 0;
   }

   public boolean hasBlockedRegionNearby(Location var1, int var2) {
      if (var1.getWorld() == null) {
         return false;
      } else {
         RegionManager var3 = this.container.get(BukkitAdapter.adapt(var1.getWorld()));
         if (var3 == null) {
            return false;
         } else {
            int var4 = var1.getBlockX();
            int var5 = var1.getBlockZ();
            int var6 = var1.getWorld().getMinHeight();
            int var7 = var1.getWorld().getMaxHeight() - 1;
            ProtectedCuboidRegion var8 = new ProtectedCuboidRegion("__pb_q__", BlockVector3.at(var4 - var2, var6, var5 - var2), BlockVector3.at(var4 + var2, var7, var5 + var2));
            Set var9 = var3.getApplicableRegions((ProtectedRegion)var8).getRegions();
            Iterator var10 = var9.iterator();

            ProtectedRegion var11;
            do {
               if (!var10.hasNext()) {
                  return false;
               }

               var11 = (ProtectedRegion)var10.next();
            } while(!this.regionDeniesEnderpearl(var11));

            return true;
         }
      }
   }

   private boolean regionDeniesEnderpearl(ProtectedRegion var1) {
      for(ProtectedRegion var2 = var1; var2 != null; var2 = var2.getParent()) {
         if (var2.getFlag(Flags.ENDERPEARL) == State.DENY) {
            return true;
         }
      }

      return false;
   }
}
