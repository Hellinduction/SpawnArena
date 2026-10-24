package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.*;
import me.zeronull.spawnarena.CrystalPvp.BlockKey;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.StructureGrowEvent;

import java.util.*;

/**
 * The rules for crystal PvP arenas (see {@link CrystalPvp}). WorldGuard can keep denying build, PvP and
 * explosions in the arena's region: this lets the fighters through, and only for blocks placed during
 * the fight. Teammates can't hurt each other with crystals or anchors, and nobody outside the fight
 * can be hurt by them.
 *
 * <p>Must be registered before {@link ArenaDeathEvent}, so damage it re-allows still counts as a death.
 */
public final class CrystalArenaListener implements Listener {
    private static final long BLOCK_EXPLOSION_MEMORY_MILLIS = 2_000;
    private static final long MESSAGE_COOLDOWN_MILLIS = 2_000;

    /** Respawn anchor / bed position -> who last used it, so its explosion counts as theirs. */
    private record Use(UUID player, long at) {}
    private static final Map<BlockKey, Use> explosiveBlockUses = new java.util.concurrent.ConcurrentHashMap<>();
    /** Explosion blocks as they were before other plugins (WorldGuard) filtered them. */
    private final Map<Event, List<Block>> explosionBlocks = java.util.Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<UUID, Long> lastMessage = new java.util.concurrent.ConcurrentHashMap<>();

    public CrystalArenaListener() {
        me.zeronull.spawnarena.Threads.globalTimer(task -> this.forget(), 600L, 600L);
    }

    // ── Building ────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(final BlockPlaceEvent e) {
        final Block block = e.getBlockPlaced();
        final Arena arena = CrystalPvp.arenaAt(block);
        final Player player = e.getPlayer();

        if (arena == null) {
            // A fighter reaching out of a crystal arena still can't build outside it
            final Arena own = SpawnArena.arenas.of(player);
            if (own != null && own.isCrystalPvp() && CrystalPvp.isActiveFighter(own, player)) {
                e.setCancelled(true);
                this.tell(player, "You can only build inside the arena.");
            }
            return;
        }

        final Fight fight = arena.getFight(player);
        if (!CrystalPvp.isActiveFighter(arena, player)) {
            if (isOutsider(arena, player))
                e.setCancelled(true);
            return;
        }

        // A ClickVillagers villager: ArenaVillagerGuard already said no (allowing it here would put it down as a plain head)
        if (ArenaVillagerGuard.placesVillager(e)) {
            e.setCancelled(true);
            return;
        }

        final List<BlockState> replaced = e instanceof BlockMultiPlaceEvent multi
                ? multi.getReplacedBlockStates() : List.of(e.getBlockReplacedState());

        String problem = null;
        if (CrystalPvp.isBanned(block.getType(), block.getWorld()))
            problem = "You can't place that in this arena.";
        for (final BlockState state : replaced) {
            if (problem != null)
                break;
            if (!arena.equals(CrystalPvp.arenaAt(state.getLocation())))
                problem = "You can only build inside the arena.";
            else if (!CrystalPvp.canBuild(arena, state.getBlock(), state.getType()))
                problem = "You can only build in empty space.";
        }

        if (problem != null) {
            e.setCancelled(true);
            this.tell(player, problem);
            return;
        }

