package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.Fight;
import me.zeronull.spawnarena.FightState;
import me.zeronull.spawnarena.SpawnArena;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Makes Fire Aspect / Flame work between fighters even inside WorldGuard "invincible" regions.
 * WorldGuard cancels the ignition, cancels burn damage, and sets fire ticks to 0 on every damage
 * event for invincible players, so the fire an opponent caused is tracked here and put back.
 */
public final class ArenaFireFix implements Listener {
    /** Fighter UUID -> server tick at which opponent-caused fire should go out. */
    private final Map<UUID, Integer> burnUntil = new HashMap<>();

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCombust(final EntityCombustByEntityEvent e) {
        if (!(e.getEntity() instanceof Player defender))
            return;

        final Player attacker = resolveAttacker(e.getCombuster());
        if (attacker == null || !areOpponentsInRunningFight(attacker, defender))
            return;

        e.setCancelled(false);
        final int ticks = (int) Math.round(((double) e.getDuration()) * 20);
        final int until = Bukkit.getCurrentTick() + ticks;
        burnUntil.merge(defender.getUniqueId(), until, Math::max);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(final EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player defender))
            return;

        final Integer until = burnUntil.get(defender.getUniqueId());
        if (until == null)
            return;

        final int remaining = until - Bukkit.getCurrentTick();
        if (remaining <= 0 || !isInRunningFight(defender)) {
            burnUntil.remove(defender.getUniqueId());
            return;
        }

        // Undo WorldGuard extinguishing the fighter on this damage event
        if (defender.getFireTicks() < remaining)
            defender.setFireTicks(remaining);

        if (e.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK && e.isCancelled())
            e.setCancelled(false);
    }

    private static Player resolveAttacker(final Entity combuster) {
        if (combuster instanceof Player p) return p;
        if (combuster instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }

    private static boolean isInRunningFight(final Player player) {
        if (SpawnArena.arenas == null || !SpawnArena.arenas.hasFighter(player))
            return false;
        final Arena arena = SpawnArena.arenas.of(player);
        final Fight fight = arena == null ? null : arena.getFight(player);
        return fight != null && fight.getState() == FightState.IN_FIGHT
                && arena.isAllowDamage() && arena.isAllowPvp()
                && arena.equals(SpawnArena.arenas.of(player.getLocation()));
    }

    private static boolean areOpponentsInRunningFight(final Player attacker, final Player defender) {
        if (!isInRunningFight(attacker) || !isInRunningFight(defender))
            return false;
        final Fight fight = SpawnArena.arenas.of(attacker).getFight(attacker);
        return defender.equals(fight.getOtherFighter(attacker));
    }
}
