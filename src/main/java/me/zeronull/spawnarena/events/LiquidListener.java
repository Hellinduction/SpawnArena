package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.Fight;
import me.zeronull.spawnarena.FightState;
import me.zeronull.spawnarena.SpawnArena;
import me.zeronull.spawnarena.events.impl.PlayerLiquidEnterEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

public final class LiquidListener implements Listener, Runnable {
    public LiquidListener() {
        me.zeronull.spawnarena.Threads.globalTimer(task -> this.run(), 1L, 3 * 20L);
    }

    /** In water or lava, counting water plants and bubble columns (which only exist in water). */
    public static boolean inLiquid(final org.bukkit.block.Block block) {
        if (block.isLiquid())
            return true;
        switch (block.getType()) {
            case KELP, KELP_PLANT, SEAGRASS, TALL_SEAGRASS, BUBBLE_COLUMN -> { return true; }
            default -> {}
        }
        // Not waterlogged blocks: a waterlogged slab or stair can be part of the floor people stand on
        return false;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerMove(final PlayerMoveEvent e) {
        if (inLiquid(e.getPlayer().getLocation().getBlock()))
            SpawnArena.INSTANCE.getServer().getPluginManager().callEvent(new PlayerLiquidEnterEvent(e.getPlayer()));
    }

    @Override
    public void run() {
        for (final Player p : SpawnArena.INSTANCE.getServer().getOnlinePlayers()) {
            me.zeronull.spawnarena.Threads.entity(p, () -> {
                if (inLiquid(p.getLocation().getBlock()))
                    SpawnArena.INSTANCE.getServer().getPluginManager().callEvent(new PlayerLiquidEnterEvent(p));
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerEnterLiquid(final PlayerLiquidEnterEvent e) {
        final Player player = e.getPlayer();

        if (!SpawnArena.arenas.hasActiveFight()) {
            return;
        }

        if (!SpawnArena.arenas.hasFighter(player)) {
            return;
        }

        final Arena arena = SpawnArena.arenas.of(player);
        final Fight fight = arena.getFight(player);

        if (fight == null) {
            return;
        }

        if (fight.getState() == FightState.COUNTDOWN) {
            return;
        }

        if (!arena.isDeathOnTouchLiquid()) {
            return;
        }

        // Dropped into liquid by a teleport they didn't cause (e.g. anticheat setback): put them back instead
        if (ArenaTeleportGrace.rescue(player))
            return;

        fight.eliminate(player);
    }
}