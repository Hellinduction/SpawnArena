package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.DuelManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Shift-right-clicking a player near an arena sends them a duel request for that arena. */
public final class DuelRequestListener implements Listener {

    // Not ignoring cancelled events: other plugins (e.g. WorldGuard interact rules) may cancel clicks on players
    @EventHandler(priority = EventPriority.MONITOR)
    public void onShiftRightClick(final PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND)
            return; // fires once per hand
        if (!(e.getRightClicked() instanceof Player target) || target.hasMetadata("NPC"))
            return;

        final Player requester = e.getPlayer();
        if (!requester.isSneaking() || requester.equals(target))
            return;
        // Only someone you can actually see: not a vanished/hidden player, and not a spectator unless you
        // are one too. Silently, so it doesn't give away that they're there.
        if (!requester.canSee(target)
                || (target.getGameMode() == org.bukkit.GameMode.SPECTATOR && requester.getGameMode() != org.bukkit.GameMode.SPECTATOR))
            return;

        final Arena arena = DuelManager.arenaNear(requester.getLocation());
        if (arena == null)
            return; // not near an arena: a normal shift-right-click

        DuelManager.sendRequest(requester, target, arena);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent e) {
        DuelManager.clear(e.getPlayer());
    }

    // Same rule as the queue: teleporting away cancels pending duel requests
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(final PlayerTeleportEvent e) {
        if (DuelManager.clear(e.getPlayer()))
            e.getPlayer().sendMessage(ChatColor.RED + "Your duel requests were cancelled because you teleported.");
    }
}
