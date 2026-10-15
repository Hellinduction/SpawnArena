package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.Fight;
import me.zeronull.spawnarena.SpawnArena;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

public class ArenaDeathEvent implements Listener {
    public static final Material BYPASS_ITEM = Material.TOTEM_OF_UNDYING;
    /**
     * Safety net: a fighter who really dies (something got past the death check) keeps everything and drops
     * nothing, since their inventory is restored after the fight anyway. They're out straight away, and
     * respawned for them so nobody waits on the death screen; their restore waits until they're alive again
     * (see Fight#restore). Not done on respawn: Folia doesn't fire the respawn event.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRealDeath(final org.bukkit.event.entity.PlayerDeathEvent e) {
        final Player player = e.getPlayer();
        if (!SpawnArena.arenas.hasFighter(player))
            return;
        final Arena arena = SpawnArena.arenas.of(player);
        final Fight fight = arena == null ? null : arena.getFight(player);
        if (fight == null || !fight.isFighter(player))
            return;

        e.setKeepInventory(true);
        e.getDrops().clear();
        e.setKeepLevel(true);
        e.setDroppedExp(0);
        me.zeronull.spawnarena.Threads.entityLater(player, () -> {
            if (player.isOnline() && player.isDead())
                player.spigot().respawn();
        }, 1L);
        fight.eliminate(player);
    }

    // Runs last (HIGHEST, and registered after the listeners that re-allow arena damage) and only for
    // damage that will really happen, so e.g. fall damage WorldGuard cancels can't count as a death
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDeath(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }

        Player defender = (Player) event.getEntity();

        if (!SpawnArena.arenas.hasActiveFight()) {
            return;
        }

        if (!(SpawnArena.arenas.hasFighter(defender))) {
            return;
        }

        final Arena arena = SpawnArena.arenas.of(defender);

        if (!arena.isAllowDamage())
            event.setDamage(0D);

        if (!arena.isAllowPvp())
            event.setCancelled(true);

        if (!((defender.getHealth() - event.getFinalDamage()) <= 0)) {
            return;
        }

        // Let a held totem pop, unless it's damage a totem can't save them from (the void, /kill)
        final boolean totemCanSave = event.getCause() != EntityDamageEvent.DamageCause.VOID
                && event.getCause() != EntityDamageEvent.DamageCause.KILL;
        if (totemCanSave && (defender.getInventory().getItemInOffHand().getType() == BYPASS_ITEM
                || defender.getInventory().getItemInMainHand().getType() == BYPASS_ITEM)) {
            return;
        }

        final Fight fight = arena.getFight(defender);
        // Counted now, though it's cancelled: eliminating them can end the fight and send the stats card
        if (fight != null && fight.getState() == me.zeronull.spawnarena.FightState.IN_FIGHT)
            FightStatsListener.record(event, fight, defender);
        event.setCancelled(true);

        if (fight == null)
            return;

        fight.eliminate(defender);
    }
}
