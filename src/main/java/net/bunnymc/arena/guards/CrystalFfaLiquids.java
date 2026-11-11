/*
 * Merged into SpawnArena from a separate plugin (11 Nov 2026). Its source was lost, so this was recovered by
 * decompiling the merged jar that ran live (hence the plain variable names); it behaves as that jar did
 * (checked with merged-modules-check.js). Started by SpawnArena.onEnable, see ArenaGuards / ArenaStatsModules.
 */
package net.bunnymc.arena.guards;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.CrystalCopy;
import me.zeronull.spawnarena.CrystalPvp;
import me.zeronull.spawnarena.SpawnArena;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;

public final class CrystalFfaLiquids
extends GuardModule
implements Listener {
    @Override
    public void onEnable() {
        this.getServer().getPluginManager().registerEvents(this, this.owner);
    }

    private boolean ffa(Arena arena) {
        if (arena instanceof CrystalCopy) {
            CrystalCopy crystalCopy = (CrystalCopy)arena;
            arena = crystalCopy.real();
        }
        return arena != null && "crystalffa".equalsIgnoreCase(arena.getArenaName());
    }

    private boolean protectedArea(Player player, Block block) {
        return this.ffa(CrystalPvp.arenaAt(block)) || SpawnArena.arenas != null && this.ffa(SpawnArena.arenas.of(player));
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void empty(PlayerBucketEmptyEvent playerBucketEmptyEvent) {
        if (this.protectedArea(playerBucketEmptyEvent.getPlayer(), playerBucketEmptyEvent.getBlock()) || this.protectedArea(playerBucketEmptyEvent.getPlayer(), playerBucketEmptyEvent.getBlockClicked())) {
            playerBucketEmptyEvent.setCancelled(true);
            playerBucketEmptyEvent.getPlayer().sendMessage("\u00a7cWater and lava cannot be placed in CrystalFFA.");
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void interact(PlayerInteractEvent playerInteractEvent) {
        if (playerInteractEvent.getAction() != Action.RIGHT_CLICK_BLOCK || playerInteractEvent.getClickedBlock() == null || playerInteractEvent.getItem() == null) {
            return;
        }
        Material material = playerInteractEvent.getItem().getType();
        if (material == Material.BUCKET || !material.name().endsWith("_BUCKET") || material == Material.MILK_BUCKET) {
            return;
        }
        if (!this.protectedArea(playerInteractEvent.getPlayer(), playerInteractEvent.getClickedBlock()) && !this.protectedArea(playerInteractEvent.getPlayer(), playerInteractEvent.getClickedBlock().getRelative(playerInteractEvent.getBlockFace()))) {
            return;
        }
        playerInteractEvent.setUseItemInHand(Event.Result.DENY);
        playerInteractEvent.getPlayer().sendMessage("\u00a7cWater and lava cannot be placed in CrystalFFA.");
    }
}
