package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Random sumo's Combo: no hit delay (an opponent can be hit again straight away, so hits chain into combos), and the
 * knockback from each hit is a bit weaker sideways so they stay in reach.
 */
public final class SumoCombo implements Listener {
    /** How much weaker the sideways knockback is. */
    private static final double KNOCKBACK_DIVISOR = 1.2000000476837158;
    /** A knockback this soon after a combo hit is that hit's. */
    private static final long HIT_MILLIS = 400;

    private static final Map<UUID, Long> HIT = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim) || !(e.getDamager() instanceof Player attacker) || !inCombo(attacker, victim))
            return;
        HIT.put(victim.getUniqueId(), System.currentTimeMillis());
        victim.setNoDamageTicks(0);
        // The hit sets them invulnerable again after this event: undone next tick
        Threads.entityLater(victim, () -> victim.setNoDamageTicks(0), 1L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onKnockback(final io.papermc.paper.event.entity.EntityKnockbackEvent e) {
        // A hit can push twice (the hit itself, then the Slap Fish's knockback enchantment): both are made weaker
        final Long hit = e.getEntity() instanceof Player ? HIT.get(e.getEntity().getUniqueId()) : null;
        if (hit == null || System.currentTimeMillis() - hit >= HIT_MILLIS)
            return;
        final Vector knockback = e.getKnockback().clone();
        e.setKnockback(knockback.setX(knockback.getX() / KNOCKBACK_DIVISOR).setZ(knockback.getZ() / KNOCKBACK_DIVISOR));
    }

    @EventHandler
    public void onQuit(final org.bukkit.event.player.PlayerQuitEvent e) {
        HIT.remove(e.getPlayer().getUniqueId());
    }

    /** Whether these two are opponents in a running Combo sumo match. */
    private static boolean inCombo(final Player attacker, final Player victim) {
        if (SpawnArena.arenas == null || !SpawnArena.arenas.hasActiveFight())
            return false;
        final Arena arena = SpawnArena.arenas.of(victim);
        final Fight fight = arena == null ? null : arena.getFight(victim);
        return fight != null && fight.getVariant() == SumoVariant.COMBO && fight.getState() == FightState.IN_FIGHT
                && fight.areOpponents(attacker, victim);
    }
}
