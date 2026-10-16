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
}
