package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.*;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.InventoryHolder;

public final class ArenaPlayerInteractEvent implements Listener {
    @EventHandler
    public void onPlayerInteract(final PlayerInteractEvent e) {
        Player player = e.getPlayer();

        if (!SpawnArena.arenas.hasActiveFight())
            return;

        if (!SpawnArena.arenas.hasFighter(player)) {
            return;
        }

        final Arena arena = SpawnArena.arenas.of(player);
        final Fight fight = arena.getFight(player);

        if (fight == null) {
            return;
        }

        if (fight.getState() == FightState.COUNTDOWN) {
            return;
        }

        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && (e.getClickedBlock().getState() instanceof InventoryHolder || e.getClickedBlock().getType() == Material.ENDER_CHEST)) {
            // Crystal fights: containers they placed themselves open (ArenaInventoryOpenEvent checks again)
            if (!(arena.isCrystalPvp() && e.getClickedBlock().getState(false) instanceof InventoryHolder holder
                    && CrystalPvp.canOpen(arena, holder.getInventory().getHolder(false))))
                e.setCancelled(true);
            return;
        }

        if (!arena.isWinOnStepStonePressurePlate()) {
            return;
        }

        // Only actually stepping on a stone pressure plate wins, not any click
        if (e.getAction() != Action.PHYSICAL || e.getClickedBlock() == null
                || e.getClickedBlock().getType() != Material.STONE_PRESSURE_PLATE
                || fight.getState() != FightState.IN_FIGHT) {
            return;
        }

        fight.winForTeamOf(player);
    }
}