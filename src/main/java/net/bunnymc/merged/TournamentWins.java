/*
 * Merged into SpawnArena from a separate plugin (11 Nov 2026). Its source was lost, so this was recovered by
 * decompiling the merged jar that ran live (hence the plain variable names); it behaves as that jar did
 * (checked with merged-modules-check.js). Started by SpawnArena.onEnable, see ArenaGuards / ArenaStatsModules.
 */
package net.bunnymc.merged;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class TournamentWins extends OwnedModule implements Listener {
   private final Gson gson = (new GsonBuilder()).setPrettyPrinting().create();
   private final Map<String, ChampionReader.Champion> ledger = new TreeMap();
   private volatile List<Row> top = List.of();
   private volatile int unverified;
   private volatile boolean ready = false;
   private volatile Map<String, List<Row>> modes = Map.of();
   private Expansion expansion;

   public TournamentWins(JavaPlugin var1) {
      super(var1, "TournamentWins");
   }

   public void onEnable() {
      this.getDataFolder().mkdirs();
      Bukkit.getPluginManager().registerEvents(this, this.owner);
      ((PluginCommand)Objects.requireNonNull(this.getCommand("tournamentwins"))).setExecutor(this::command);
      if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
         this.expansion = new Expansion();
         this.expansion.register();
      }

      // Paper's async scheduler (it works on Folia too, the Bukkit one doesn't): every 10 s
      Bukkit.getAsyncScheduler().runAtFixedRate(this.owner, task -> this.refresh(), 50L, 10_000L, java.util.concurrent.TimeUnit.MILLISECONDS);
   }

   public void onDisable() {
      if (this.expansion != null) {
         this.expansion.unregister();
      }

   }

   static String mode(String var0) {
      return var0 == null ? "unknown" : var0.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
   }

   static List<Row> rank(Collection<ChampionReader.Champion> var0, String var1) {
      HashMap var2 = new HashMap();
      Iterator var3 = var0.iterator();

      while(true) {
         ChampionReader.Champion var4;
         do {
            if (!var3.hasNext()) {
               return var2.values().stream().sorted(Comparator.comparingInt(Row::wins).reversed().thenComparing(Row::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Row::uuid)).toList();
            }

            var4 = (ChampionReader.Champion)var3.next();
         } while(var1 != null && !mode(var4.arena()).equals(var1));

         Row var5 = (Row)var2.get(var4.uuid());
         var2.put(var4.uuid(), new Row(var4.uuid(), var4.name(), var5 == null ? 1 : var5.wins() + 1));
      }
   }

   private synchronized void refresh() {
      try {
         Path var1 = this.getDataFolder().toPath().resolve("champions.json");
         if (!this.ready && Files.exists(var1, new LinkOption[0])) {
            JsonArray var2 = JsonParser.parseString(Files.readString(var1)).getAsJsonArray();
            Iterator var3 = var2.iterator();

            while(var3.hasNext()) {
               JsonElement var4 = (JsonElement)var3.next();
               ChampionReader.Champion var5 = (ChampionReader.Champion)this.gson.fromJson(var4, ChampionReader.Champion.class);
               UUID.fromString(var5.tournament());
               UUID.fromString(var5.uuid());
               this.ledger.put(var5.tournament(), var5);
            }
         }

         Path var19 = this.getDataFolder().toPath().getParent().resolve("SpawnArenaTournaments");
         ArrayList var20 = new ArrayList();
         Path var21 = var19.resolve("history");
         if (Files.isDirectory(var21, new LinkOption[0])) {
            Stream var22 = Files.list(var21);

            try {
               Stream var10000 = var22.filter((var0) -> {
                  return var0.toString().endsWith(".json");
               });
               Objects.requireNonNull(var20);
               var10000.forEach(var20::add);
            } catch (Throwable var17) {
               if (var22 != null) {
                  try {
                     var22.close();
                  } catch (Throwable var14) {
                     var17.addSuppressed(var14);
                  }
               }

               throw var17;
            }

            if (var22 != null) {
               var22.close();
            }
         }

         if (Files.exists(var19.resolve("session.json"), new LinkOption[0])) {
            var20.add(var19.resolve("session.json"));
         }

         int var23 = 0;
         boolean var6 = false;
         Iterator var7 = var20.iterator();

         while(var7.hasNext()) {
            Path var8 = (Path)var7.next();

            try {
               JsonObject var9 = JsonParser.parseString(Files.readString(var8)).getAsJsonObject();
               String var10 = var9.has("id") ? var9.get("id").getAsString() : "";
               if (var9.has("phase") && "FINISHED".equals(var9.get("phase").getAsString())) {
                  UUID.fromString(var10);
                  Path var11 = var19.resolve("payouts").resolve(var10 + ".txt");
                  ChampionReader.Champion var12 = ChampionReader.read(var9, Files.exists(var11, new LinkOption[0]) ? Files.readString(var11) : "");
                  if (var12 == null) {
                     if (!this.ledger.containsKey(var10)) {
                        ++var23;
                     }
                  } else {
                     ChampionReader.Champion var13 = (ChampionReader.Champion)this.ledger.get(var10);
                     if (var13 == null) {
                        this.ledger.put(var10, var12);
                        var6 = true;
                     } else if (!var13.uuid().equals(var12.uuid())) {
                        this.getLogger().warning("Conflicting champion for tournament " + var10 + "; retained recorded result.");
                     }
                  }
               }
            } catch (Exception var16) {
               Logger var28 = this.getLogger();
               String var10001 = String.valueOf(var8.getFileName());
               var28.warning("Could not read tournament record " + var10001 + ": " + var16.getClass().getSimpleName());
            }
         }

         if (var6) {
            Path var24 = var1.resolveSibling("champions.json.tmp");
            Files.writeString(var24, this.gson.toJson(this.ledger.values()), StandardCharsets.UTF_8);
            if (Files.exists(var1, new LinkOption[0])) {
               Files.copy(var1, this.getDataFolder().toPath().resolve("champions.previous.json"), StandardCopyOption.REPLACE_EXISTING);
            }

            try {
               Files.move(var24, var1, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException var15) {
               Files.move(var24, var1, StandardCopyOption.REPLACE_EXISTING);
            }
         }

         TreeMap<String, List<Row>> var25 = new TreeMap<>();
         Iterator var26 = this.ledger.values().iterator();

         while(var26.hasNext()) {
            ChampionReader.Champion var27 = (ChampionReader.Champion)var26.next();
            var25.computeIfAbsent(mode(var27.arena()), (var1x) -> {
               return rank(this.ledger.values(), var1x);
            });
         }

         this.modes = Map.copyOf(var25);
         this.top = rank(this.ledger.values(), (String)null);
         this.unverified = var23;
         this.ready = true;
      } catch (Exception var18) {
         this.getLogger().severe("Tournament leaderboard refresh failed; previous results retained: " + String.valueOf(var18));
      }

   }

   private boolean command(CommandSender var1, Command var2, String var3, String[] var4) {
      if (!this.ready) {
         var1.sendMessage("§dTournament leaderboard is loading.");
         return true;
      } else if (var4.length > 0 && var4[0].equalsIgnoreCase("status") && var1.hasPermission("tournamentwins.admin")) {
         int var13 = this.top.stream().mapToInt(Row::wins).sum();
         var1.sendMessage("Confirmed championships: " + var13 + "; ranked players: " + this.top.size() + "; unverified historical results: " + this.unverified + "; modes: " + String.valueOf(new TreeSet(this.modes.keySet())));
         return true;
      } else {
         String var5 = null;
         if (var4.length > 0) {
            String var6 = mode(var4[0].equalsIgnoreCase("top") && var4.length > 1 ? var4[1] : var4[0]);
            if (this.modes.containsKey(var6) || Set.of("sumo", "crystalpvp", "arena").contains(var6)) {
               var5 = var6;
            }
         }

         List var10 = var5 == null ? this.top : (List)this.modes.getOrDefault(var5, List.of());
         if (var4.length > 0 && !var4[0].equalsIgnoreCase("top") && var5 == null) {
            String var11 = var4[0];
            List<Row> var12 = var4.length > 1 ? (List<Row>)this.modes.getOrDefault(mode(var4[1]), List.of()) : this.top;
            Row var9 = var12.stream().filter((var1x) -> {
               return var1x.name().equalsIgnoreCase(var11);
            }).findFirst().orElse(null);
            var1.sendMessage("§d" + var11 + " §7has §f" + (var9 == null ? 0 : var9.wins()) + " §7recorded tournament championships.");
            return true;
         } else {
            String var10001 = var5 == null ? "" : " §7[" + var5 + "]";
            var1.sendMessage("§d§lBUNNYMC §5» §fTournament Champions" + var10001);
            if (var10.isEmpty()) {
               var1.sendMessage("§7No confirmed champions recorded yet.");
            }

            for(int var7 = 0; var7 < Math.min(10, var10.size()); ++var7) {
               Row var8 = (Row)var10.get(var7);
               var1.sendMessage("§5" + (var7 + 1) + ". §f" + var8.name() + " §8— §d" + var8.wins() + " §7championship" + (var8.wins() == 1 ? "" : "s"));
            }

            var1.sendMessage("§8Championship wins only • Updates every 10 seconds");
            return true;
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void alias(PlayerCommandPreprocessEvent var1) {
      Matcher var2 = Pattern.compile("(?i)^/(tournament|tourney|tour) (top|leaderboard|winners)(?: ([a-z0-9_-]+))?\\s*$").matcher(var1.getMessage());
      if (var2.matches()) {
         var1.setCancelled(true);
         this.command(var1.getPlayer(), this.getCommand("tournamentwins"), "tournamentwins", var2.group(3) == null ? new String[0] : new String[]{"top", var2.group(3)});
      }

   }

   public final class Expansion extends PlaceholderExpansion {
      public String getIdentifier() {
         return "tournamentwins";
      }

      public String getAuthor() {
         return "BunnyMC";
      }

      public String getVersion() {
         return "1.1.0";
      }

      public boolean persist() {
         return true;
      }

      public String onRequest(OfflinePlayer var1, String var2) {
         List<Row> var3 = TournamentWins.this.top;
         if (!var2.equals("wins") && !var2.equals("total") && !var2.startsWith("top_")) {
            Iterator var4 = List.of("_top_", "_wins", "_total").iterator();

            while(var4.hasNext()) {
               String var5 = (String)var4.next();
               int var6 = var2.indexOf(var5);
               if (var6 > 0) {
                  var3 = (List)TournamentWins.this.modes.getOrDefault(TournamentWins.mode(var2.substring(0, var6)), List.of());
                  var2 = var2.substring(var6 + 1);
                  break;
               }
            }
         }

         if (var2.equals("wins")) {
            return var1 == null ? "0" : (String)var3.stream().filter((var1x) -> {
               return var1x.uuid().equals(var1.getUniqueId().toString());
            }).map((var0) -> {
               return Integer.toString(var0.wins());
            }).findFirst().orElse("0");
         } else if (var2.equals("total")) {
            return Integer.toString(var3.stream().mapToInt(Row::wins).sum());
         } else {
            Matcher var8 = Pattern.compile("top_(\\d+)_(name|wins)").matcher(var2);
            if (var8.matches()) {
               int var9;
               try {
                  var9 = Integer.parseInt(var8.group(1)) - 1;
               } catch (Exception var7) {
                  return "";
               }

               if (var9 >= 0 && var9 < var3.size()) {
                  Row var10 = (Row)var3.get(var9);
                  return var8.group(2).equals("name") ? var10.name() : Integer.toString(var10.wins());
               } else {
                  return var8.group(2).equals("name") ? "—" : "0";
               }
            } else {
               return null;
            }
         }
      }
   }

   static record Row(String uuid, String name, int wins) {
      Row(String uuid, String name, int wins) {
         this.uuid = uuid;
         this.name = name;
         this.wins = wins;
      }

      public String uuid() {
         return this.uuid;
      }

      public String name() {
         return this.name;
      }

      public int wins() {
         return this.wins;
      }
   }
}
