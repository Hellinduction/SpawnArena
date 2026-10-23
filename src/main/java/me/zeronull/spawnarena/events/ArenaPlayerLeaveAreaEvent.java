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

        // A pearl landing where they wouldn't fit (into a wall, under a low ceiling) or just outside the arena: they
        // go to the nearest spot in the arena where they do fit, close by, instead of the pearl being wasted
        if (event.getCause() == TeleportCause.ENDER_PEARL) {
            final org.bukkit.Location spot = nearestFreeSpot(player, arena, event.getTo());
            if (spot == null) {
                event.setCancelled(true);
                player.getInventory().addItem(new ItemStack(Material.ENDER_PEARL, 1));
            } else if (spot != event.getTo())
                event.setTo(spot);
            return;
        }

        final Arena to = SpawnArena.arenas.of(event.getTo());
        if (to != null && to.equals(arena))
            return;

        if (event.getCause() == TeleportCause.COMMAND || event.getCause() == TeleportCause.PLUGIN || event.getCause() == TeleportCause.UNKNOWN) {
            fight.leftArena(player);
            return;
        }

        // Anything else (chorus fruit, portals, ...) must not carry a fighter out of the arena
        event.setCancelled(true);
    }

    /** How far (in blocks) from where a pearl landed a free spot is looked for. */
    private static final int PEARL_SEARCH = 3;

    /**
     * Where a fighter's pearl puts them: where it landed if they fit there and it's in their arena (head under a
     * crystal arena's ceiling too), else the nearest such spot within {@link #PEARL_SEARCH} blocks, or null.
     */
    private static org.bukkit.Location nearestFreeSpot(final Player player, final Arena arena, final org.bukkit.Location landed) {
        if (fits(player, arena, landed))
            return landed;
        final java.util.List<org.bukkit.Location> spots = new java.util.ArrayList<>();
        for (int dx = -PEARL_SEARCH; dx <= PEARL_SEARCH; dx++)
            for (int dy = -PEARL_SEARCH; dy <= PEARL_SEARCH; dy++)
                for (int dz = -PEARL_SEARCH; dz <= PEARL_SEARCH; dz++) {
                    final org.bukkit.Location spot = new org.bukkit.Location(landed.getWorld(), landed.getBlockX() + dx + 0.5,
                            landed.getBlockY() + dy, landed.getBlockZ() + dz + 0.5, landed.getYaw(), landed.getPitch());
                    if (spot.distanceSquared(landed) <= (PEARL_SEARCH + 0.5) * (PEARL_SEARCH + 0.5))
                        spots.add(spot);
                }
        spots.sort(java.util.Comparator.comparingDouble((org.bukkit.Location l) -> l.distanceSquared(landed)));
        for (final org.bukkit.Location spot : spots)
            if (fits(player, arena, spot))
                return spot;
        return null;
    }

    private static boolean fits(final Player player, final Arena arena, final org.bukkit.Location loc) {
        if (player.collidesAt(loc) || !arena.equals(SpawnArena.arenas.of(loc)))
            return false;
        final Integer top = arena.isCrystalPvp() ? arena.regionTopY() : null;
        return top == null || loc.getY() + player.getHeight() <= top + 1;
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

