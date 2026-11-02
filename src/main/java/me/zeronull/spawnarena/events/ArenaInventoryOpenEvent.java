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
        // /arenabuild on can. An ender chest is fine: it only ever holds the player's own things
        final org.bukkit.Location at = e.getInventory().getLocation();
        if (at != null && at.getWorld() != null && e.getInventory().getType() != org.bukkit.event.inventory.InventoryType.ENDER_CHEST) {
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

    /**
     * MULTI crystal copy worlds carry copies of the real arena's WorldGuard regions, so where those deny using chests,
     * ender chests there couldn't be opened either. Anyone not fighting may open one (it only holds their own things).
     * After WorldGuard (HIGHEST), and only to allow: fighters stay under the crystal rules.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEnderChestInCopy(final org.bukkit.event.player.PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null
                || e.getClickedBlock().getType() != org.bukkit.Material.ENDER_CHEST
                || e.useInteractedBlock() != org.bukkit.event.Event.Result.DENY
                || CrystalCopies.at(e.getClickedBlock().getWorld()) == null || SpawnArena.arenas.hasFighter(e.getPlayer()))
            return;
        e.setUseInteractedBlock(org.bukkit.event.Event.Result.ALLOW);
        if (e.useItemInHand() == org.bukkit.event.Event.Result.DENY)
            e.setUseItemInHand(org.bukkit.event.Event.Result.DEFAULT);
    }
}