package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.Fight;
import me.zeronull.spawnarena.SpawnArena;
import me.zeronull.spawnarena.Threads;
import org.bukkit.Sound;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.util.Vector;

/**
 * A snowball or a fishing rod's bobber does no damage, and the game skips the knockback and the hurt animation for a hit
 * that does none, so in a fight (Snowball Fight and Fishing Rod above all) they'd just bounce off. When one hits an
 * opponent, they're knocked back the way a punch would, with the hurt animation and sound.
 */
public final class SnowballKnockback implements Listener {
    /** Like an unenchanted punch. */
    private static final double STRENGTH = 0.4;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Snowball ball && ball.getShooter() instanceof Player thrower && e.getEntity() instanceof Player victim)
            knock(thrower, victim, ball.getVelocity());
    }

    /** Snowball Fight's snowballs never run out (the kit's are tagged; anyone else's are used up as normal). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onThrow(final com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent e) {
        if (e.getProjectile() instanceof Snowball && me.zeronull.spawnarena.SlapFish.isKitItem(e.getItemStack()))
            e.setShouldConsume(false);
    }

    /** A bobber hooking a player never counts as a hit at all (no damage event), so it's caught as it lands. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHook(final ProjectileHitEvent e) {
        if (e.getEntity() instanceof FishHook hook && hook.getShooter() instanceof Player thrower && e.getHitEntity() instanceof Player victim)
            knock(thrower, victim, hook.getVelocity());
    }

    /** Knocks {@code victim} back like a punch, if they're fighting {@code thrower}. */
    private static void knock(final Player thrower, final Player victim, final Vector flying) {
        if (SpawnArena.arenas == null || !SpawnArena.arenas.hasActiveFight())
            return;
        final Arena arena = SpawnArena.arenas.of(victim);
        final Fight fight = arena == null ? null : arena.getFight(victim);
        if (fight == null || !fight.areOpponents(thrower, victim))
            return;
        final Vector push = flying.clone().setY(0);
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
