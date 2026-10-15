package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.SpawnArena;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class ArenaPlayerJoinEvent implements Listener {
    @EventHandler
    public void onPlayerJoin(final PlayerJoinEvent e) {
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
        }, 20L);

        // IP history for the win-reward alt check; DB write kept off the main thread
        final java.util.UUID uuid = e.getPlayer().getUniqueId();
        final java.net.InetAddress address = e.getPlayer().getAddress() == null ? null : e.getPlayer().getAddress().getAddress();
        if (SpawnArena.statsManager != null && address != null)
            org.bukkit.Bukkit.getAsyncScheduler().runNow(SpawnArena.INSTANCE, task -> SpawnArena.statsManager.recordIp(uuid, address));
    }
}