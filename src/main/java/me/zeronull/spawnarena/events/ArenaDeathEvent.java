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
    /** Fighters who really died (something got past the death check), eliminated once they respawn */
    private final java.util.Set<java.util.UUID> diedInFight = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Safety net: a fighter who really dies keeps everything and drops nothing. Their inventory is
     * restored after the fight anyway, so dropped items would be duplicates.
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
        this.diedInFight.add(player.getUniqueId());
    }

    @EventHandler
    public void onRespawn(final org.bukkit.event.player.PlayerRespawnEvent e) {
        final Player player = e.getPlayer();
        if (!this.diedInFight.remove(player.getUniqueId()))
            return;
        // A tick later, once they're alive again, so restoring them works normally
        me.zeronull.spawnarena.Threads.entityLater(player, () -> {
            final Arena arena = SpawnArena.arenas.of(player);
            final Fight fight = arena == null ? null : arena.getFight(player);
            if (fight != null && fight.isFighter(player))
                fight.eliminate(player);
        }, 1L);
    }

    @EventHandler
    public void onQuit(final org.bukkit.event.player.PlayerQuitEvent e) {
        this.diedInFight.remove(e.getPlayer().getUniqueId());
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

        event.setCancelled(true);

        final Fight fight = arena.getFight(defender);

        if (fight == null)
            return;

        fight.eliminate(defender);
    }
}
