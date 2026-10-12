package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.Fight;
import me.zeronull.spawnarena.SpawnArena;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.projectiles.ProjectileSource;

public final class ArenaPotionSplashEvent implements Listener {

    /** Lingering potion clouds: same rules as splash potions (only this match's fighters; no harmful effects on teammates). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCloudApply(final org.bukkit.event.entity.AreaEffectCloudApplyEvent e) {
        if (!(e.getEntity().getSource() instanceof Player shooter))
            return;
        if (!SpawnArena.arenas.hasActiveFight() || !SpawnArena.arenas.hasFighter(shooter))
            return;

        final Arena arena = SpawnArena.arenas.of(shooter);
        final Fight fight = arena == null ? null : arena.getFight(shooter);
        if (fight == null)
            return;

        final org.bukkit.entity.AreaEffectCloud cloud = e.getEntity();
        boolean harmful = cloud.getCustomEffects().stream()
                .anyMatch(effect -> effect.getType().getCategory() == org.bukkit.potion.PotionEffectTypeCategory.HARMFUL);
        if (!harmful && cloud.getBasePotionType() != null)
            harmful = cloud.getBasePotionType().getPotionEffects().stream()
                    .anyMatch(effect -> effect.getType().getCategory() == org.bukkit.potion.PotionEffectTypeCategory.HARMFUL);

        final boolean harmfulCloud = harmful;
        e.getAffectedEntities().removeIf(entity -> entity instanceof Player player
                && (!fight.isFighter(player) || (harmfulCloud && fight.areTeammates(shooter, player))));
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotionSplash(final PotionSplashEvent e) {
        final ThrownPotion potion = e.getEntity();
        final ProjectileSource shooterEntity = potion.getShooter();

        if (!(shooterEntity instanceof Player))
            return;

        final Player shooter = (Player) shooterEntity;

        if (!SpawnArena.arenas.hasActiveFight())
            return;

        if (!SpawnArena.arenas.hasFighter(shooter))
            return;

        final Arena arena = SpawnArena.arenas.of(shooter);
        final Fight fight = arena.getFight(shooter);

        if (fight == null)
            return;

        final boolean harmful = potion.getEffects().stream()
                .anyMatch(effect -> effect.getType().getCategory() == org.bukkit.potion.PotionEffectTypeCategory.HARMFUL);

        for (final LivingEntity entity : e.getAffectedEntities()) {
            if (!(entity instanceof Player))
                continue;

            final Player player = (Player) entity;

            // Fighters in this match are affected, except by harmful potions from their own teammate
            if (fight.isFighter(player) && !(harmful && fight.areTeammates(shooter, player)))
                continue;

            e.setIntensity(entity, 0D);
        }
    }
}