        e.setCancelled(false);
        e.setBuild(true);
        for (final BlockState state : replaced)
            CrystalPvp.recordPlaced(fight, state.getBlock(), state.getBlockData());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(final BlockBreakEvent e) {
        final Block block = e.getBlock();
        final Arena arena = CrystalPvp.arenaAt(block);
        final Player player = e.getPlayer();

        // A crystal fighter can't break anything outside their arena's region either (most of the walls are
        // just outside it), whatever WorldGuard would allow them (ops get past WorldGuard)
        final Arena own = SpawnArena.arenas.of(player);
        if (own != null && own.isCrystalPvp() && CrystalPvp.isActiveFighter(own, player) && !CrystalPvp.canBreak(own, block)) {
            e.setCancelled(true);
            this.tell(player, "You can only break blocks placed during the fight, and the floor below the spawns.");
            return;
        }

        if (arena == null)
            return;

        if (!CrystalPvp.isActiveFighter(arena, player)) {
            if (isOutsider(arena, player))
                e.setCancelled(true);
            return;
        }

        if (!CrystalPvp.canBreak(arena, block)) {
            e.setCancelled(true);
            this.tell(player, "You can only break blocks placed during the fight, and the floor below the spawns.");
            return;
        }

        e.setCancelled(false);
        e.setExpToDrop(0); // the block itself drops, as a match item (see onItemSpawn)
    }

    /** A charged anchor blows up when used outside the nether, unless it's being charged further with glowstone. */
    private static boolean anchorWillExplode(final Block anchor, final org.bukkit.inventory.ItemStack inHand) {
        if (!(anchor.getBlockData() instanceof org.bukkit.block.data.type.RespawnAnchor data) || data.getCharges() == 0)
            return false;
        if (anchor.getWorld().getEnvironment() == org.bukkit.World.Environment.NETHER)
            return false;
        return !(inHand != null && inHand.getType() == Material.GLOWSTONE && data.getCharges() < data.getMaximumCharges());
    }

