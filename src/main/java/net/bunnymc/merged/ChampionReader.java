/*
 * Merged into SpawnArena from a separate plugin (11 Nov 2026). Its source was lost, so this was recovered by
 * decompiling the merged jar that ran live (hence the plain variable names); it behaves as that jar did
 * (checked with merged-modules-check.js). Started by SpawnArena.onEnable, see ArenaGuards / ArenaStatsModules.
 */
package net.bunnymc.merged;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChampionReader {
   public static Champion read(JsonObject var0, String var1) {
      if (!"FINISHED".equals(str(var0, "phase"))) {
         return null;
      } else {
         String var2 = str(var0, "id");
         UUID.fromString(var2);
         JsonObject var3 = var0.getAsJsonObject("entrants");
         if (var3 != null && var3.size() >= 2) {
            String var4 = null;
            String var5 = null;
            JsonObject var12;
            if (var0.has("localMode") && var0.get("localMode").getAsBoolean()) {
               var12 = var0.getAsJsonObject("localBracket");
               if (var12 == null) {
                  return null;
               }

               JsonArray var7 = var12.getAsJsonArray("nodes");
               if (var7 == null || var7.isEmpty()) {
                  return null;
               }

               JsonObject var8 = var7.get(var7.size() - 1).getAsJsonObject();
               if (!var8.has("resolved") || !var8.get("resolved").getAsBoolean() || var8.get("winner").getAsInt() <= 0) {
                  return null;
               }

               int var9 = var8.get("winner").getAsInt();
               Iterator var10 = var3.entrySet().iterator();

               while(var10.hasNext()) {
                  Map.Entry var11 = (Map.Entry)var10.next();
                  if (((JsonElement)var11.getValue()).getAsJsonObject().get("remote").getAsInt() == var9) {
                     var4 = (String)var11.getKey();
                  }
               }

               var5 = "completed-bracket";
            } else {
               Matcher var6 = Pattern.compile("^(?:PAID|EXTERNAL_COMMAND_ISSUED) winner=([0-9a-fA-F-]{36})(?: |$)").matcher(var1.trim());
               if (var6.find()) {
                  var4 = var6.group(1);
                  var5 = "winner-payout-record";
               }
            }

            if (var4 != null && var3.has(var4)) {
               UUID.fromString(var4);
               var12 = var3.getAsJsonObject(var4);
               return new Champion(var2, var4, str(var12, "name"), str(var0, "arena"), var5);
            } else {
               return null;
            }
         } else {
            return null;
         }
      }
   }

   private static String str(JsonObject var0, String var1) {
      return var0.has(var1) && !var0.get(var1).isJsonNull() ? var0.get(var1).getAsString() : "";
   }

   public static record Champion(String tournament, String uuid, String name, String arena, String evidence) {
      public Champion(String tournament, String uuid, String name, String arena, String evidence) {
         this.tournament = tournament;
         this.uuid = uuid;
         this.name = name;
         this.arena = arena;
         this.evidence = evidence;
      }

      public String tournament() {
         return this.tournament;
      }

      public String uuid() {
         return this.uuid;
      }

      public String name() {
         return this.name;
      }

      public String arena() {
         return this.arena;
      }

      public String evidence() {
         return this.evidence;
      }
   }
}
