package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.Fight;
import me.zeronull.spawnarena.SpawnArena;
import me.zeronull.spawnarena.Threads;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.Vector;

/**
 * A snowball does no damage, and the game skips the knockback and the hurt animation for a hit that does none, so in a
 * fight (Snowball Fight above all) a snowball would just bounce off. When one hits an opponent, they're knocked back
 * the way a punch would, with the hurt animation and sound.
 */
public final class SnowballKnockback implements Listener {
    /** Like an unenchanted punch. */
    private static final double STRENGTH = 0.4;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Snowball ball) || !(ball.getShooter() instanceof Player thrower)
                || !(e.getEntity() instanceof Player victim) || SpawnArena.arenas == null || !SpawnArena.arenas.hasActiveFight())
            return;
        final Arena arena = SpawnArena.arenas.of(victim);
        final Fight fight = arena == null ? null : arena.getFight(victim);
        if (fight == null || !fight.areOpponents(thrower, victim))
            return;
        final Vector push = ball.getVelocity().setY(0);
        if (push.lengthSquared() < 1.0E-6)
            push.copy(victim.getLocation().toVector().subtract(thrower.getLocation().toVector()).setY(0));
        // Next tick, on their own thread: the hit itself would otherwise reset what's set here
        Threads.entityLater(victim, () -> {
            if (!victim.isOnline() || victim.isDead())
                return;
            // Worked out like vanilla knockback, but set as velocity so it reaches the player's own game
            final Vector away = push.clone().normalize().multiply(STRENGTH);
            final Vector now = victim.getVelocity();
            final Vector velocity = new Vector(now.getX() / 2 + away.getX(),
                    victim.isOnGround() ? Math.min(0.4, now.getY() / 2 + STRENGTH) : now.getY(), now.getZ() / 2 + away.getZ());
            victim.setVelocity(velocity);
            victim.playHurtAnimation(0);
            victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 1f);
        }, 1L);
    }
}