    /** End crystals: WorldGuard checks them when the item is used, before any entity exists. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(final PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null)
            return;
        final Block clicked = e.getClickedBlock();
        final Arena arena = CrystalPvp.arenaAt(clicked);
        if (arena == null)
            return;

        final Player player = e.getPlayer();
        final boolean fighter = CrystalPvp.isActiveFighter(arena, player);
        final Material type = clicked.getType();

        // Anchors and beds: only ones placed during the fight (in the open or down a hole they dug), and remember who
        // set them off
        if (type == Material.RESPAWN_ANCHOR || Tag.BEDS.isTagged(type)) {
            if (!fighter || !CrystalPvp.canBreak(arena, clicked)) {
                if (fighter || isOutsider(arena, player))
                    e.setCancelled(true);
                return;
            }
            explosiveBlockUses.put(BlockKey.of(clicked), new Use(player.getUniqueId(), System.currentTimeMillis()));
            // Fight stats: counted now, as the blast can end the fight before the explode event fires
            if (type == Material.RESPAWN_ANCHOR && anchorWillExplode(clicked, e.getItem())) {
                final Fight fight = arena.getFight(player);
                if (fight != null)
                    fight.stats().anchorSetOff(player);
            }
            if (Tag.BEDS.isTagged(type))
                explosiveBlockUses.put(BlockKey.of(otherBedHalf(clicked)), new Use(player.getUniqueId(), System.currentTimeMillis()));
            e.setUseInteractedBlock(Event.Result.ALLOW);
            e.setUseItemInHand(Event.Result.ALLOW);
            return;
        }

        if (e.getItem() == null || e.getItem().getType() != Material.END_CRYSTAL)
            return;
        if (!fighter) {
            if (isOutsider(arena, player))
                e.setCancelled(true);
            return;
        }
        if ((type == Material.OBSIDIAN || type == Material.BEDROCK)
                && arena.equals(CrystalPvp.arenaAt(clicked.getRelative(org.bukkit.block.BlockFace.UP)))) {
            e.setUseItemInHand(Event.Result.ALLOW);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityPlace(final EntityPlaceEvent e) {
        final Arena arena = CrystalPvp.arenaAt(e.getEntity().getLocation());
        if (arena == null) {
            // Crystal fighters can't place things outside their arena either
            if (e.getPlayer() != null && isCrystalFighter(e.getPlayer()))
                e.setCancelled(true);
            return;
        }

        final Player player = e.getPlayer();
        final boolean fighter = player != null && CrystalPvp.isActiveFighter(arena, player);
        if (!fighter) {
            if (player == null || isOutsider(arena, player))
                e.setCancelled(true);
            return;
        }

        // Only end crystals: boats, minecarts, armor stands etc. could carry items or blocks out
        if (!(e.getEntity() instanceof EnderCrystal)) {
            e.setCancelled(true);
            this.tell(player, "You can't place that in this arena.");
            return;
        }

        e.setCancelled(false);
        CrystalPvp.recordCrystal(arena.getFight(player), e.getEntity());
        arena.getFight(player).stats().crystalPlaced(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHangingPlace(final HangingPlaceEvent e) {
        final Arena arena = CrystalPvp.arenaAt(e.getEntity().getLocation());
        if ((arena != null && (e.getPlayer() == null || CrystalPvp.isActiveFighter(arena, e.getPlayer())
                || isOutsider(arena, e.getPlayer()))) || (e.getPlayer() != null && isCrystalFighter(e.getPlayer())))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBucketEmpty(final PlayerBucketEmptyEvent e) {
        final Player player = e.getPlayer();
        final Arena arena = CrystalPvp.arenaAt(e.getBlock());
        if (isCrystalFighter(player)) {
            // Into a spot that was empty when the fight started: wherever it flows is put back afterwards
            final Arena own = SpawnArena.arenas.of(player);
            if (!CrystalPvp.canBuild(own, e.getBlock()))
                e.setCancelled(true);
            else
                e.setCancelled(false);
            return;
        }
        if (arena != null && isOutsider(arena, player))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBucketFill(final PlayerBucketFillEvent e) {
        final Player player = e.getPlayer();
        final Arena arena = CrystalPvp.arenaAt(e.getBlock());
        if (isCrystalFighter(player)) {
            final Arena own = SpawnArena.arenas.of(player);
            e.setCancelled(!CrystalPvp.canBreak(own, e.getBlock())); // what they poured, in the open or down a hole
            return;
        }
        if (arena != null && isOutsider(arena, player))
            e.setCancelled(true);
    }

    // ── Explosions ──────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST)
    public void snapshotEntityExplosion(final EntityExplodeEvent e) {
        if (CrystalPvp.arenaAt(e.getLocation()) != null)
            this.explosionBlocks.put(e, new ArrayList<>(e.blockList()));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void snapshotBlockExplosion(final BlockExplodeEvent e) {
        if (CrystalPvp.arenaAt(e.getBlock().getLocation()) != null)
            this.explosionBlocks.put(e, new ArrayList<>(e.blockList()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityExplode(final EntityExplodeEvent e) {
        final List<Block> before = this.explosionBlocks.remove(e);
        if (this.filterExplosion(e.getLocation(), before, e.blockList())) {
            // Always let it go off (sound, particles, knockback): the list now only has blocks placed during
            // the fight, so the arena itself is safe even where WorldGuard denies explosions
            e.setCancelled(false);
            e.setYield(0); // what a blast breaks drops nothing (digging still does)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockExplode(final BlockExplodeEvent e) {
        final List<Block> before = this.explosionBlocks.remove(e);
        if (this.filterExplosion(e.getBlock().getLocation(), before, e.blockList())) {
            // Always let it go off (sound, particles, knockback): the list now only has blocks placed during
            // the fight, so the arena itself is safe even where WorldGuard denies explosions
            e.setCancelled(false);
            e.setYield(0); // what a blast breaks drops nothing (digging still does)
        }
    }

    /**
     * Inside a crystal arena only blocks placed during the fight can be blown up; anywhere else near one,
     * the arena's own blocks are protected. Returns true if the explosion started in a crystal arena.
     */
    private boolean filterExplosion(final Location origin, final List<Block> before, final List<Block> blocks) {
        final Arena originArena = CrystalPvp.arenaAt(origin);
        if (originArena == null) {
            if (CrystalPvp.anyNear(origin, 16))
                blocks.removeIf(block -> CrystalPvp.arenaAt(block) != null);
            return false;
        }

        final List<Block> candidates = before != null ? before : new ArrayList<>(blocks);
        blocks.clear();
        for (final Block block : candidates)
            if (CrystalPvp.canBreak(originArena, block)) // what was placed in the fight, and the floor below the spawns
                blocks.add(block);
        return true;
    }

