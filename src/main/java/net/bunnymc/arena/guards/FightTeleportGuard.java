/*
 * Merged into SpawnArena from a separate plugin (11 Nov 2026). Its source was lost, so this was recovered by
 * decompiling the merged jar that ran live (hence the plain variable names); it behaves as that jar did
 * (checked with merged-modules-check.js). Started by SpawnArena.onEnable, see ArenaGuards / ArenaStatsModules.
 */
package net.bunnymc.arena.guards;

import java.util.Locale;
import java.util.Set;
import me.zeronull.spawnarena.SpawnArena;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

public final class FightTeleportGuard
extends GuardModule
implements Listener {
    private static final Set<String> ACCEPT = Set.of("tpaccept", "tpaaccept", "tpyes", "tpacceptall", "tpaacceptall", "tpy");

    static String bare(String string) {
        int n = string.indexOf(58);
        return (n < 0 ? string : string.substring(n + 1)).toLowerCase(Locale.ROOT);
    }

    static boolean acceptance(String string2) {
        if (ACCEPT.contains(FightTeleportGuard.bare(string2))) {
            return true;
        }
        Command command = Bukkit.getCommandMap().getCommand(string2);
        return command != null && (ACCEPT.contains(FightTeleportGuard.bare(command.getName())) || command.getAliases().stream().anyMatch(string -> ACCEPT.contains(FightTeleportGuard.bare(string))));
    }

    @Override
    public void onEnable() {
        this.getServer().getPluginManager().registerEvents(this, this.owner);
        this.getLogger().info("Teleport acceptance blocked for arena fighters, including permission bypass holders.");
    }

    @EventHandler(priority=EventPriority.LOWEST, ignoreCancelled=true)
    public void command(PlayerCommandPreprocessEvent playerCommandPreprocessEvent) {
        String string = playerCommandPreprocessEvent.getMessage().substring(1).split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!FightTeleportGuard.acceptance(string)) {
            return;
        }
        if (SpawnArena.arenas != null && SpawnArena.arenas.hasFighter(playerCommandPreprocessEvent.getPlayer())) {
            playerCommandPreprocessEvent.setCancelled(true);
            playerCommandPreprocessEvent.getPlayer().sendMessage("\u00a7cYou cannot accept teleports during an arena fight.");
        }
    }
}
