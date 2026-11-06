package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.SpawnArena;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class ArenaPlayerJoinEvent implements Listener {
    @EventHandler
    public void onPlayerJoin(final PlayerJoinEvent e) {
        me.zeronull.spawnarena.FfaFight.clearLeftoverProtection(e.getPlayer());
        Arena.ArenaUtils.kickOutLingeringPlayer(e.getPlayer());

        // Undo any fight hiding left over from a fight that ended while they (or the other person) were
        // offline: nobody who just joined is in a fight, so nobody should be hidden from them by us.
        // (Only our own hiding; vanish plugins hide players under their own name.)
        final org.bukkit.entity.Player joining = e.getPlayer();
        for (final org.bukkit.entity.Player other : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (other.equals(joining))
                continue;
            me.zeronull.spawnarena.Threads.entity(other, () -> other.showPlayer(SpawnArena.INSTANCE, joining));
            me.zeronull.spawnarena.Threads.entity(joining, () -> joining.showPlayer(SpawnArena.INSTANCE, other));
        }
        final org.bukkit.entity.Player joined = e.getPlayer();
        me.zeronull.spawnarena.Threads.entityLater(joined, () -> {
            if (!joined.isOnline())
                return;
            me.zeronull.spawnarena.PlayerPreFightData.restoreLeftOver(joined);
            me.zeronull.spawnarena.PlayerPreFightData.giveOwedItemsAsync(joined);
            takeKitItems(joined);
        }, 20L);

        // IP history for the win-reward alt check; DB write kept off the main thread
        final java.util.UUID uuid = e.getPlayer().getUniqueId();
        final java.net.InetAddress address = e.getPlayer().getAddress() == null ? null : e.getPlayer().getAddress().getAddress();
        if (SpawnArena.statsManager != null)
            SpawnArena.statsManager.rememberName(uuid, e.getPlayer().getName());
        if (SpawnArena.statsManager != null && address != null)
            org.bukkit.Bukkit.getAsyncScheduler().runNow(SpawnArena.INSTANCE, task -> SpawnArena.statsManager.recordIp(uuid, address));
    }

    /**
     * Nobody joining is in a match, so nothing handed out for one (the Slap Fish, a match's snowballs...) may still be on
     * them: if something else put a match inventory back (a plugin's own copy of their inventory, saved mid-match), it's
     * taken away here rather than kept. Inventory, off hand, cursor and ender chest.
     */
    private static void takeKitItems(final org.bukkit.entity.Player player) {
        if (SpawnArena.arenas != null && SpawnArena.arenas.hasFighter(player))
            return;
        int taken = 0;
        for (final org.bukkit.inventory.Inventory inv : java.util.List.of(player.getInventory(), player.getEnderChest()))
            for (int i = 0; i < inv.getSize(); i++)
                if (me.zeronull.spawnarena.SlapFish.isKitItem(inv.getItem(i))) {
                    inv.setItem(i, null);
                    taken++;
                }
        if (me.zeronull.spawnarena.SlapFish.isKitItem(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
            taken++;
        }
        if (taken > 0)
            SpawnArena.INSTANCE.getLogger().info("Took " + taken + " match kit item(s) off " + player.getName() + " as they joined (not in a match).");
    }
}
