package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.Fight;
import me.zeronull.spawnarena.FightState;
import me.zeronull.spawnarena.SpawnArena;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public class ArenaPlayerLeave implements Listener {

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (SpawnArena.statsManager != null)
            SpawnArena.statsManager.forgetStandings(player.getUniqueId());

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
        final boolean laggedOut = me.zeronull.spawnarena.LagOuts.looksLikeLagOut(event)
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

}
