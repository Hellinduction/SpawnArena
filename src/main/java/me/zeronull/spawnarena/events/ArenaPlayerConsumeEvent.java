package me.zeronull.spawnarena.events;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;

import java.util.Map;
import java.util.UUID;

public final class ArenaPlayerConsumeEvent implements Listener {
    public static final Map<UUID, Long> PLAYER_FINISH_GAME_MAP = new java.util.concurrent.ConcurrentHashMap<>();
    /** Nothing eaten for this long after their things are given back (milliseconds). */
    public static final long PREVENT_EATING_AFTER = 2_000;

    @EventHandler(ignoreCancelled = true)
    public void onPlayerItemConsume(final PlayerItemConsumeEvent e) {
        final Player p = e.getPlayer();
        final UUID uuid = p.getUniqueId();

        final long now = System.currentTimeMillis();
        final long then = PLAYER_FINISH_GAME_MAP.getOrDefault(uuid, 0L);

        final boolean before2SecondsAgo = now - then < PREVENT_EATING_AFTER;

        if (!before2SecondsAgo) {
            PLAYER_FINISH_GAME_MAP.remove(uuid);
            return;
        }

        e.setCancelled(true);
    }
}