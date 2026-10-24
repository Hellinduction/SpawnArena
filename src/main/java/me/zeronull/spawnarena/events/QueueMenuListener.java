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
        // A menu button anywhere else (it should never be): gone
        if (QueueMenu.isButton(e.getCurrentItem()) || QueueMenu.isButton(e.getCursor())) {
            if (!(e.getView().getTopInventory().getHolder() instanceof QueueMenu)) {
                e.setCancelled(true);
                if (e.getWhoClicked() instanceof Player p)
                    me.zeronull.spawnarena.Threads.entity(p, () -> QueueMenu.purge(p));
                return;
            }
        }
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
            closeLater(player);
            player.sendMessage(ChatColor.RED + "You can only queue 1v1.");
            return;
        }

        final Arena arena = SpawnArena.arenas.of(menu.getArenaName());
        closeLater(player);
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

    /**
     * Closes the menu a tick later: closing it inside the click event, before the server has undone the cancelled
     * click on the player's screen, can leave them seeing the button they clicked as if it were in their inventory.
     */
    private static void closeLater(final Player player) {
        me.zeronull.spawnarena.Threads.entityLater(player, () -> {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof QueueMenu)
                player.closeInventory();
            QueueMenu.purge(player);
        }, 1L);
    }

    /** Whatever happened while it was open, none of its buttons leave with them. */
    @EventHandler
    public void onClose(final org.bukkit.event.inventory.InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof QueueMenu && e.getPlayer() instanceof Player p)
            me.zeronull.spawnarena.Threads.entityLater(p, () -> QueueMenu.purge(p), 1L);
    }

    @EventHandler
    public void onJoin(final org.bukkit.event.player.PlayerJoinEvent e) {
        QueueMenu.purge(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onButtonSpawn(final org.bukkit.event.entity.ItemSpawnEvent e) {
        if (QueueMenu.isButton(e.getEntity().getItemStack()))
            e.setCancelled(true);
    }

    /** Again after every other plugin: nothing may take this menu's click back off cancelled. */
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onClickLast(final InventoryClickEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof QueueMenu)
            e.setCancelled(true);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onDropButton(final org.bukkit.event.player.PlayerDropItemEvent e) {
        if (QueueMenu.isButton(e.getItemDrop().getItemStack())) {
            e.getItemDrop().remove();
            // A tick later: if the drop was cancelled, it's back in their inventory only after this event
            me.zeronull.spawnarena.Threads.entityLater(e.getPlayer(), () -> QueueMenu.purge(e.getPlayer()), 1L);
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onPickupButton(final org.bukkit.event.entity.EntityPickupItemEvent e) {
        if (QueueMenu.isButton(e.getItem().getItemStack())) {
            e.setCancelled(true);
            e.getItem().remove();
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onDragLast(final InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof QueueMenu)
            e.setCancelled(true);
    }

    @EventHandler
    public void onDrag(final InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof QueueMenu)
            e.setCancelled(true);
    }
}
