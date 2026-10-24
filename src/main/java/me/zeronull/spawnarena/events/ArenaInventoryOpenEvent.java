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

        // Onlookers can't open anything in a crystal arena (containers fighters placed, crafting tables...): a fighter's
        // things there are copies that go when the fight ends, so taking them out would be a dupe. Staff with
        // /arenabuild on can
        final org.bukkit.Location at = e.getInventory().getLocation();
        if (at != null && at.getWorld() != null) {
            final Arena there = CrystalPvp.arenaAt(at);
            if (there != null && !CrystalPvp.isActiveFighter(there, player)
                    && !me.zeronull.spawnarena.commands.impl.ArenaBuildCommand.isOn(player)) {
                e.setCancelled(true);
                return;
            }
        }

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

        // Crystal fights: a container or crafting table they placed themselves (not the arena's own, not an ender chest)
        if (arena.isCrystalPvp() && CrystalPvp.canOpen(arena, e.getInventory()))
            return;

        e.setCancelled(true);
    }
}