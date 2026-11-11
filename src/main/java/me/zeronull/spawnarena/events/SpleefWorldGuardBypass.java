package me.zeronull.spawnarena.events;

import com.sk89q.worldguard.bukkit.event.block.BreakBlockEvent;
import me.zeronull.spawnarena.Fight;
import me.zeronull.spawnarena.Spleef;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Lets a spleef fighter break their arena's floor past WorldGuard (the region's build flag can stay denied, and they
 * don't get its "you can't" message): WorldGuard skips its check for events already allowed. Only floor blocks of their
 * own live match; everything else is WorldGuard's as usual, and the real rules are in {@link Spleef}.
 */
public final class SpleefWorldGuardBypass implements Listener {

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(final BreakBlockEvent e) {
        final Player player = e.getCause().getFirstPlayer();
        if (player == null || e.getBlocks().isEmpty())
            return;
        final Fight fight = Spleef.fightOf(player);
        if (fight == null)
            return;
        for (final Block block : e.getBlocks())
            if (!Spleef.isFloor(fight, block))
                return;
        e.setAllowed(true);
    }
}
