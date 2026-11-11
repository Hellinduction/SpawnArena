/*
 * Merged into SpawnArena from a separate plugin (11 Nov 2026). Its source was lost, so this was recovered by
 * decompiling the merged jar that ran live (hence the plain variable names); it behaves as that jar did
 * (checked with merged-modules-check.js). Started by SpawnArena.onEnable, see ArenaGuards / ArenaStatsModules.
 */
package dev.pearlblock;

import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import net.bunnymc.arena.guards.GuardModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.EntityType;

public final class PearlBlock extends GuardModule {
   private int worldEvents;
   private static PearlBlock instance;
   private WorldGuardHook wgHook;
   private PearlTracker tracker;
   private int trackRadius;
   private int checkIntervalTicks;
   private long maxLifetimeMs;
   private boolean notifyThrower;
   private boolean logRemovals;
   private Component actionBarMsg;
   private Component removalMsg;
   private Set<EntityType> blockedTypes;
   private Set<Material> blockedMaterials;

   public void onEnable() {
      instance = this;
      this.saveDefaultConfig();
      this.loadConfigValues();
      this.wgHook = new WorldGuardHook(this);
      this.tracker = new PearlTracker(this, this.wgHook);
      this.getServer().getPluginManager().registerEvents(new PearlListener(this, this.wgHook, this.tracker), this.owner);
      PluginCommand var1 = this.getCommand("pearlblock");
      if (var1 != null) {
         PearlBlockCommand var2 = new PearlBlockCommand(this, this.wgHook, this.tracker);
         var1.setExecutor(var2);
         var1.setTabCompleter(var2);
      }

      Logger var10000 = this.getLogger();
      String var10001 = this.getDescription().getVersion();
      var10000.info("PearlBlock " + var10001 + " enabled (Folia=" + PearlTracker.IS_FOLIA + ").");
   }

   public void onDisable() {
      if (this.tracker != null) {
         this.tracker.shutdown();
      }

   }

   public void loadConfigValues() {
      this.reloadConfig();
      MiniMessage var1 = MiniMessage.miniMessage();
      this.trackRadius = this.getConfig().getInt("track-radius", 64);
      this.checkIntervalTicks = Math.max(1, this.getConfig().getInt("check-interval-ticks", 2));
      this.maxLifetimeMs = this.getConfig().getLong("max-lifetime-seconds", 60L) * 1000L;
      this.notifyThrower = this.getConfig().getBoolean("notify-thrower", true);
      this.logRemovals = this.getConfig().getBoolean("log-removals", true);
      this.actionBarMsg = var1.deserialize(this.getConfig().getString("action-bar-message", "<red>Projectiles are not allowed here.</red>"));
      this.removalMsg = var1.deserialize(this.getConfig().getString("removal-message", "<red>That projectile was blocked by a protected region.</red>"));
      this.blockedTypes = EnumSet.of(EntityType.ENDER_PEARL);
      this.blockedMaterials = EnumSet.of(Material.ENDER_PEARL);
      List var2 = this.getConfig().getStringList("extra-blocked-projectiles");
      Iterator var3 = var2.iterator();

      while(var3.hasNext()) {
         String var4 = (String)var3.next();

         try {
            EntityType var5 = EntityType.valueOf(var4.trim().toUpperCase());
            this.blockedTypes.add(var5);
            Material var6 = entityTypeToMaterial(var5);
            if (var6 != null) {
               this.blockedMaterials.add(var6);
            }
         } catch (IllegalArgumentException var7) {
            this.getLogger().warning("Unknown entity type in extra-blocked-projectiles: " + var4);
         }
      }

   }

   private static Material entityTypeToMaterial(EntityType var0) {
      Material var10000;
      switch (var0) {
         case EXPERIENCE_BOTTLE -> var10000 = Material.EXPERIENCE_BOTTLE;
         case SPLASH_POTION -> var10000 = Material.SPLASH_POTION;
         case LINGERING_POTION -> var10000 = Material.LINGERING_POTION;
         case SNOWBALL -> var10000 = Material.SNOWBALL;
         case EGG -> var10000 = Material.EGG;
         case TRIDENT -> var10000 = Material.TRIDENT;
         default -> var10000 = null;
      }

      return var10000;
   }

   public static PearlBlock get() {
      return instance;
   }

   public WorldGuardHook getWgHook() {
      return this.wgHook;
   }

   public PearlTracker getTracker() {
      return this.tracker;
   }

   public int getTrackRadius() {
      return this.trackRadius;
   }

   public int getCheckIntervalTicks() {
      return this.checkIntervalTicks;
   }

   public long getMaxLifetimeMs() {
      return this.maxLifetimeMs;
   }

   public boolean isNotifyThrower() {
      return this.notifyThrower;
   }

   public boolean isLogRemovals() {
      return this.logRemovals;
   }

   public Component getActionBarMsg() {
      return this.actionBarMsg;
   }

   public Component getRemovalMsg() {
      return this.removalMsg;
   }

   public Set<EntityType> getBlockedTypes() {
      return this.blockedTypes;
   }

   public Set<Material> getBlockedMaterials() {
      return this.blockedMaterials;
   }

   public boolean isBlockedType(EntityType var1) {
      return this.blockedTypes.contains(var1);
   }
}
