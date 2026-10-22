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
        if (fighterActingOn(e, e.getBlocks(), null) != null)
            e.setAllowed(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(final BreakBlockEvent e) {
        // An explosion going off in a crystal fight (a crystal or anchor): it keeps just what a fighter could break
        // there, and WorldGuard lets that go. Explosions anywhere else are WorldGuard's business as usual
        final org.bukkit.Location origin = e.getOriginalEvent() instanceof org.bukkit.event.entity.EntityExplodeEvent ex ? ex.getLocation()
                : e.getOriginalEvent() instanceof org.bukkit.event.block.BlockExplodeEvent bx ? bx.getBlock().getLocation() : null;
        if (origin != null) {
            final Arena arena = CrystalPvp.arenaAt(origin);
            if (arena == null || CrystalPvp.activeFight(arena) == null)
                return;
            e.filter(loc -> arena.equals(CrystalPvp.arenaAt(loc)) && CrystalPvp.canBreak(arena, loc.getBlock()), false);
            e.setAllowed(true);
            return;
        }
        // Blocks placed during the fight, and the floor they may dig into
        if (fighterActingOn(e, e.getBlocks(), CrystalPvp::canBreak) != null)
            e.setAllowed(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onUse(final UseBlockEvent e) {
        // Placing TNT counts as using the block it's put against (WorldGuard checks TNT that way): CrystalArenaListener
        // decides where it can go, as for any block
        if (e.getEffectiveMaterial() == org.bukkit.Material.TNT && fighterActingOn(e, e.getBlocks(), null) != null) {
            e.setAllowed(true);
            return;
        }
        // Anchors, beds and anything else they placed themselves (down a hole they dug too)
        if (fighterActingOn(e, e.getBlocks(), CrystalPvp::canBreak) != null)
            e.setAllowed(true);
    }

    // WorldGuard says nothing to a crystal fighter: what they can't do there, CrystalArenaListener tells them itself
    // (otherwise they'd get WorldGuard's "Hey! Sorry, but you can't..." as well as ours)

    @EventHandler(priority = EventPriority.LOWEST)
    public void quietPlace(final PlaceBlockEvent e) {
        quietForFighter(e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void quietBreak(final BreakBlockEvent e) {
        quietForFighter(e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void quietUse(final UseBlockEvent e) {
        quietForFighter(e);
    }

    private static void quietForFighter(final DelegateEvent e) {
        final Player player = e.getCause().getFirstPlayer();
        if (player == null || me.zeronull.spawnarena.SpawnArena.arenas == null)
            return;
        final Arena own = me.zeronull.spawnarena.SpawnArena.arenas.of(player);
        if (own != null && own.isCrystalPvp() && CrystalPvp.isActiveFighter(own, player))
            e.setSilent(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpawnEntity(final SpawnEntityEvent e) {
        final Arena arena = CrystalPvp.arenaAt(e.getTarget());
        if (arena == null)
            return;
        final Player player = e.getCause().getFirstPlayer();
        final EntityType type = e.getEffectiveType();
        // Crystals and dropped items: a fighter's own, in their arena (CrystalArenaListener decides the rest).
        // Primed TNT: only where it was placed during the fight, whoever or whatever lit it (CrystalArenaListener
        // checks the same before it can appear)
        if (((type == EntityType.END_CRYSTAL || type == EntityType.ITEM) && player != null && CrystalPvp.isActiveFighter(arena, player))
                || (type == EntityType.TNT && CrystalPvp.activeFight(arena) != null && CrystalPvp.canBuild(arena, e.getTarget().getBlock())))
            e.setAllowed(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void quietSpawn(final SpawnEntityEvent e) {
        quietForFighter(e);
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
     * The fighter behind this event if every block is in their crystal arena (and passes {@code allowed}, if
     * given); otherwise null.
     */
    private static Player fighterActingOn(final DelegateEvent e, final List<Block> blocks,
                                          final java.util.function.BiPredicate<Arena, Block> allowed) {
        final Player player = e.getCause().getFirstPlayer();
        if (player == null || blocks.isEmpty())
            return null;

        Arena arena = null;
        for (final Block block : blocks) {
            final Arena at = CrystalPvp.arenaAt(block);
            if (at == null || (arena != null && !arena.equals(at)))
                return null;
            arena = at;
            if (allowed != null && !allowed.test(at, block))
                return null;
        }
        return CrystalPvp.isActiveFighter(arena, player) ? player : null;
    }
}
