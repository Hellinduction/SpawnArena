package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.SpawnArena;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;

public class ArenaDropItemEvent implements Listener {
    /** How close (in blocks) to an open-air arena's region an onlooker's drop counts as into it. */
    private static final double EDGE = 3;

    /**
     * Whether an onlooker dropping something here would drop it into an arena: in or right by any arena's region (a
     * crystal arena's walls count; its spectators' deck, above its barrier ceiling, doesn't).
     */
    private static boolean intoArena(final Player player) {
        final org.bukkit.Location at = player.getLocation();
        final me.zeronull.spawnarena.Arena crystal = me.zeronull.spawnarena.CrystalPvp.arenaNear(at, 3);
        if (crystal != null)
            return crystal.equals(me.zeronull.spawnarena.CrystalPvp.arenaAt(at)) || player.collidesAt(at) || insideWalls(crystal, at);
        for (final me.zeronull.spawnarena.Arena arena : SpawnArena.arenas)
            if (!arena.isCrystalPvp() && arena.distanceFrom(at) <= EDGE)
                return true;
        return false;
    }

    /** Below the top of a crystal arena's region (in its walls or pit, not up on the deck). */
    private static boolean insideWalls(final me.zeronull.spawnarena.Arena arena, final org.bukkit.Location at) {
        final Integer top = arena.regionTopY();
        return top != null && at.getY() < top;
    }

    @EventHandler
    public void onCommandExecution(PlayerDropItemEvent event) {
        Player player = event.getPlayer();

        // Fight just ended but their inventory isn't back yet (a tick on Folia): still their fight inventory
        final boolean awaitingRestore = me.zeronull.spawnarena.Fight.AWAITING_RESTORE.contains(player.getUniqueId());

        // Someone who isn't fighting (standing in a crystal arena's walls, at the edge of an arena...) can't drop their
        // own things into it, unless they're staff with /arenabuild or /arenadrop on
        if (!awaitingRestore && !SpawnArena.arenas.hasFighter(player) && intoArena(player)
                && !me.zeronull.spawnarena.commands.impl.ArenaBuildCommand.isOn(player)
                && !me.zeronull.spawnarena.commands.impl.ArenaDropCommand.mayDropInFight(player)) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "You can't drop items into the arena.");
            return;
        }

        if (!awaitingRestore && !SpawnArena.arenas.hasActiveFight())
            return;

        if (!awaitingRestore && !SpawnArena.arenas.hasFighter(player)) {
            return;
        }

        // Crystal fights: a fighter still in the fight can drop things. They become match items (CrystalArenaListener):
        // only that match's fighters can pick them up, and they're deleted when it ends, when everyone's own
        // inventory is put back anyway. Not while it counts down, ends, or their inventory is being given back
        if (!awaitingRestore) {
            final me.zeronull.spawnarena.Arena arena = SpawnArena.arenas.of(player);
            if (arena != null && arena.isCrystalPvp() && me.zeronull.spawnarena.CrystalPvp.isActiveFighter(arena, player))
                return;
        }

        // Staff with the permission, and only once they've switched it on with /arenadrop
        if (me.zeronull.spawnarena.commands.impl.ArenaDropCommand.mayDropInFight(player)) {
            return;
        }

        event.setCancelled(true);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cYou can't drop items while in the arena!"));
    }
}
