package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.Fight;
import me.zeronull.spawnarena.FightState;
import me.zeronull.spawnarena.SpawnArena;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredListener;

public class ArenaPlayerLeave implements Listener {

    /**
     * A fighter logging out gets their own things back right here, before anything else hears they've gone: a plugin that
     * saves inventories as players leave (PerWorlds keeps one per world group, saved at LOWEST) would otherwise save the
     * match kit and put that back over their real inventory when they next join. So this runs at LOWEST, and
     * {@link #runFirst} puts it ahead of every other plugin's LOWEST handler.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        me.zeronull.spawnarena.AnticheatExempt.remove(player.getUniqueId()); // by uuid, so it works once they're gone
        if (!SpawnArena.arenas.hasActiveFight()) {
            return;
        }

        if (!SpawnArena.arenas.hasFighter(player)) {
            return;
        }

        final Arena arena = SpawnArena.arenas.of(player);
        final Fight fight = arena.getFight(player);

        if (fight == null)
            return;

        if (fight.getState() == FightState.COUNTDOWN) {
            return;
        }

        // Their connection dropped (rather than them leaving): no loss, while they have lag-outs left today
        // (Not in a free-for-all: there, logging out is only a death if they were in combat)
        // In arenas where touching water loses, only if they were standing on the platform when their game went quiet:
        // someone already falling in could otherwise hold their game frozen until they're timed out for a draw
        final long quiet = me.zeronull.spawnarena.LagOuts.quietMillis(player);
        final boolean onPlatform = !arena.isDeathOnTouchLiquid() || quiet < 0
                || ArenaTeleportGrace.onPlatformAt(player, arena, System.currentTimeMillis() - quiet);
        final boolean laggedOut = !fight.isFfa() && onPlatform
                && me.zeronull.spawnarena.LagOuts.looksLikeLagOut(event)
                && fight.isFighter(player)
                && me.zeronull.spawnarena.LagOuts.takeAllowance(player, fight);

        // Anything owed back that doesn't fit is saved for their next join instead of dropped
        me.zeronull.spawnarena.PlayerPreFightData.QUITTING.add(player.getUniqueId());
        try {
            if (laggedOut) {
                fight.lagOut(player);
            } else {
                fight.markQuit(player);
                fight.eliminate(player);
            }
        } finally {
            me.zeronull.spawnarena.PlayerPreFightData.QUITTING.remove(player.getUniqueId());
        }
    }

    /**
     * Moves this listener's quit handler to the very front (handlers of the same priority run in the order they were
     * registered, and a plugin enabled before this one, or this one reloaded, would otherwise go first). Everyone
     * else's handlers keep their order; plugins enabled later are added behind it anyway.
     */
    public static void runFirst(final ArenaPlayerLeave ours) {
        final org.bukkit.event.HandlerList handlers = PlayerQuitEvent.getHandlerList();
        synchronized (handlers) {
            final RegisteredListener[] all = handlers.getRegisteredListeners();
            RegisteredListener mine = null;
            for (final RegisteredListener l : all)
                if (l.getListener() == ours)
                    mine = l;
            if (mine == null || (all.length > 0 && all[0] == mine))
                return;
            for (final RegisteredListener l : all)
                handlers.unregister(l);
            handlers.register(mine);
            for (final RegisteredListener l : all)
                if (l != mine)
                    handlers.register(l);
        }
    }
}
