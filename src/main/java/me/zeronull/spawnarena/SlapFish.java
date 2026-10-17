package me.zeronull.spawnarena;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * The knockback fish arenas can hand out (a fish rather than a stick: Litematica uses the stick as its tool item).
 * It's marked, so it can't be eaten: a tropical fish is food, and a fighter low on hunger would otherwise lose it.
 */
public final class SlapFish implements Listener {
    private static final NamespacedKey KEY = new NamespacedKey(SpawnArena.INSTANCE, "slap_fish");
    /** Any item handed out for a fight (random sumo's bow, rod, snowballs...): kept to that fight like the fish. */
    private static final NamespacedKey KIT = new NamespacedKey(SpawnArena.INSTANCE, "fight_kit");

    /** Marks an item as handed out for the fight. */
    public static ItemStack tag(final ItemStack item) {
        item.editMeta(meta -> meta.getPersistentDataContainer().set(KIT, PersistentDataType.BYTE, (byte) 1));
        return item;
    }

    /** The fish, or anything else handed out for a fight. */
    public static boolean isKitItem(final ItemStack item) {
        return isSlapFish(item) || (item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(KIT, PersistentDataType.BYTE));
    }

    public static ItemStack create() {
        final ItemStack fish = new ItemStack(Material.TROPICAL_FISH);
        final Enchantment knockback = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("knockback"));
        if (knockback != null)
            fish.addUnsafeEnchantment(knockback, 1);
        fish.editMeta(meta -> {
            meta.displayName(MiniMessage.miniMessage().deserialize(
                    "<!italic><aqua><obfuscated>|</obfuscated></aqua><yellow><bold>Slap Fish</bold></yellow><aqua><obfuscated>|</obfuscated></aqua>"));
            meta.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
        });
        return fish;
    }

    public static boolean isSlapFish(final ItemStack item) {
        return item != null && item.getType() == Material.TROPICAL_FISH && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(KEY, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEat(final PlayerItemConsumeEvent e) {
        if (isSlapFish(e.getItem()))
            e.setCancelled(true);
    }

    /** It's only for the fight it was handed out in: it can't be dropped, even by staff allowed to drop items. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(final org.bukkit.event.player.PlayerDropItemEvent e) {
        if (isKitItem(e.getItemDrop().getItemStack()))
            e.setCancelled(true);
    }

    /** One that's on the ground anyway (older versions let staff drop it) goes, rather than to whoever walks over it. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(final org.bukkit.event.entity.EntityPickupItemEvent e) {
        if (isKitItem(e.getItem().getItemStack())) {
            e.setCancelled(true);
            e.getItem().remove();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHopperPickup(final org.bukkit.event.inventory.InventoryPickupItemEvent e) {
        if (isKitItem(e.getItem().getItemStack())) {
            e.setCancelled(true);
            e.getItem().remove();
        }
    }
}
