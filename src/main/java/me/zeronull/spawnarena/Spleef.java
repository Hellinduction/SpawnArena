package me.zeronull.spawnarena;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Spleef arenas (ArenaConfig.spleef): fighters get a shovel and break the floor under each other, through its layers,
 * into the liquid at the bottom (out, as in sumo: see LiquidListener). Only floor blocks (spleefFloor) break, and only
 * during the match; each one broken gives a snowball, and a snowball breaks the floor where it lands or knocks back the
 * player it hits. No other damage (no melee, no falling between layers). Whoever broke the block under someone, or hit
 * them with a snowball, counts as having touched them, so they "beat" them rather than them going out on their own.
 * The floor is put back after every match by the crystal arenas' snapshot (see CrystalPvp, Fight).
 */
public final class Spleef implements Listener {

    /** The match shovel: breaks a snow floor at once. A match item (see SlapFish): never kept after the match. */
    public static ItemStack shovel() {
        final ItemStack shovel = new ItemStack(Material.DIAMOND_SHOVEL);
        shovel.editMeta(meta -> {
            meta.displayName(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize("<!italic><aqua>Spleef Shovel"));
            meta.setUnbreakable(true);
        });
        return SlapFish.tag(shovel);
    }

    private static ItemStack snowball() {
        return SlapFish.tag(new ItemStack(Material.SNOWBALL));
    }

    /** The spleef match this player is fighting in now (live), or null. */
    public static Fight fightOf(final Player player) {
        if (SpawnArena.arenas == null)
            return null;
        final Arena arena = SpawnArena.arenas.of(player);
        if (arena == null || !arena.isSpleef())
            return null;
        final Fight fight = arena.getFight(player);
        return fight != null && fight.getState() == FightState.IN_FIGHT && fight.isFighter(player) ? fight : null;
    }

    /** Whether this block is floor of that match's arena (inside it, and of a floor type). */
    public static boolean isFloor(final Fight fight, final Block block) {
        final Arena arena = fight.getArena();
        return arena.getSpleefFloor().contains(block.getType()) && arena.equals(SpawnArena.arenas.of(block.getLocation()));
    }

    /**
     * {@code by} broke {@code block}: anyone else in the match standing on it (or about to land on it) counts as touched by
     * them, so it's their doing if they go out.
     */
    private static void undermined(final Fight fight, final Player by, final Block block) {
        for (final Player p : fight.getFighters()) {
            if (p.equals(by) || fight.areTeammates(p, by))
                continue;
            final Location at = p.getLocation();
            if (at.getWorld() != block.getWorld())
                continue;
            final double dx = at.getX() - (block.getX() + 0.5), dz = at.getZ() - (block.getZ() + 0.5);
            final double above = at.getY() - (block.getY() + 1);
            if (Math.abs(dx) <= 0.8 && Math.abs(dz) <= 0.8 && above >= -0.1 && above <= 1.5) {
                fight.stats().damage(by, p, 0, false, false, false);
                fight.onHurt(by, p); // (a free-for-all round credits the knock-out to them)
            }
        }
    }

    // ── Breaking the floor ──────────────────────────────────────────────────

    /** A fighter breaks a block: floor goes (no drop, a snowball instead), nothing else does. After WorldGuard's say. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(final BlockBreakEvent e) {
        final Player p = e.getPlayer();
        final Fight fight = fightOf(p);
        if (fight == null) {
            // Not in a match: a spleef arena's floor is only for its fighters (and a MULTI one's real arena is its viewing
            // arena, never broken)
            final Arena at = SpawnArena.arenas == null ? null : SpawnArena.arenas.of(e.getBlock().getLocation());
            if (at != null && at.isSpleef() && (!at.getFights().isEmpty() || at instanceof CrystalCopy || !CrystalCopies.of(at).isEmpty())
                    && !p.hasPermission("spawnarena.admin"))
                e.setCancelled(true);
            return;
        }
        if (!isFloor(fight, e.getBlock())) {
            e.setCancelled(true);
            return;
        }
        e.setCancelled(false);
        e.setDropItems(false);
        e.setExpToDrop(0);
        p.getInventory().addItem(snowball());
        undermined(fight, p, e.getBlock());
    }

    /** A fighter's snowball: breaks the floor it lands on; one that hits a player knocks them back (see onDamage). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof Snowball) || !(e.getEntity().getShooter() instanceof Player thrower))
            return;
        final Block block = e.getHitBlock();
        if (block == null)
            return;
        final Fight fight = fightOf(thrower);
        if (fight == null || !isFloor(fight, block))
            return;
        undermined(fight, thrower, block);
        block.setType(Material.AIR);
        block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5), org.bukkit.Sound.BLOCK_SNOW_BREAK, 1f, 1f);
    }

    // ── No damage, but snowballs knock back ─────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(final EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player victim))
            return;
        final Fight fight = fightOf(victim);
        if (fight == null)
            return;
        // An opponent's snowball: no damage, but its knockback (and it counts as their doing if they go out)
        if (e instanceof EntityDamageByEntityEvent by && by.getDamager() instanceof Snowball ball
                && ball.getShooter() instanceof Player thrower && fight.isFighter(thrower) && fight.areOpponents(thrower, victim)
                && !thrower.equals(victim)) {
            e.setCancelled(false);
            e.setDamage(0);
            fight.stats().damage(thrower, victim, 0, true, false, false);
            fight.onHurt(thrower, victim);
            return;
        }
        e.setCancelled(true); // melee, falling between layers, anything else
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHunger(final FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && fightOf(p) != null && e.getFoodLevel() < p.getFoodLevel())
            e.setCancelled(true);
    }

    /** The arenas that are spleef arenas (for commands and listings). */
    public static List<Arena> arenas() {
        return SpawnArena.arenas == null ? List.of() : SpawnArena.arenas.toList().stream().filter(Arena::isSpleef).toList();
    }
}
