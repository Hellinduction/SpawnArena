package me.zeronull.spawnarena.events;

import com.sk89q.worldguard.bukkit.event.DelegateEvent;
import com.sk89q.worldguard.bukkit.event.block.BreakBlockEvent;
import com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent;
import com.sk89q.worldguard.bukkit.event.block.UseBlockEvent;
import com.sk89q.worldguard.bukkit.event.entity.DamageEntityEvent;
import com.sk89q.worldguard.bukkit.event.entity.SpawnEntityEvent;
import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.CrystalPvp;
import me.zeronull.spawnarena.Fight;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.List;

/**
 * Pre-allows a crystal arena fighter's own actions in WorldGuard's internal events, so the region's
 * build/use/damage flags can stay denied and fighters don't get WorldGuard's "you can't" messages.
 * WorldGuard skips its region check for events that are already allowed. This only ever allows:
 * the real rules (what can be placed, broken or blown up) are enforced by {@link CrystalArenaListener}.
 */
public final class CrystalWorldGuardBypass implements Listener {

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(final PlaceBlockEvent e) {
        if (fighterActingOn(e, e.getBlocks(), false) != null)
            e.setAllowed(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(final BreakBlockEvent e) {
        // Only blocks placed during the fight
        if (fighterActingOn(e, e.getBlocks(), true) != null)
            e.setAllowed(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onUse(final UseBlockEvent e) {
        // Anchors, beds and anything else they placed themselves
        if (fighterActingOn(e, e.getBlocks(), true) != null)
            e.setAllowed(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpawnEntity(final SpawnEntityEvent e) {
        if (e.getEffectiveType() != EntityType.END_CRYSTAL)
            return;
        final Player player = e.getCause().getFirstPlayer();
        final Arena arena = CrystalPvp.arenaAt(e.getTarget());
        if (player != null && arena != null && CrystalPvp.isActiveFighter(arena, player))
            e.setAllowed(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamageEntity(final DamageEntityEvent e) {
        final Player player = e.getCause().getFirstPlayer();
        final Arena arena = CrystalPvp.arenaAt(e.getTarget());
        if (player == null || arena == null || !CrystalPvp.isActiveFighter(arena, player))
            return;

        // Popping a crystal from this fight
        if (e.getEntity() instanceof EnderCrystal) {
            if (CrystalPvp.isPlacedCrystal(arena, e.getEntity()))
                e.setAllowed(true);
            return;
        }

        // An opponent (or themselves) caught in their blast; teammates stay protected
        if (e.getEntity() instanceof Player victim && CrystalPvp.isActiveFighter(arena, victim)) {
            final Fight fight = arena.getFight(player);
            if (fight != null && !fight.areTeammates(player, victim))
                e.setAllowed(true);
        }
    }

    /**
     * The fighter behind this event if every block is in their crystal arena (and, when
     * {@code placedOnly}, was placed during the fight); otherwise null.
     */
    private static Player fighterActingOn(final DelegateEvent e, final List<Block> blocks, final boolean placedOnly) {
        final Player player = e.getCause().getFirstPlayer();
        if (player == null || blocks.isEmpty())
            return null;

        Arena arena = null;
        for (final Block block : blocks) {
            final Arena at = CrystalPvp.arenaAt(block);
            if (at == null || (arena != null && !arena.equals(at)))
                return null;
            arena = at;
            if (placedOnly && CrystalPvp.isOriginal(at, block))
                return null;
        }
        return CrystalPvp.isActiveFighter(arena, player) ? player : null;
    }
}
