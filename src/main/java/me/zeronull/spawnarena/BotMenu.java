package me.zeronull.spawnarena;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The sumo menu's bot sub-menu: pick a difficulty to play a practice match against a bot. */
public final class BotMenu implements InventoryHolder {
    private static final int[] SLOTS = {11, 12, 13, 14, 15};
    private static final int SLOT_BACK = 22;
    private static final Map<BotDifficulty, List<String>> ABOUT = Map.of(
            BotDifficulty.EASY, List.of("<gray>Slow to aim and react,", "<gray>misses a lot. A warm-up."),
            BotDifficulty.MEDIUM, List.of("<gray>Like a decent player:", "<gray>some w-taps and strafing."),
            BotDifficulty.HARD, List.of("<gray>W-taps, strafes, jump-resets", "<gray>and stays off the edge."),
            BotDifficulty.CHEATER, List.of("<gray>Hard, with extra reach", "<gray>and less knockback taken."),
            BotDifficulty.COMBO, List.of("<gray>Hard, with fists and", "<gray>no hit delay: combos!"));

    private final String arenaName;
    private final Inventory inventory;

    private BotMenu(final Arena arena, final Player viewer) {
        this.arenaName = arena.getArenaName();
        this.inventory = Bukkit.createInventory(this, 27, mm("<dark_gray>" + Arena.displayName(arena.getArenaName()) + " · Bots"));
        final ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < this.inventory.getSize(); i++)
            this.inventory.setItem(i, filler);
        final Map<String, Integer> beaten = SpawnArena.statsManager == null ? Map.of()
                : SpawnArena.statsManager.botWins(viewer.getUniqueId(), arena.getArenaName());
        final BotDifficulty[] levels = BotDifficulty.values();
        for (int i = 0; i < levels.length && i < SLOTS.length; i++) {
            final BotDifficulty level = levels[i];
            final List<String> lore = new ArrayList<>(ABOUT.get(level));
            lore.add("");
            lore.add("<gray>Bots beaten: <white>" + beaten.getOrDefault(level.name(), 0));
            lore.add("<dark_gray>Practice: doesn't count for stats");
            lore.add("");
            lore.add("<green>Click to fight");
            this.inventory.setItem(SLOTS[i], item(level.icon, level.colour + "<bold>" + level.label + " Bot", lore));
        }
        this.inventory.setItem(SLOT_BACK, item(Material.ARROW, "<gray>Back", List.of("<gray>To the queue menu")));
    }

    public static void open(final Player player, final Arena arena) {
        player.openInventory(new BotMenu(arena, player).getInventory());
    }

    @Override
    public @NotNull Inventory getInventory() {
        return this.inventory;
    }

    /** The bot item on the sumo queue menu. */
    public static ItemStack menuItem() {
        return item(Material.ZOMBIE_HEAD, "<light_purple><bold>Practice vs Bot", List.of(
                "<gray>Fight a bot: Easy, Medium,", "<gray>Hard, Cheater or Combo.", "<dark_gray>Doesn't count for stats", "", "<green>Click to pick a difficulty"));
    }

    private static ItemStack item(final Material material, final String name, final List<String> lore) {
        final ItemStack item = new ItemStack(material);
        final ItemMeta meta = item.getItemMeta();
        meta.displayName(mm(name));
        final List<Component> lines = new ArrayList<>();
        for (final String line : lore)
            lines.add(mm(line));
        meta.lore(lines);
        meta.setAttributeModifiers(com.google.common.collect.ImmutableMultimap.of());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        QueueMenu.markButton(meta);
        item.setItemMeta(meta);
        return item;
    }

    private static Component mm(final String text) {
        return MiniMessage.miniMessage().deserialize(text).decoration(TextDecoration.ITALIC, false);
    }

    /** Clicks in the bot menu: nothing can be taken out; a difficulty starts a match, Back goes back. */
    public static final class Clicks implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onClick(final InventoryClickEvent e) {
            if (!(e.getView().getTopInventory().getHolder() instanceof BotMenu menu))
                return;
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player player) || e.getClickedInventory() != e.getView().getTopInventory())
                return;
            final Arena arena = SpawnArena.arenas.of(menu.arenaName);
            if (e.getSlot() == SLOT_BACK) {
                Threads.entityLater(player, () -> {
                    if (arena != null)
                        QueueMenu.open(player, arena);
                    else
                        player.closeInventory();
                }, 1L);
                return;
            }
            BotDifficulty level = null;
            for (int i = 0; i < SLOTS.length && i < BotDifficulty.values().length; i++)
                if (SLOTS[i] == e.getSlot())
                    level = BotDifficulty.values()[i];
            if (level == null)
                return;
            final BotDifficulty chosen = level;
            Threads.entityLater(player, () -> {
                if (player.getOpenInventory().getTopInventory().getHolder() instanceof BotMenu)
                    player.closeInventory();
                QueueMenu.purge(player);
                if (arena == null) {
                    player.sendMessage(ChatColor.RED + "That arena no longer exists.");
                    return;
                }
                final String problem = BotFight.start(player, arena, chosen);
                if (problem != null)
                    player.sendMessage(ChatColor.RED + problem);
            }, 1L);
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDrag(final InventoryDragEvent e) {
            if (e.getView().getTopInventory().getHolder() instanceof BotMenu)
                e.setCancelled(true);
        }

        @EventHandler
        public void onClose(final org.bukkit.event.inventory.InventoryCloseEvent e) {
            if (e.getInventory().getHolder() instanceof BotMenu && e.getPlayer() instanceof Player p)
                Threads.entityLater(p, () -> QueueMenu.purge(p), 1L);
        }
    }
}
