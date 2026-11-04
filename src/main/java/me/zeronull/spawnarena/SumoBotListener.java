package me.zeronull.spawnarena;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * Sumo bots: only their opponent can hit them (whatever WorldGuard says about hitting a non-player in the arena),
 * nothing else hurts them, a Superior Gaming Chair bot takes less knockback, and a body left over from before (a crash) is removed
 * as soon as it's loaded.
 */
public final class SumoBotListener implements Listener {

    /** Their opponent's hit counts, after every other plugin has had its say; anyone else's doesn't. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHit(final EntityDamageByEntityEvent e) {
        final SumoBot bot = SumoBot.of(e.getEntity());
        if (bot == null) {
            if (SumoBot.isBot(e.getEntity()))
                e.setCancelled(true);
            return;
        }
        final Entity damager = e.getDamager() instanceof Projectile p && p.getShooter() instanceof Entity shooter ? shooter : e.getDamager();
        final boolean opponent = damager instanceof Player player && player.equals(bot.getTarget())
                && bot.getFight().getState() == FightState.IN_FIGHT && bot.getFight().isFighter(player);
        e.setCancelled(!opponent);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHitLanded(final EntityDamageByEntityEvent e) {
        final SumoBot bot = SumoBot.of(e.getEntity());
        if (bot != null)
            bot.hurt();
    }

    /** Falling, the void, suffocating...: never (going in the water is how it loses, and that's watched for). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onOtherDamage(final EntityDamageEvent e) {
        if (!(e instanceof EntityDamageByEntityEvent) && SumoBot.isBot(e.getEntity()))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onKnockback(final io.papermc.paper.event.entity.EntityKnockbackEvent e) {
        final SumoBot bot = SumoBot.of(e.getEntity());
        if (bot == null)
            return;
        double scale = bot.getLevel().knockbackTaken;
        if (bot.getLevel().combo)
            scale /= 1.2000000476837158; // combo sumo's softer knockback, as players get
        if (scale != 1) {
            final org.bukkit.util.Vector k = e.getKnockback().clone();
            e.setKnockback(k.setX(k.getX() * scale).setZ(k.getZ() * scale));
        }
    }

    @EventHandler
    public void onInteract(final org.bukkit.event.player.PlayerInteractEntityEvent e) {
        if (SumoBot.isBot(e.getRightClicked()))
            e.setCancelled(true);
    }

    /** A hit on the player its opponent sees ({@link BotPuppet}, an entity only their game has) lands on the bot itself. */
    @EventHandler
    public void onHitPuppet(final com.destroystokyo.paper.event.player.PlayerUseUnknownEntityEvent e) {
        if (!e.isAttack())
            return;
        final SumoBot bot = BotPuppet.botOf(e.getEntityId());
        final Player player = e.getPlayer();
        if (bot == null || !player.equals(bot.getTarget()))
            return;
        final org.bukkit.entity.Mannequin body = bot.getBody();
        if (body == null || !body.isValid() || body.getWorld() != player.getWorld())
            return;
        // As far as the game itself lets a hit reach
        final org.bukkit.attribute.AttributeInstance range = player.getAttribute(org.bukkit.attribute.Attribute.ENTITY_INTERACTION_RANGE);
        final double reach = (range == null ? 3 : range.getValue()) + 1;
        final org.bukkit.util.BoundingBox box = body.getBoundingBox();
        final org.bukkit.util.Vector eye = player.getEyeLocation().toVector();
        final org.bukkit.util.Vector near = new org.bukkit.util.Vector(Math.max(box.getMinX(), Math.min(eye.getX(), box.getMaxX())),
                Math.max(box.getMinY(), Math.min(eye.getY(), box.getMaxY())), Math.max(box.getMinZ(), Math.min(eye.getZ(), box.getMaxZ())));
        if (eye.distance(near) <= reach && player.hasLineOfSight(body))
            player.attack(body);
    }

    @EventHandler
    public void onLoad(final org.bukkit.event.world.EntitiesLoadEvent e) {
        for (final Entity entity : e.getEntities())
            if (SumoBot.isLeftover(entity))
                entity.getScheduler().run(SpawnArena.INSTANCE, t -> entity.remove(), null);
    }
}
