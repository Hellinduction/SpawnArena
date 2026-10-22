package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.*;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;

public final class ArenaInventoryOpenEvent implements Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(final InventoryOpenEvent e) {
        final HumanEntity humanEntity = e.getPlayer();

        if (!(humanEntity instanceof Player))
            return;

        final Player player = (Player) humanEntity;

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

        // Crystal fights: a container they placed themselves (not the arena's own, not an ender chest)
        if (arena.isCrystalPvp() && CrystalPvp.canOpen(arena, e.getInventory().getHolder(false)))
            return;

        e.setCancelled(true);
    }
}