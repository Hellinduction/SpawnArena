package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.CrystalPvp;
import me.zeronull.spawnarena.CrystalPvp.BlockKey;
import me.zeronull.spawnarena.Fight;
import me.zeronull.spawnarena.FightState;
import me.zeronull.spawnarena.SpawnArena;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
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
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    private final Map<BlockKey, Use> explosiveBlockUses = new java.util.concurrent.ConcurrentHashMap<>();
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
            if (own != null && own.isCrystalPvp() && CrystalPvp.isActiveFighter(own, player))
                e.setCancelled(true);
            return;
        }

        final Fight fight = arena.getFight(player);
        if (!CrystalPvp.isActiveFighter(arena, player)) {
            if (isOutsider(arena, player))
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
            else if (CrystalPvp.isOriginal(arena, state.getBlock()))
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
        if (own != null && own.isCrystalPvp() && CrystalPvp.isActiveFighter(own, player) && CrystalPvp.isOriginal(own, block)) {
            e.setCancelled(true);
            this.tell(player, "You can only break blocks placed during the fight.");
            return;
        }

        if (arena == null)
            return;

        if (!CrystalPvp.isActiveFighter(arena, player)) {
            if (isOutsider(arena, player))
                e.setCancelled(true);
            return;
        }

        if (CrystalPvp.isOriginal(arena, block)) {
            e.setCancelled(true);
            this.tell(player, "You can only break blocks placed during the fight.");
            return;
        }

        e.setCancelled(false);
        e.setExpToDrop(0); // the block itself drops, as a match item (see onItemSpawn)
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

        // Anchors and beds: only ones placed during the fight, and remember who set them off
        if (type == Material.RESPAWN_ANCHOR || Tag.BEDS.isTagged(type)) {
            if (!fighter || CrystalPvp.isOriginal(arena, clicked)) {
                if (fighter || isOutsider(arena, player))
                    e.setCancelled(true);
                return;
            }
            this.explosiveBlockUses.put(BlockKey.of(clicked), new Use(player.getUniqueId(), System.currentTimeMillis()));
            if (Tag.BEDS.isTagged(type))
                this.explosiveBlockUses.put(BlockKey.of(otherBedHalf(clicked)), new Use(player.getUniqueId(), System.currentTimeMillis()));
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
            if (CrystalPvp.isOriginal(own, e.getBlock()))
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
            e.setCancelled(CrystalPvp.isOriginal(own, e.getBlock()));
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
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockExplode(final BlockExplodeEvent e) {
        final List<Block> before = this.explosionBlocks.remove(e);
        if (this.filterExplosion(e.getBlock().getLocation(), before, e.blockList())) {
            // Always let it go off (sound, particles, knockback): the list now only has blocks placed during
            // the fight, so the arena itself is safe even where WorldGuard denies explosions
            e.setCancelled(false);
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
            if (!CrystalPvp.isOriginal(originArena, block))
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
    private Player causeOf(final EntityDamageEvent e, final Location where) {
        final Entity causing = e.getDamageSource().getCausingEntity();
        if (causing != null)
            return playerBehind(causing);

        if (where == null)
            return null;
        final Use use = this.explosiveBlockUses.get(BlockKey.of(where));
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

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpread(final BlockSpreadEvent e) {
        if (this.duringFight(e.getBlock().getLocation()))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChangeBlock(final EntityChangeBlockEvent e) {
        final Arena arena = CrystalPvp.arenaAt(e.getBlock());
        if (arena != null && CrystalPvp.activeFight(arena) != null && CrystalPvp.isOriginal(arena, e.getBlock()))
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

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPistonExtend(final BlockPistonExtendEvent e) {
        if (this.duringFight(e.getBlock().getLocation()))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPistonRetract(final BlockPistonRetractEvent e) {
        if (this.duringFight(e.getBlock().getLocation()))
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

    /**
     * Someone who isn't fighting here and must be kept from changing the arena: everyone while a fight
     * is on, and everyone but staff (to edit the arena) otherwise.
     */
    /** Whether this player is fighting right now in a crystal arena (wherever they're aiming). */
    private static boolean isCrystalFighter(final Player player) {
        final Arena own = SpawnArena.arenas.of(player);
        return own != null && own.isCrystalPvp() && CrystalPvp.isActiveFighter(own, player);
    }

    private static boolean isOutsider(final Arena arena, final Player player) {
        return CrystalPvp.activeFight(arena) != null || !player.hasPermission(BUILD_BYPASS_PERMISSION);
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
        final long now = System.currentTimeMillis();
        this.explosiveBlockUses.values().removeIf(use -> now - use.at() > BLOCK_EXPLOSION_MEMORY_MILLIS);
        this.lastMessage.values().removeIf(at -> now - at > MESSAGE_COOLDOWN_MILLIS);
    }
}
