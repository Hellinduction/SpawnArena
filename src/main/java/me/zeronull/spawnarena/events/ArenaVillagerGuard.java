package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.SpawnArena;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Minecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Villagers picked up with ClickVillagers can't be put down inside an arena (placed like a block, or sneak-clicked
 * into a boat or minecart), fight or not. Cancelled before ClickVillagers sees it, so the villager stays in the
 * player's hand. (Its dispensers ignore other plugins, but players can't build dispensers in arenas.)
 */
public final class ArenaVillagerGuard implements Listener {
    private static final NamespacedKey VILLAGER = new NamespacedKey("clickvillagers", "villager");

    /** A villager carried as an item by ClickVillagers (a player head marked as one). */
    private static boolean isVillager(final ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD)
            return false;
        final ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(VILLAGER);
    }

    private static boolean inArena(final Location location) {
        if (SpawnArena.arenas == null || !Arena.ArenaUtils.WORLD_EDIT_SUPPORT || !Arena.isArenaWorld(location.getWorld()))
            return false;
        return SpawnArena.arenas.of(location) != null;
    }

    /** Whether this place would put a villager down (ClickVillagers uses one held in either hand, whichever hand placed). */
    public static boolean placesVillager(final BlockPlaceEvent e) {
        return isVillager(e.getPlayer().getInventory().getItemInMainHand())
                || isVillager(e.getPlayer().getInventory().getItemInOffHand());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(final BlockPlaceEvent e) {
        if (placesVillager(e) && inArena(e.getBlockPlaced().getLocation())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(ChatColor.RED + "You can't put villagers down in an arena.");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVehicle(final PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Minecart) && !(e.getRightClicked() instanceof Boat))
            return;
        if (isVillager(e.getPlayer().getInventory().getItemInMainHand()) && inArena(e.getRightClicked().getLocation())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(ChatColor.RED + "You can't put villagers down in an arena.");
        }
    }
}
