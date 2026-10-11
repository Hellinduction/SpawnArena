package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.SpawnArena;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps non-fighters out of an arena while a fight is inside it: walking in is pushed back,
 * teleporting in (pearls, /tpa, ...) is cancelled, and anyone already inside is sent out.
 */
public final class ArenaSpectatorGuard implements Listener {
    private static final long MESSAGE_COOLDOWN_MILLIS = 3_000;
    private final Map<UUID, Long> lastMessage = new HashMap<>();
    private static final long KICK_COOLDOWN_MILLIS = 2_000;
    private final Map<UUID, Long> lastKick = new HashMap<>();

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent e) {
        final Location to = e.getTo();
        if (e.getFrom().getBlockX() == to.getBlockX() && e.getFrom().getBlockY() == to.getBlockY()
                && e.getFrom().getBlockZ() == to.getBlockZ() && e.getFrom().getWorld() == to.getWorld())
            return;

        if (!SpawnArena.arenas.hasActiveFight())
            return;

        final Player player = e.getPlayer();
        if (Arena.ArenaUtils.isExemptFromKickOut(player))
            return;

        final Arena arena = SpawnArena.arenas.of(to);
        if (arena == null || !arena.hasRunningFight())
            return;

        // Already inside (e.g. was standing there when the fight started) — send them out,
        // throttled so a kick-out command that doesn't work can't run on every step
        if (arena.equals(SpawnArena.arenas.of(e.getFrom()))) {
            final long now = System.currentTimeMillis();
            final Long last = lastKick.get(player.getUniqueId());
            if (last == null || now - last >= KICK_COOLDOWN_MILLIS) {
                lastKick.put(player.getUniqueId(), now);
                arena.kickOut(player);
            }
            return;
        }

        // Walking in: stay where they were, but keep the direction they're looking
        final Location back = e.getFrom().clone();
        back.setYaw(to.getYaw());
        back.setPitch(to.getPitch());
        e.setTo(back);
        notify(player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(final PlayerTeleportEvent e) {
        if (e.getTo() == null || !SpawnArena.arenas.hasActiveFight())
            return;

        final Player player = e.getPlayer();
        if (Arena.ArenaUtils.isExemptFromKickOut(player))
            return;

        final Arena arena = SpawnArena.arenas.of(e.getTo());
        if (arena == null || !arena.hasRunningFight())
            return;

        e.setCancelled(true);
        notify(player);
    }

    private void notify(final Player player) {
        final long now = System.currentTimeMillis();
        final Long last = lastMessage.get(player.getUniqueId());
        if (last != null && now - last < MESSAGE_COOLDOWN_MILLIS)
            return;
        lastMessage.put(player.getUniqueId(), now);
        player.sendMessage(ChatColor.RED + "There's a fight going on in that arena. Wait for it to finish!");
    }
}
