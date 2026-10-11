package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.Fight;
import me.zeronull.spawnarena.FightState;
import me.zeronull.spawnarena.SpawnArena;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Replacement for the old BetterTeams TeamDisallowedPvPEvent hook.
 * Cuy/Economy teams expose no API or event (the team code is downloaded at runtime),
 * so instead of asking the team plugin, we re-allow damage between the two opponents
 * of a running fight after every other plugin has had its say.
 */
public final class ArenaTeamPvPEvent implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityDamage(final EntityDamageByEntityEvent e) {
        if (!e.isCancelled()) return;
        if (!(e.getEntity() instanceof Player defender)) return;

        final Player attacker = resolveAttacker(e.getDamager());
        if (attacker == null || attacker.equals(defender)) return;

        if (!SpawnArena.arenas.hasActiveFight()) return;

        final Arena arena = SpawnArena.arenas.of(attacker);
        if (arena == null) return;

        final Fight fight = arena.getFight(attacker);
        if (fight == null || fight.getState() != FightState.IN_FIGHT) return;
        if (!defender.equals(fight.getOtherFighter(attacker))) return;
        if (!arena.equals(SpawnArena.arenas.of(attacker.getLocation()))) return;
        if (!arena.equals(SpawnArena.arenas.of(defender.getLocation()))) return;

        e.setCancelled(false);
    }

    private static Player resolveAttacker(final Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }
}
