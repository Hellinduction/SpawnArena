package me.zeronull.spawnarena;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** The per-arena "1v1 / Auto / 2v2" queue picker opened by /joinarenaqueue <arena> (and the NPCs). */
public final class QueueMenu implements InventoryHolder {
    public static final int SLOT_1V1 = 11;
    public static final int SLOT_AUTO = 13;
    public static final int SLOT_2V2 = 15;

    private final String arenaName;
    private final Inventory inventory;

    private QueueMenu(final Arena arena, final Player viewer) {
        this.arenaName = arena.getArenaName();
        this.inventory = Bukkit.createInventory(this, 27,
                mm("<dark_gray>" + Arena.displayName(arena.getArenaName()) + " Queue"));
        this.fill(arena, viewer);
    }

    public static void open(final Player player, final Arena arena) {
        player.openInventory(new QueueMenu(arena, player).getInventory());
    }

    public String getArenaName() {
        return this.arenaName;
    }

    /** Re-draws the counts for the player looking at it. */
    public void refresh(final Player viewer) {
        final Arena arena = SpawnArena.arenas.of(this.arenaName);
        if (arena != null)
            this.fill(arena, viewer);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return this.inventory;
    }

    /** The mode Auto would pick right now: whichever queue has more players waiting (1v1 on a tie). */
    public static FightMode autoMode(final Arena arena) {
        return arena.queue.size(FightMode.TWO_V_TWO) > arena.queue.size(FightMode.ONE_V_ONE)
                ? FightMode.TWO_V_TWO : FightMode.ONE_V_ONE;
    }

    private void fill(final Arena arena, final Player viewer) {
        final ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < this.inventory.getSize(); i++)
            this.inventory.setItem(i, filler);

        final FightMode current = arena.queue.modeOf(viewer);

        this.inventory.setItem(SLOT_1V1, item(Material.IRON_SWORD, "<white><bold>1v1</bold>", List.of(
                "<gray>Players waiting: <white>" + arena.queue.size(FightMode.ONE_V_ONE),
                "<gray>Matches in progress: <white>" + matches(arena, FightMode.ONE_V_ONE),
                "",
                action(current == FightMode.ONE_V_ONE))));

        final FightMode auto = autoMode(arena);
        this.inventory.setItem(SLOT_AUTO, item(Material.NETHER_STAR, "<gold><bold>Auto</bold>", List.of(
                "<gray>Joins whichever queue has",
                "<gray>the most players waiting.",
                "<gray>Right now: <white>" + auto.label(),
                "",
                "<green>Click to join")));

        final ItemStack duo = item(Material.DIAMOND_SWORD, "<aqua><bold>2v2</bold>", List.of(
                "<gray>Players waiting: <white>" + arena.queue.size(FightMode.TWO_V_TWO) + "/" + FightMode.TWO_V_TWO.players(),
                "<gray>Matches in progress: <white>" + matches(arena, FightMode.TWO_V_TWO),
                "<gray>Teams are picked for you;",
                "<gray>friends are kept together.",
                "",
                action(current == FightMode.TWO_V_TWO)));
        duo.setAmount(2);
        this.inventory.setItem(SLOT_2V2, duo);
    }

    private static String action(final boolean inThisQueue) {
        return inThisQueue ? "<yellow>You're in this queue. Click to leave." : "<green>Click to join";
    }

    private static long matches(final Arena arena, final FightMode mode) {
        return arena.getFights().stream().filter(f -> f.getMode() == mode).count();
    }

    private static ItemStack item(final Material material, final String name, final List<String> lore) {
        final ItemStack item = new ItemStack(material);
        final ItemMeta meta = item.getItemMeta();
        meta.displayName(mm(name));
        final List<Component> lines = new ArrayList<>();
        for (final String line : lore)
            lines.add(mm(line));
        meta.lore(lines);
        // HIDE_ATTRIBUTES only takes effect when the item has explicit (here: no) attribute modifiers
        meta.setAttributeModifiers(com.google.common.collect.ImmutableMultimap.of());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private static Component mm(final String text) {
        return MiniMessage.miniMessage().deserialize(text).decoration(TextDecoration.ITALIC, false);
    }
}
