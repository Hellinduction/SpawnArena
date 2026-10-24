package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.SpawnArena;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.CraftingInventory;

public class ArenaPreventCraftingSlot implements Listener {
    private static boolean inCrystalFight(final Player player) {
        final me.zeronull.spawnarena.Arena arena = SpawnArena.arenas.of(player);
        return arena != null && arena.isCrystalPvp();
    }

    @EventHandler
    public void onClickInventory(final InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();

        if (!SpawnArena.arenas.hasActiveFight())
            return;

        if (!SpawnArena.arenas.hasFighter(player))
            return;

        if (!(event.getSlotType() == InventoryType.SlotType.CRAFTING)) {
            return;
        }

        // A crafting table they placed in a crystal fight (it only opens for them if they did), and their own crafting
        // grid in a crystal fight: what they make is the fight's, and whatever's left in the grid goes when it ends
        if (event.getView().getTopInventory().getType() == InventoryType.WORKBENCH || inCrystalFight(player))
            return;

        event.setCancelled(true);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cYou can't craft while in the arena!"));
    }

    @EventHandler
    public void onInventoryDrag(final InventoryDragEvent event) {
        Player player = (Player) event.getWhoClicked();

        if (!SpawnArena.arenas.hasActiveFight())
            return;

        if (!SpawnArena.arenas.hasFighter(player))
            return;

        if (event.getInventory() instanceof CraftingInventory && event.getInventory().getType() != InventoryType.WORKBENCH && !inCrystalFight(player)) {
            for (int slot : event.getRawSlots()) {
                if (slot > 0 && slot < 5) {
                    event.setCancelled(true);
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cYou can't craft while in the arena!"));
                    return;
                }
            }
        }
    }
}
