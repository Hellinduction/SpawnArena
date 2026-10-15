package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.Fight;
import me.zeronull.spawnarena.FightState;
import me.zeronull.spawnarena.SpawnArena;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;

/** Fills in each running fight's FightStats (the hover card on the result message). */
public final class FightStatsListener implements Listener {

    /** A live fight this player is still in, or null. */
    private static Fight fightOf(final Player player) {
        final Arena arena = SpawnArena.arenas == null ? null : SpawnArena.arenas.of(player);
        final Fight fight = arena == null ? null : arena.getFight(player);
        return fight != null && fight.getState() == FightState.IN_FIGHT ? fight : null;
    }

    // MONITOR, after everything has decided. The killing blow is cancelled (it's turned into a loss) and was
    // already counted by ArenaDeathEvent, before the fight ended and the card was sent.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(final EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player victim))
            return;
        final Fight fight = fightOf(victim);
        if (fight != null)
            record(e, fight, victim);
    }

    /** Counts this damage to {@code victim} in the fight's stats, credited to whoever caused it. */
    public static void record(final EntityDamageEvent e, final Fight fight, final Player victim) {
        // Never more than they had: a 70-damage blast on someone with 6 health counts as 6
        final double amount = Math.max(0, Math.min(e.getFinalDamage(), victim.getHealth() + victim.getAbsorptionAmount()));

        Player attacker = null;
        boolean hit = false, crit = false;
        if (e instanceof EntityDamageByEntityEvent byEntity) {
            final Entity damager = byEntity.getDamager();
            if (damager instanceof Player p) {
                attacker = p;
                hit = true;
                crit = byEntity.isCritical();
            } else if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player p) {
                attacker = p;
                hit = true;
            }
        }
        if (attacker == null && (e.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || e.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION)) {
            Location where = e.getDamageSource().getSourceLocation();
            if (where != null && where.getWorld() == null)
                where = new Location(victim.getWorld(), where.getX(), where.getY(), where.getZ());
            attacker = CrystalArenaListener.causeOf(e, where);
        }
        // Only damage from the other side counts as dealt (not your own crystal, not a teammate)
        if (attacker != null && !fight.areOpponents(attacker, victim))
            attacker = null;
        fight.stats().damage(attacker, victim, amount, hit, crit);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEat(final PlayerItemConsumeEvent e) {
        final Material type = e.getItem().getType();
        if (type != Material.GOLDEN_APPLE && type != Material.ENCHANTED_GOLDEN_APPLE)
            return;
        final Fight fight = fightOf(e.getPlayer());
        if (fight != null)
            fight.stats().gappleEaten(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTotem(final EntityResurrectEvent e) {
        if (!(e.getEntity() instanceof Player player))
            return;
        final Fight fight = fightOf(player);
        if (fight != null)
            fight.stats().totemPopped(player);
    }
}
