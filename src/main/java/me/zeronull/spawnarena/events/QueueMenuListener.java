package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.FightMode;
import me.zeronull.spawnarena.QueueMenu;
import me.zeronull.spawnarena.SpawnArena;
import me.zeronull.spawnarena.commands.impl.JoinArenaQueueCommand;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public final class QueueMenuListener implements Listener {

    public QueueMenuListener() {
        // Keep open menus' player counts live
        me.zeronull.spawnarena.Threads.globalTimer(task -> {
            for (final Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                if (p.getOpenInventory().getTopInventory().getHolder() instanceof QueueMenu)
                    me.zeronull.spawnarena.Threads.entity(p, () -> {
                        if (p.getOpenInventory().getTopInventory().getHolder() instanceof QueueMenu menu)
                            menu.refresh(p);
                    });
            }
        }, 20L, 20L);
    }

    @EventHandler
    public void onClick(final InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof QueueMenu menu))
            return;
        e.setCancelled(true); // nothing in this menu can be taken or moved

        if (!(e.getWhoClicked() instanceof Player player) || e.getClickedInventory() != e.getView().getTopInventory())
            return;

        final FightMode mode = switch (e.getSlot()) {
            case QueueMenu.SLOT_1V1 -> FightMode.ONE_V_ONE;
            case QueueMenu.SLOT_2V2 -> FightMode.TWO_V_TWO;
            case QueueMenu.SLOT_AUTO -> null;
            default -> FightMode.ONE_V_ONE; // never used: filtered below
        };
        if (e.getSlot() != QueueMenu.SLOT_1V1 && e.getSlot() != QueueMenu.SLOT_2V2 && e.getSlot() != QueueMenu.SLOT_AUTO)
            return;
        // The permission could have been taken away while the menu was open
        if (e.getSlot() != QueueMenu.SLOT_1V1 && !player.hasPermission(me.zeronull.spawnarena.commands.impl.JoinArenaQueueCommand.MODES_PERMISSION)) {
            player.closeInventory();
            player.sendMessage(ChatColor.RED + "You can only queue 1v1.");
            return;
        }

        final Arena arena = SpawnArena.arenas.of(menu.getArenaName());
        player.closeInventory();
        if (arena == null) {
            player.sendMessage(ChatColor.RED + "That arena no longer exists.");
            return;
        }

        // Clicking the queue you're already in leaves it
        if (mode != null && arena.queue.modeOf(player) == mode) {
            arena.queue.removePlayer(player);
            player.sendMessage(ChatColor.YELLOW + "You've been removed from the queue!");
            return;
        }

        JoinArenaQueueCommand.join(player, arena, mode == null ? QueueMenu.autoMode(arena) : mode);
    }

    @EventHandler
    public void onDrag(final InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof QueueMenu)
            e.setCancelled(true);
    }
}
