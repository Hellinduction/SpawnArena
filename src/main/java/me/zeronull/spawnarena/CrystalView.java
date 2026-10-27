package me.zeronull.spawnarena;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.world.StructureGrowEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What players on spawn see of a MULTI crystal arena while its matches are fought in copies (see {@link CrystalCopies}):
 * every match at once, merged into the real arena.
 * <ul>
 *   <li>Each fighter shows where they are in their copy, as a player made of packets alone (see {@link CrystalViewPlayers}).</li>
 *   <li>A block changed in any match shows (sent to the viewers only; the real arena never changes). Where more than one
 *   match changed a spot, the newest change shows.</li>
 *   <li>Around each fighter (crystalViewBubble...) their own match's blocks always show, so they never look like they're
 *   inside a wall or standing on nothing; where two fighters' bubbles meet, each block goes to the closer one. As they
 *   move, the blocks they leave go back to the merged view.</li>
 *   <li>When a match ends its changes are dropped, and those spots show the other matches' changes or the plain arena.</li>
 * </ul>
 * The other way round, fighters see everyone watching in their copy, the same way (nothing there to hit or shoot),
 * and with Simple Voice Chat watchers and fighters hear each other (see {@link #voiceRoute}).
 * Paper only, like the copies themselves.
 */
public final class CrystalView implements Listener {
    private static final Map<String, View> VIEWS = new ConcurrentHashMap<>();
    private static long clock;

    /** Every tick: the players and entities shown follow the real ones; every other tick, the blocks. */
    public static void start() {
        if (!CrystalCopies.supported())
            return;
        Threads.globalTimer(task -> tick(), 1L, 1L);
    }

    /** On shutdown: viewers get the real blocks back, and everything shown goes. */
    public static void stop() {
        for (final View view : VIEWS.values())
            view.close();
        VIEWS.clear();
    }

    private static void tick() {
        if (SpawnArena.arenas == null)
            return;
        clock++;
        final Set<String> active = new HashSet<>();
        for (final Arena arena : SpawnArena.arenas) {
            if (!(arena instanceof SavableArena real) || CrystalCopies.of(real).isEmpty())
                continue;
            final String key = real.getArenaName().toLowerCase(Locale.ROOT);
            active.add(key);
            VIEWS.computeIfAbsent(key, k -> new View(real)).tick();
        }
        VIEWS.entrySet().removeIf(e -> {
            if (active.contains(e.getKey()))
                return false;
            e.getValue().close();
            return true;
        });
    }

    private static View viewOf(final CrystalCopy copy) {
        return VIEWS.get(copy.real().getArenaName().toLowerCase(Locale.ROOT));
    }

    // ── Block positions packed into a long ──────────────────────────────────

    private static long pack(final int x, final int y, final int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static int unpackX(final long p) {
        return (int) (p >> 38);
    }

    private static int unpackY(final long p) {
        return (int) (p << 52 >> 52);
    }

    private static int unpackZ(final long p) {
        return (int) (p << 26 >> 38);
    }

    /** One MULTI crystal arena's merged view. */
    private static final class View {
        private final SavableArena real;
        /** Copy -> the spots its match changed, and when (newest wins) */
        private final Map<CrystalCopy, Map<Long, Long>> layers = new HashMap<>();
        /** Spots to work out again on the next block update */
        private final Set<Long> dirty = new HashSet<>();
        /** Spot -> which copy's block shows there because a fighter's bubble covers it */
        private Map<Long, CrystalCopy> bubbles = new HashMap<>();
        /** What the viewers are being shown instead of the real block */
        private final Map<Long, BlockData> shown = new HashMap<>();
        private final Set<UUID> viewers = new HashSet<>();
        /** The fighters, shown to the watchers on spawn */
        private final CrystalViewPlayers fightersShown = new CrystalViewPlayers();
        /** The real arena was changed (no match is fought in it, so nothing else puts it back) */
        volatile boolean realTouched;
        /** The matches' crystals, projectiles, items... sent to the watchers */
        private final CrystalViewEntities entities = new CrystalViewEntities();
        /** Copy -> the watchers, shown to the players in it */
        private final Map<CrystalCopy, CrystalViewPlayers> watchersShown = new HashMap<>();
        /** Voice: speaker -> where they are and who hears them in another world (read off the voice chat's thread) */
        private volatile Map<UUID, VoiceRoute> routes = Map.of();

        View(final SavableArena real) {
            this.real = real;
        }

        private World world() {
            return this.real.spawnPoint1 == null ? null : this.real.spawnPoint1.getWorld();
        }

        /** A block changed in this copy (or is about to, this tick). */
        void changed(final CrystalCopy copy, final int x, final int y, final int z) {
            final long p = pack(x, y, z);
            this.layers.computeIfAbsent(copy, c -> new HashMap<>()).put(p, clock);
            this.dirty.add(p);
        }

        void tick() {
            final World world = this.world();
            if (world == null)
                return;
            // Matches that are over: their changes go
            for (final Iterator<Map.Entry<CrystalCopy, Map<Long, Long>>> it = this.layers.entrySet().iterator(); it.hasNext(); ) {
                final Map.Entry<CrystalCopy, Map<Long, Long>> layer = it.next();
                if (layer.getKey().getFights().isEmpty() || !CrystalCopies.of(this.real).contains(layer.getKey())) {
                    this.dirty.addAll(layer.getValue().keySet());
                    it.remove();
                }
            }
            // The real arena was built in or blown up (staff, or something left over): put back as soon as no match is on
            if (this.realTouched && !this.real.isResetting()
                    && CrystalCopies.of(this.real).stream().allMatch(c -> c.getFights().isEmpty())) {
                this.realTouched = false;
                this.resetReal(world);
            }
            final List<Player> nowViewing = this.viewersIn(world);
            final List<Fighter> fighters = this.fighters();
            final List<Player> fighting = new ArrayList<>(fighters.size());
            for (final Fighter fighter : fighters)
                fighting.add(fighter.player);
            this.fightersShown.update(fighting, nowViewing);
            this.showWatchers(fighters, nowViewing);
            final Set<CrystalCopy> live = new HashSet<>();
            for (final CrystalCopy copy : CrystalCopies.of(this.real))
                if (!copy.getFights().isEmpty())
                    live.add(copy);
            this.entities.tick(live, nowViewing);
            if (clock % 2 != 0)
                return;
            this.routeVoices(fighters, nowViewing);

            // The bubbles: around each fighter, their own copy's blocks
            final Map<Long, CrystalCopy> bubbles = new HashMap<>();
            final Map<Long, Double> closest = new HashMap<>();
            final int radius = Math.max(0, SpawnArena.settings == null ? 3 : SpawnArena.settings.crystalViewBubbleRadius);
            final int below = Math.max(0, SpawnArena.settings == null ? 2 : SpawnArena.settings.crystalViewBubbleBelow);
            final int above = Math.max(0, SpawnArena.settings == null ? 2 : SpawnArena.settings.crystalViewBubbleAbove);
            final double reach = (radius + 0.5) * (radius + 0.5);
            for (final Fighter fighter : fighters) {
                final Location at = fighter.player.getLocation();
                final int fx = at.getBlockX(), fy = at.getBlockY(), fz = at.getBlockZ();
                for (int dx = -radius; dx <= radius; dx++)
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (dx * dx + dz * dz > reach)
                            continue;
                        for (int y = fy - below; y <= fy + 1 + above; y++) {
                            final long p = pack(fx + dx, y, fz + dz);
                            final double cx = fx + dx + 0.5 - at.getX(), cy = y + 0.5 - at.getY(), cz = fz + dz + 0.5 - at.getZ();
                            final double d = cx * cx + cy * cy + cz * cz;
                            final Double best = closest.get(p);
                            if (best == null || d < best) {
                                closest.put(p, d);
                                bubbles.put(p, fighter.copy);
                            }
                        }
                    }
            }
            this.dirty.addAll(this.bubbles.keySet());
            this.dirty.addAll(bubbles.keySet());
            this.bubbles = bubbles;

            // Work out the dirty spots
            final Map<Long, BlockData> changes = new HashMap<>();
            for (final long p : this.dirty) {
                final int x = unpackX(p), y = unpackY(p), z = unpackZ(p);
                if (y < world.getMinHeight() || y >= world.getMaxHeight())
                    continue;
                CrystalCopy from = bubbles.get(p);
                if (from == null) {
                    long newest = Long.MIN_VALUE;
                    for (final Map.Entry<CrystalCopy, Map<Long, Long>> layer : this.layers.entrySet()) {
                        final Long when = layer.getValue().get(p);
                        if (when != null && when > newest) {
                            newest = when;
                            from = layer.getKey();
                        }
                    }
                }
                final BlockData realData = world.getBlockData(x, y, z);
                BlockData want = from == null ? null : from.world().getBlockData(x, y, z);
                if (want != null && want.equals(realData))
                    want = null;
                final BlockData had = this.shown.get(p);
                if (Objects.equals(had, want))
                    continue;
                if (want == null) {
                    this.shown.remove(p);
                    changes.put(p, realData);
                } else {
                    this.shown.put(p, want);
                    changes.put(p, want);
                }
            }
            this.dirty.clear();

            // Who's watching: newcomers get everything, those who left get the real blocks back
            final Set<UUID> now = new HashSet<>();
            final boolean resend = clock % 100 == 0; // anything a viewer's game lost (a click on a fake block, a chunk reloaded)
            for (final Player viewer : nowViewing) {
                now.add(viewer.getUniqueId());
                if (resend || !this.viewers.contains(viewer.getUniqueId()))
                    send(viewer, world, this.shown);
                else if (!changes.isEmpty())
                    send(viewer, world, changes);
            }
            for (final UUID gone : this.viewers) {
                if (now.contains(gone))
                    continue;
                final Player player = org.bukkit.Bukkit.getPlayer(gone);
                if (player != null && player.getWorld().equals(world))
                    this.revert(player, world);
            }
            this.viewers.clear();
            this.viewers.addAll(now);
        }

        private List<Player> viewersIn(final World world) {
            final int range = SpawnArena.settings == null ? 96 : SpawnArena.settings.crystalViewDistance;
            final List<Player> out = new ArrayList<>();
            for (final Player player : world.getPlayers())
                if (this.real.distanceFrom(player.getLocation()) <= range)
                    out.add(player);
            return out;
        }

        /** Each copy with a match on shows the watchers (not anyone in spectator mode or vanished) to its fighters. */
        private void showWatchers(final List<Fighter> fighters, final List<Player> viewers) {
            final Set<CrystalCopy> live = new HashSet<>();
            for (final Fighter fighter : fighters)
                live.add(fighter.copy);
            final List<Player> watching = new ArrayList<>(viewers.size());
            for (final Player viewer : viewers)
                if (viewer.getGameMode() != GameMode.SPECTATOR && !isVanished(viewer))
                    watching.add(viewer);
            for (final CrystalCopy copy : live)
                this.watchersShown.computeIfAbsent(copy, c -> new CrystalViewPlayers()).update(watching, copy.world().getPlayers());
            this.watchersShown.entrySet().removeIf(e -> {
                if (live.contains(e.getKey()))
                    return false;
                e.getValue().close();
                return true;
            });
        }

        /** Who hears whom across the worlds: watchers hear the fighters near them, fighters the watchers near them. */
        private void routeVoices(final List<Fighter> fighters, final List<Player> viewers) {
            if (!voiceOn) {
                this.routes = Map.of();
                return;
            }
            final double range = voiceDistance;
            final Map<UUID, VoiceRoute> routes = new HashMap<>();
            for (final Fighter fighter : fighters) {
                final Location from = fighter.player.getLocation();
                final List<VoiceRoute.Ear> ears = new ArrayList<>();
                for (final Player viewer : viewers) {
                    final double d = distance(from, viewer.getLocation());
                    if (d <= range)
                        ears.add(new VoiceRoute.Ear(viewer.getUniqueId(), d));
                }
                if (!ears.isEmpty())
                    routes.put(fighter.player.getUniqueId(), new VoiceRoute(from.getX(), from.getY(), from.getZ(), ears));
            }
            for (final Player viewer : viewers) {
                if (viewer.getGameMode() == GameMode.SPECTATOR || isVanished(viewer))
                    continue;
                final Location from = viewer.getLocation();
                final List<VoiceRoute.Ear> ears = new ArrayList<>();
                for (final Fighter fighter : fighters) {
                    final double d = distance(from, fighter.player.getLocation());
                    if (d <= range)
                        ears.add(new VoiceRoute.Ear(fighter.player.getUniqueId(), d));
                }
                if (!ears.isEmpty())
                    routes.put(viewer.getUniqueId(), new VoiceRoute(from.getX(), from.getY(), from.getZ(), ears));
            }
            this.routes = routes;
        }

        private record Fighter(Player player, CrystalCopy copy) {}

        /** Everyone still fighting in a copy, there and alive. */
        private List<Fighter> fighters() {
            final List<Fighter> out = new ArrayList<>();
            for (final CrystalCopy copy : CrystalCopies.of(this.real))
                for (final Fight fight : copy.getFights()) {
                    if (fight.getState() != FightState.IN_FIGHT && fight.getState() != FightState.PRE_FIGHT_START
                            && fight.getState() != FightState.ENDING)
                        continue;
                    for (final Player player : fight.getFighters())
                        if (player.isOnline() && !player.isDead() && player.getGameMode() != GameMode.SPECTATOR
                                && player.getWorld().equals(copy.world()))
                            out.add(new Fighter(player, copy));
                }
            return out;
        }

        /** Every group of fakes this view sends (the fighters on spawn, the watchers in each copy). */
        List<CrystalViewPlayers> shownPlayers() {
            final List<CrystalViewPlayers> out = new ArrayList<>(this.watchersShown.values());
            out.add(this.fightersShown);
            return out;
        }

        private void close() {
            final World world = this.world();
            for (final UUID id : this.viewers) {
                final Player player = org.bukkit.Bukkit.getPlayer(id);
                if (player != null && world != null && player.getWorld().equals(world))
                    this.revert(player, world);
            }
            this.viewers.clear();
            this.shown.clear();
            this.fightersShown.close();
            this.watchersShown.values().forEach(CrystalViewPlayers::close);
            this.watchersShown.clear();
            this.routes = Map.of();
            this.entities.close();
        }

        /** The real arena's schematic pasted back, and end crystals or lit TNT left in it removed. */
        private void resetReal(final World world) {
            for (final Entity entity : world.getEntities())
                if ((entity instanceof org.bukkit.entity.EnderCrystal || entity instanceof org.bukkit.entity.TNTPrimed
                        || entity instanceof org.bukkit.entity.FallingBlock) && this.real.equals(SpawnArena.arenas.of(entity.getLocation())))
                    entity.remove();
            try {
                this.real.resetArena();
            } catch (final RuntimeException e) {
                SpawnArena.INSTANCE.getLogger().warning("Couldn't put back the real arena of " + this.real.getArenaName() + ": " + e.getMessage());
            }
        }

        /** Shows the watchers an explosion that happened at this spot in one of the copies (particles and sound only). */
        void explosion(final double x, final double y, final double z, final float power) {
            final World world = this.world();
            if (world == null)
                return;
            final Location at = new Location(world, x, y, z);
            for (final UUID id : this.viewers) {
                final Player viewer = org.bukkit.Bukkit.getPlayer(id);
                if (viewer == null || !viewer.getWorld().equals(world))
                    continue;
                viewer.spawnParticle(power >= 2 ? org.bukkit.Particle.EXPLOSION_EMITTER : org.bukkit.Particle.EXPLOSION, at, 1);
                viewer.playSound(at, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, org.bukkit.SoundCategory.BLOCKS, 4f,
                        (float) (0.7 + Math.random() * 0.2));
            }
        }

        /** This viewer's game shows the real blocks again. */
        private void revert(final Player player, final World world) {
            final Map<Long, BlockData> back = new HashMap<>();
            for (final long p : this.shown.keySet())
                back.put(p, world.getBlockData(unpackX(p), unpackY(p), unpackZ(p)));
            send(player, world, back);
        }
    }

    private static boolean isVanished(final Player player) {
        for (final org.bukkit.metadata.MetadataValue value : player.getMetadata("vanished"))
            if (value.asBoolean())
                return true;
        return false;
    }

    /** Same coordinates, different worlds (a copy and the real arena) */
    private static double distance(final Location a, final Location b) {
        final double dx = a.getX() - b.getX(), dy = a.getY() - b.getY(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ── Voice ───────────────────────────────────────────────────────────────

    /** Set by the voice chat hook: whether it's there, and its proximity distance. */
    public static volatile boolean voiceOn;
    public static volatile double voiceDistance = 48;

    /** Where a speaker is, and who in another world (watchers / fighters) is close enough to hear them, and how far off. */
    public record VoiceRoute(double x, double y, double z, List<Ear> ears) {
        public record Ear(UUID player, double distance) {}
    }

    /** Who should hear this speaker across worlds (null if nobody); safe from any thread. */
    public static VoiceRoute voiceRoute(final UUID speaker) {
        for (final View view : VIEWS.values()) {
            final VoiceRoute route = view.routes.get(speaker);
            if (route != null)
                return route;
        }
        return null;
    }

    private static void send(final Player player, final World world, final Map<Long, BlockData> blocks) {
        if (blocks.isEmpty())
            return;
        final Map<Location, BlockData> out = new HashMap<>(blocks.size() * 2);
        for (final Map.Entry<Long, BlockData> e : blocks.entrySet())
            out.put(new Location(world, unpackX(e.getKey()), unpackY(e.getKey()), unpackZ(e.getKey())), e.getValue());
        player.sendMultiBlockChange(out);
    }

    // ── What happens in the copies ──────────────────────────────────────────

    private static void record(final Block block) {
        final CrystalCopy copy = CrystalCopies.at(block.getWorld());
        if (copy == null || copy.getFights().isEmpty())
            return;
        final View view = viewOf(copy);
        if (view != null)
            view.changed(copy, block.getX(), block.getY(), block.getZ());
    }

    /**
     * Someone changed a MULTI crystal arena's real arena itself (staff building in it, an explosion): put back when no
     * match is on. Only changes made by players or explosions: grass spreading or ice melting don't count.
     */
    private static void touchedReal(final Location at) {
        if (VIEWS.isEmpty() || SpawnArena.arenas == null || CrystalCopies.at(at.getWorld()) != null || !Arena.isArenaWorld(at.getWorld()))
            return;
        final Arena arena = SpawnArena.arenas.of(at);
        if (arena == null)
            return;
        final View view = VIEWS.get(arena.getArenaName().toLowerCase(Locale.ROOT));
        if (view != null)
            view.realTouched = true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaceEntity(final org.bukkit.event.entity.EntityPlaceEvent e) {
        if (CrystalCopies.at(e.getEntity().getWorld()) == null)
            touchedReal(e.getEntity().getLocation());
    }

    private static void record(final Collection<Block> blocks) {
        for (final Block block : blocks)
            record(block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(final BlockPlaceEvent e) {
        if (e instanceof BlockMultiPlaceEvent multi)
            for (final BlockState state : multi.getReplacedBlockStates())
                record(state.getBlock());
        record(e.getBlock());
        touchedReal(e.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent e) {
        record(e.getBlock());
        record(e.getBlock().getRelative(org.bukkit.block.BlockFace.UP)); // what stood on it
        touchedReal(e.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(final EntityExplodeEvent e) {
        record(e.blockList());
        explosionAt(e.getLocation(), e.getYield());
        touchedReal(e.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent e) {
        record(e.getBlock());
        record(e.blockList());
        explosionAt(e.getBlock().getLocation().add(0.5, 0.5, 0.5), e.getYield());
        touchedReal(e.getBlock().getLocation());
    }

    private static void explosionAt(final Location at, final float yield) {
        final CrystalCopy copy = CrystalCopies.at(at.getWorld());
        final View view = copy == null ? null : viewOf(copy);
        if (view != null)
            view.explosion(at.getX(), at.getY(), at.getZ(), 2f);
    }

    /** A fighter's totem pops: where they're shown, too. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTotem(final org.bukkit.event.entity.EntityResurrectEvent e) {
        if (e.getEntity() instanceof Player player)
            for (final View view : VIEWS.values())
                for (final CrystalViewPlayers shown : view.shownPlayers())
                    shown.totem(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(final BlockFromToEvent e) {
        record(e.getToBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(final BlockFadeEvent e) {
        record(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(final BlockFormEvent e) {
        record(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(final BlockBurnEvent e) {
        record(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(final BlockIgniteEvent e) {
        record(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDestroy(final com.destroystokyo.paper.event.block.BlockDestroyEvent e) {
        record(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFall(final EntityChangeBlockEvent e) {
        record(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonOut(final BlockPistonExtendEvent e) {
        record(e.getBlock().getRelative(e.getDirection()));
        for (final Block block : e.getBlocks()) {
            record(block);
            record(block.getRelative(e.getDirection()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonIn(final BlockPistonRetractEvent e) {
        record(e.getBlock().getRelative(e.getDirection()));
        for (final Block block : e.getBlocks()) {
            record(block);
            record(block.getRelative(e.getDirection()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(final PlayerBucketEmptyEvent e) {
        record(e.getBlock());
        touchedReal(e.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(final PlayerBucketFillEvent e) {
        record(e.getBlock());
        touchedReal(e.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(final StructureGrowEvent e) {
        for (final BlockState state : e.getBlocks())
            record(state.getBlock());
    }

    // ── The fakes act like the real players ─────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(final PlayerAnimationEvent e) {
        final boolean offHand = e.getAnimationType() == org.bukkit.event.player.PlayerAnimationType.OFF_ARM_SWING;
        for (final View view : VIEWS.values())
            for (final CrystalViewPlayers shown : view.shownPlayers())
                shown.swing(e.getPlayer(), offHand);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(final EntityDamageEvent e) {
        if (e.getEntity() instanceof Player player)
            for (final View view : VIEWS.values())
                for (final CrystalViewPlayers shown : view.shownPlayers())
                    shown.hurt(player);
    }

    /**
     * Someone shown as a fake is about to arrive in a world where it's shown (a fighter back on spawn), or leaving:
     * the fake goes first, as their game can't have two of them.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangeWorld(final org.bukkit.event.player.PlayerChangedWorldEvent e) {
        forget(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final org.bukkit.event.player.PlayerQuitEvent e) {
        forget(e.getPlayer());
    }

    private static void forget(final Player player) {
        for (final View view : VIEWS.values())
            for (final CrystalViewPlayers shown : view.shownPlayers())
                shown.forget(player.getUniqueId());
    }
}
