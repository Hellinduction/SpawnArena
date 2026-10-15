package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.Fight;
import me.zeronull.spawnarena.FightState;
import me.zeronull.spawnarena.SpawnArena;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;

public class ArenaPlayerLeaveAreaEvent implements Listener {
    /** Pearls thrown during a fight (from the fight's inventory), so any other pearl landing is one from before it. */
    private static final java.util.Set<java.util.UUID> FIGHT_PEARLS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onPearlThrown(final org.bukkit.event.entity.ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof org.bukkit.entity.EnderPearl pearl) || !(pearl.getShooter() instanceof Player player))
            return;
        if (fightingIn(player) != null)
            FIGHT_PEARLS.add(pearl.getUniqueId());
    }

    /**
     * A pearl a fighter threw before their fight (a stasis chamber someone pulls, or one still in the air) landing
     * mid-fight: it's stopped as it lands, so there's no teleport at all, and the pearl is handed back after the
     * fight with the rest of their own things (also if they quit, or the server stops). Done here rather than on
     * the teleport, as Folia doesn't fire teleport events.
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPearlLands(final org.bukkit.event.entity.ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof org.bukkit.entity.EnderPearl pearl) || !(pearl.getShooter() instanceof Player player))
            return;
        if (FIGHT_PEARLS.remove(pearl.getUniqueId()))
            return; // thrown in this fight: the teleport rules below deal with it
        final Fight fight = fightingIn(player);
        if (fight == null)
            return;
        e.setCancelled(true);
        final ItemStack item = pearl.getItem().asOne();
        pearl.remove();
        // The player may be far from the pearl (another region on Folia)
        me.zeronull.spawnarena.Threads.entity(player, () -> {
            fight.returnAfterFight(player, item);
            player.sendMessage(org.bukkit.ChatColor.YELLOW + "An ender pearl you threw before the fight landed, so it was stopped. You'll get it back after the fight.");
        });
    }

    /** Their fight, if they're still in it (teleported in, not knocked out). */
    private static Fight fightingIn(final Player player) {
        final Arena arena = SpawnArena.arenas == null ? null : SpawnArena.arenas.of(player);
        final Fight fight = arena == null ? null : arena.getFight(player);
        return fight != null && fight.isFighter(player)
                && (fight.getState() == FightState.PRE_FIGHT_START || fight.getState() == FightState.IN_FIGHT) ? fight : null;
    }

    @EventHandler
    public void onPlayerTeleportOut(PlayerTeleportEvent event) {
        Player player = event.getPlayer();

        if (!SpawnArena.arenas.hasActiveFight()) {
            return;
        }

        if (!SpawnArena.arenas.hasFighter(player)) {
            return;
        }

        final Arena arena = SpawnArena.arenas.of(player);
        final Fight fight = arena.getFight(player);

        if (fight == null)
            return;

        if (fight.getState() == FightState.ENDING || fight.getState() == FightState.COUNTDOWN) {
            return;
        }

        if (fight.isSpawnTeleport(player, event.getTo()))
            return;

        final Material type = event.getTo().clone().add(0, 0.5, 0).getBlock().getType();
        if (event.getCause() == TeleportCause.ENDER_PEARL && type.isSolid()) {
            event.setCancelled(true);
            player.getInventory().addItem(new ItemStack(Material.ENDER_PEARL, 1));
            return;
        }

        final Arena to = SpawnArena.arenas.of(event.getTo());
        if (to != null && to.equals(arena))
            return;

        if (event.getCause() == TeleportCause.COMMAND || event.getCause() == TeleportCause.PLUGIN || event.getCause() == TeleportCause.UNKNOWN) {
            fight.leftArena(player);
            return;
        }

        // Anything else (pearls thrown before the fight that land after it starts, chorus fruit,
        // portals, ...) must not carry a fighter out of the arena
        event.setCancelled(true);
        if (event.getCause() == TeleportCause.ENDER_PEARL)
            player.getInventory().addItem(new ItemStack(Material.ENDER_PEARL, 1));
    }

    @EventHandler
    public void onEntityDamage(final EntityDamageEvent e) {
        if (e.getEntity().getType() != EntityType.PLAYER)
            return;

        final Player p = (Player) e.getEntity();

        if (e.getCause() != EntityDamageEvent.DamageCause.SUFFOCATION)
            return;

        if (!SpawnArena.arenas.hasActiveFight())
            return;

        if (!SpawnArena.arenas.hasFighter(p))
            return;

        final Arena arena = SpawnArena.arenas.of(p);
        final Fight fight = arena.getFight(p);

        if (fight == null)
            return;

        if (fight.getState() == FightState.ENDING || fight.getState() == FightState.COUNTDOWN)
            return;

        final Arena to = SpawnArena.arenas.of(p.getLocation());
        if (to != null && to.equals(arena))
            return;

        fight.teleportToSpawn(p);
    }

}