    // ── Damage ──────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(final EntityDamageEvent e) {
        final Entity victim = e.getEntity();

        // Popping a crystal: only fighters, only crystals from this fight
        if (victim instanceof EnderCrystal) {
            final Arena arena = CrystalPvp.arenaAt(victim.getLocation());
            if (arena == null)
                return;
            if (!CrystalPvp.isPlacedCrystal(arena, victim)) {
                if (CrystalPvp.activeFight(arena) != null)
                    e.setCancelled(true);
                return;
            }
            if (e instanceof EntityDamageByEntityEvent byEntity) {
                final Player attacker = playerBehind(byEntity.getDamager());
                if (attacker != null) {
                    e.setCancelled(!CrystalPvp.isActiveFighter(arena, attacker));
                    return;
                }
            }
            e.setCancelled(false); // chain reactions from other explosions in the fight
            return;
        }

        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                && e.getCause() != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION)
            return;

        Location where = e.getDamageSource().getSourceLocation();
        // Block explosions (respawn anchors, beds) report where they went off without a world
        if (where != null && where.getWorld() == null)
            where = new Location(victim.getWorld(), where.getX(), where.getY(), where.getZ());
        if (where == null && e instanceof EntityDamageByEntityEvent byEntity)
            where = byEntity.getDamager().getLocation();
        final Arena arena = CrystalPvp.arenaAt(where);
        if (arena == null)
            return;

        // Nothing outside the fight is hurt: spectators, pets, armor stands, item frames...
        if (!(victim instanceof Player player) || !CrystalPvp.isActiveFighter(arena, player)) {
            e.setCancelled(true);
            return;
        }

        final Fight fight = arena.getFight(player);
        final Player cause = this.causeOf(e, where);
        if (cause != null && fight.areTeammates(cause, player)) {
            e.setCancelled(true);
            return;
        }

        // A fighter caught in the fight's own blast: let it through whatever WorldGuard thinks of PvP
        if (arena.isAllowDamage() && arena.isAllowPvp())
            e.setCancelled(false);
    }

    /** Who set off the explosion: whoever popped the crystal, or whoever last used the anchor/bed. */
    public static Player causeOf(final EntityDamageEvent e, final Location where) {
        final Entity causing = e.getDamageSource().getCausingEntity();
        if (causing != null)
            return playerBehind(causing);

        if (where == null)
            return null;
        final Use use = explosiveBlockUses.get(BlockKey.of(where));
        if (use == null || System.currentTimeMillis() - use.at() > BLOCK_EXPLOSION_MEMORY_MILLIS)
            return null;
        return Bukkit.getPlayer(use.player());
    }

    private static Player playerBehind(final Entity entity) {
        if (entity instanceof Player p) return p;
        if (entity instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHangingBreak(final HangingBreakEvent e) {
        if (e.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION && CrystalPvp.arenaAt(e.getEntity().getLocation()) != null)
            e.setCancelled(true);
    }

    // ── Nothing else changes the arena during a fight ───────────────────────

    /**
     * Blocks broken or blown up during a fight drop as usual, but as match items: only that match's
     * fighters can pick them up, and they're deleted when it ends. Anything else that would appear in the
     * arena during a fight (or while it's being cleaned up) is removed.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(final ItemSpawnEvent e) {
        final Arena arena = CrystalPvp.arenaAt(e.getLocation());
        if (arena == null)
            return;
        final Fight fight = CrystalPvp.activeFight(arena);
        if (fight == null || CrystalPvp.isCleaning(arena)) {
            if (CrystalPvp.isCleaning(arena))
                e.setCancelled(true);
            return;
        }
        if (fight.getState() != FightState.IN_FIGHT) {
            e.setCancelled(true);
            return;
        }
        CrystalPvp.recordItem(fight, e.getEntity());
    }

    /** Staff allowed to drop items mid-fight (spawnarena.allowdropinfight): those become match items too. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(final org.bukkit.event.player.PlayerDropItemEvent e) {
        final Arena arena = SpawnArena.arenas.of(e.getPlayer());
        if (arena != null && arena.isCrystalPvp() && CrystalPvp.isActiveFighter(arena, e.getPlayer()))
            CrystalPvp.recordItem(arena.getFight(e.getPlayer()), e.getItemDrop());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(final org.bukkit.event.entity.EntityPickupItemEvent e) {
        final String arenaName = CrystalPvp.matchOf(e.getItem());
        if (arenaName == null)
            return;
        final Arena arena = SpawnArena.arenas.of(arenaName);
        if (!(e.getEntity() instanceof Player player) || arena == null
                || !CrystalPvp.isLiveMatchItem(e.getItem()) || !CrystalPvp.isActiveFighter(arena, player))
            e.setCancelled(true);
    }

    /** Hoppers and hopper minecarts can't take match items either. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onHopperPickup(final org.bukkit.event.inventory.InventoryPickupItemEvent e) {
        if (CrystalPvp.matchOf(e.getItem()) != null)
            e.setCancelled(true);
    }

    /** Match items never merge with ordinary items (or another match's). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onMerge(final org.bukkit.event.entity.ItemMergeEvent e) {
        final String a = CrystalPvp.matchOf(e.getEntity()), b = CrystalPvp.matchOf(e.getTarget());
        if (!java.util.Objects.equals(a, b))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onIgnite(final BlockIgniteEvent e) {
        if (this.duringFight(e.getBlock().getLocation()))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBurn(final BlockBurnEvent e) {
        if (CrystalPvp.arenaAt(e.getBlock()) != null)
            e.setCancelled(true);
    }

    /** Water and lava poured in a fight stay in spots that were empty when it started (those are put back). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlow(final org.bukkit.event.block.BlockFromToEvent e) {
        final Arena arena = CrystalPvp.arenaAt(e.getBlock());
        if (arena != null && CrystalPvp.activeFight(arena) != null && !CrystalPvp.canBuild(arena, e.getToBlock()))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpread(final BlockSpreadEvent e) {
        // Grass (and mycelium) growing back over dirt is fine: the arena is put back after the fight anyway
        final Material grows = e.getNewState().getType();
        if (grows == Material.GRASS_BLOCK || grows == Material.MYCELIUM)
            return;
        if (this.duringFight(e.getBlock().getLocation()))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChangeBlock(final EntityChangeBlockEvent e) {
        final Arena arena = CrystalPvp.arenaAt(e.getBlock());
        if (arena != null && CrystalPvp.activeFight(arena) != null && CrystalPvp.isOriginal(arena, e.getBlock())) {
            e.setCancelled(true);
            return;
        }
        // A crystal fighter's arrows and tridents can't break things outside their arena either (pots, chorus...)
        if (arena == null && e.getEntity() instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof Player shooter && isCrystalFighter(shooter))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFertilize(final BlockFertilizeEvent e) {
        if (this.duringFight(e.getBlock().getLocation()))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGrow(final StructureGrowEvent e) {
        if (this.duringFight(e.getLocation()))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEditPlace(final BlockPlaceEvent e) {
        this.forgetIfEdited(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEditBreak(final BlockBreakEvent e) {
        this.forgetIfEdited(e.getBlock());
    }

    private void forgetIfEdited(final Block block) {
        final Arena arena = CrystalPvp.arenaAt(block);
        if (arena != null && CrystalPvp.activeFight(arena) == null)
            CrystalPvp.forgetSnapshot(arena);
    }

    /** No pistons in the arena, and none outside it moving the arena's blocks (or anything into it). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPistonExtend(final BlockPistonExtendEvent e) {
        if (this.pistonTouchesFight(e.getBlock(), e.getBlocks(), e.getDirection()))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPistonRetract(final BlockPistonRetractEvent e) {
        if (this.pistonTouchesFight(e.getBlock(), e.getBlocks(), e.getDirection()))
            e.setCancelled(true);
    }

    private boolean pistonTouchesFight(final Block piston, final List<Block> moved, final org.bukkit.block.BlockFace direction) {
        if (this.duringFight(piston.getLocation()) || this.duringFight(piston.getRelative(direction).getLocation()))
            return true;
        for (final Block block : moved)
            if (this.duringFight(block.getLocation()) || this.duringFight(block.getRelative(direction).getLocation()))
                return true;
        return false;
    }

    /** Nothing dispenses in an arena during a fight (TNT, armor stands, boats...). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDispense(final org.bukkit.event.block.BlockDispenseEvent e) {
        if (this.duringFight(e.getBlock().getLocation()))
            e.setCancelled(true);
    }

    /** A sponge placed in a fight only soaks up water that wasn't there when it started (that's put back anyway). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSponge(final org.bukkit.event.block.SpongeAbsorbEvent e) {
        final Arena arena = CrystalPvp.arenaAt(e.getBlock());
        if (arena == null || CrystalPvp.activeFight(arena) == null)
            return;
        e.getBlocks().removeIf(state -> CrystalPvp.isOriginal(arena, state.getBlock()));
    }

    /** TNT blocks lit in a fight (where the block was, and when), so the primed TNT that follows can appear. */
    private final Map<BlockKey, Long> litTnt = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * TNT a fighter placed during the fight can be lit, any way at all (flint and steel, fire, redstone, another
     * blast); its blast breaks only what a fighter could. The arena's own TNT can't be.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTntPrime(final org.bukkit.event.block.TNTPrimeEvent e) {
        final Block block = e.getBlock();
        if (!this.duringFight(block.getLocation()))
            return;
        final Arena arena = CrystalPvp.arenaAt(block);
        if (arena == null || !CrystalPvp.canBreak(arena, block)) { // TNT they placed, in the open or down a hole
            e.setCancelled(true);
            return;
        }
        litTnt.put(BlockKey.of(block), System.currentTimeMillis());
    }

    /** Reach of a crystal placed by hand: a crystal that appears further than this from every fighter wasn't theirs. */
    private static final double CRYSTAL_REACH = 6.5;
    /** A right-click with a crystal this recent can be what made one appear. */
    private static final long CRYSTAL_USE_MILLIS = 1_500;

    /** Each crystal fighter's last right-click with an end crystal: when, and where they were. */
    private record CrystalUse(long at, Location where) {}
    private static final Map<java.util.UUID, CrystalUse> crystalUses = new java.util.concurrent.ConcurrentHashMap<>();

    private static void noteCrystalUse(final Player player, final org.bukkit.inventory.ItemStack item) {
        if (item == null || item.getType() != Material.END_CRYSTAL || !isCrystalFighter(player))
            return;
        crystalUses.put(player.getUniqueId(), new CrystalUse(System.currentTimeMillis(), player.getLocation()));
    }

    // Whatever the click was on (a block, the air, or a crystal that's just been popped), and even if something
    // else cancelled it: a plugin may have placed the crystal itself
    @EventHandler(priority = EventPriority.MONITOR)
    public void noteCrystalClick(final PlayerInteractEvent e) {
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK || e.getAction() == Action.RIGHT_CLICK_AIR)
            noteCrystalUse(e.getPlayer(), e.getItem());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void noteCrystalClickOnEntity(final org.bukkit.event.player.PlayerInteractEntityEvent e) {
        noteCrystalUse(e.getPlayer(), e.getPlayer().getInventory().getItem(e.getHand()));
    }

    @EventHandler
    public void forgetCrystalUses(final org.bukkit.event.player.PlayerQuitEvent e) {
        crystalUses.remove(e.getPlayer().getUniqueId());
    }

    /**
     * A crystal that appears mid-fight without a place event: plugins like FasterCrystals spawn crystals themselves
     * (placing one onto a spot where one was just popped). It's counted as placed by the fighter who just right-clicked
     * with a crystal within reach of it (the latest), or else by the nearest fighter within reach; otherwise it would be
     * part of the arena and could never be popped. With no fighter in reach it isn't allowed.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrystalAppear(final org.bukkit.event.entity.EntitySpawnEvent e) {
        if (!(e.getEntity() instanceof EnderCrystal crystal))
            return;
        final Arena arena = CrystalPvp.arenaAt(e.getLocation());
        final Fight fight = arena == null ? null : CrystalPvp.activeFight(arena);
        if (fight == null || CrystalPvp.isPlacedCrystal(arena, crystal))
            return;
        final long now = System.currentTimeMillis();
        final double reach = CRYSTAL_REACH * CRYSTAL_REACH;
        Player clicked = null, nearest = null;
        long latest = 0;
        double best = reach;
        for (final Player p : fight.getFighters()) {
            final Location at = p.getLocation(); // a read of where they are; fine from any thread
            if (at.getWorld() == null || !at.getWorld().equals(e.getLocation().getWorld()))
                continue;
            final double d = at.distanceSquared(e.getLocation());
            if (d > reach)
                continue;
            if (d <= best) {
                best = d;
                nearest = p;
            }
            final CrystalUse use = crystalUses.get(p.getUniqueId());
            if (use != null && now - use.at() <= CRYSTAL_USE_MILLIS && use.at() > latest
                    && use.where().getWorld() == e.getLocation().getWorld() && use.where().distanceSquared(e.getLocation()) <= reach) {
                latest = use.at();
                clicked = p;
            }
        }
        final Player placer = clicked != null ? clicked : nearest;
        if (placer == null) {
            e.setCancelled(true);
            return;
        }
        CrystalPvp.recordCrystal(fight, crystal);
        fight.stats().crystalPlaced(placer);
    }

    /** Primed TNT in an arena during a fight: only from a TNT block placed in it that was just lit (see above). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntitySpawn(final org.bukkit.event.entity.EntitySpawnEvent e) {
        if (!(e.getEntity() instanceof org.bukkit.entity.TNTPrimed) || !this.duringFight(e.getLocation()))
            return;
        final Long lit = litTnt.remove(BlockKey.of(e.getLocation().getBlock()));
        if (lit == null || System.currentTimeMillis() - lit > 2_000)
            e.setCancelled(true); // summoned, from a TNT minecart, a dispenser...
    }

    /** The arena's own item frames, paintings and armor stands stay as they are during a fight. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHangingHit(final org.bukkit.event.hanging.HangingBreakByEntityEvent e) {
        if (this.duringFight(e.getEntity().getLocation()))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityUse(final org.bukkit.event.player.PlayerInteractEntityEvent e) {
        final org.bukkit.entity.Entity clicked = e.getRightClicked();
        if ((clicked instanceof org.bukkit.entity.Hanging || clicked instanceof org.bukkit.entity.ArmorStand)
                && this.duringFight(clicked.getLocation()))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onArmorStand(final org.bukkit.event.player.PlayerArmorStandManipulateEvent e) {
        if (this.duringFight(e.getRightClicked().getLocation()))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFrameOrStandHit(final EntityDamageByEntityEvent e) {
        final org.bukkit.entity.Entity hit = e.getEntity();
        if ((hit instanceof org.bukkit.entity.ItemFrame || hit instanceof org.bukkit.entity.ArmorStand)
                && this.duringFight(hit.getLocation()))
            e.setCancelled(true);
    }

    /** The arena's own signs can't be rewritten mid-fight (their text isn't part of what's put back). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSign(final org.bukkit.event.block.SignChangeEvent e) {
        final Arena arena = CrystalPvp.arenaAt(e.getBlock());
        if (arena != null && CrystalPvp.activeFight(arena) != null && CrystalPvp.isOriginal(arena, e.getBlock()))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCreatureSpawn(final CreatureSpawnEvent e) {
        if (this.duringFight(e.getLocation()))
            e.setCancelled(true);
    }

    @EventHandler
    public void onEntitiesLoad(final EntitiesLoadEvent e) {
        CrystalPvp.onEntitiesLoad(e.getEntities());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    public static final String BUILD_BYPASS_PERMISSION = "spawnarena.crystalarena.build";

    /** Whether this player is fighting right now in a crystal arena (wherever they're aiming). */
    private static boolean isCrystalFighter(final Player player) {
        final Arena own = SpawnArena.arenas.of(player);
        return own != null && own.isCrystalPvp() && CrystalPvp.isActiveFighter(own, player);
    }

    /**
     * Someone who isn't fighting here and must be kept from changing the arena: everyone while a fight
     * is on, and everyone but staff (to edit the arena) otherwise.
     */
    private static boolean isOutsider(final Arena arena, final Player player) {
        if (me.zeronull.spawnarena.commands.impl.ArenaBuildCommand.isOn(player))
            return false;
        return CrystalPvp.activeFight(arena) != null || !player.hasPermission(BUILD_BYPASS_PERMISSION);
    }

    /**
     * Someone who isn't fighting there pearling into a crystal arena, or into its walls (it would leave them stuck in
     * them): the pearl just stops. Done as it lands, as Folia doesn't fire teleport events.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onOutsiderPearl(final org.bukkit.event.entity.ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof org.bukkit.entity.EnderPearl pearl) || !(pearl.getShooter() instanceof Player player))
            return;
        final Location lands = pearl.getLocation();
        final Arena arena = CrystalPvp.arenaNear(lands, 3);
        if (arena == null || CrystalPvp.isActiveFighter(arena, player) || me.zeronull.spawnarena.commands.impl.ArenaBuildCommand.isOn(player))
            return;
        if (!arena.equals(CrystalPvp.arenaAt(lands)) && !player.collidesAt(lands))
            return; // around it, somewhere they fit (on the spectators' deck...)
        e.setCancelled(true);
        pearl.remove();
        me.zeronull.spawnarena.Threads.entity(player, () -> this.tell(player, "You can't pearl into the arena."));
    }

    private boolean duringFight(final Location loc) {
        final Arena arena = CrystalPvp.arenaAt(loc);
        return arena != null && (CrystalPvp.activeFight(arena) != null || CrystalPvp.isCleaning(arena));
    }

    private static Block otherBedHalf(final Block bed) {
        if (!(bed.getBlockData() instanceof org.bukkit.block.data.type.Bed data))
            return bed;
        return data.getPart() == org.bukkit.block.data.type.Bed.Part.FOOT
                ? bed.getRelative(data.getFacing()) : bed.getRelative(data.getFacing().getOppositeFace());
    }

    private void tell(final Player player, final String message) {
        final long now = System.currentTimeMillis();
        final Long last = this.lastMessage.get(player.getUniqueId());
        if (last != null && now - last < MESSAGE_COOLDOWN_MILLIS)
            return;
        this.lastMessage.put(player.getUniqueId(), now);
        player.sendMessage(ChatColor.RED + message);
    }

    /** Drops old anchor/bed uses and message cooldowns. */
    private void forget() {
        final long tntCutoff = System.currentTimeMillis() - 2_000;
        litTnt.values().removeIf(at -> at < tntCutoff);
        final long now = System.currentTimeMillis();
        explosiveBlockUses.values().removeIf(use -> now - use.at() > BLOCK_EXPLOSION_MEMORY_MILLIS);
        this.lastMessage.values().removeIf(at -> now - at > MESSAGE_COOLDOWN_MILLIS);
    }
}
