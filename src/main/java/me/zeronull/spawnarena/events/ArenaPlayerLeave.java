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
        final boolean laggedOut = !fight.isFfa()
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

}
