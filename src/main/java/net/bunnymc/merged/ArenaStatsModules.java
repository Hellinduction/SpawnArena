/*
 * Merged into SpawnArena from a separate plugin (11 Nov 2026). Its source was lost, so this was recovered by
 * decompiling the merged jar that ran live (hence the plain variable names); it behaves as that jar did
 * (checked with merged-modules-check.js). Started by SpawnArena.onEnable, see ArenaGuards / ArenaStatsModules.
 */
package net.bunnymc.merged;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

public final class ArenaStatsModules {
   private static List<OwnedModule> active = new ArrayList();

   public static void start(JavaPlugin var0) {
      if (var0.isEnabled() && active.isEmpty()) {
         ArenaWinRate var1 = new ArenaWinRate(var0);
         active.add(var1);
         var1.onEnable();
         TournamentWins var2 = new TournamentWins(var0);
         active.add(var2);
         var2.onEnable();
      }
   }

   public static void stop(JavaPlugin var0) {
      Iterator var1 = active.iterator();

      while(var1.hasNext()) {
         OwnedModule var2 = (OwnedModule)var1.next();

         try {
            var2.onDisable();
         } catch (Exception var4) {
            var0.getLogger().log(Level.SEVERE, "Merged module shutdown failed", var4);
         }
      }

      active.clear();
   }
}
