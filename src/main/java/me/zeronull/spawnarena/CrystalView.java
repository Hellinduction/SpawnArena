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
 *
 * <p>All of it runs on the global thread (the main thread on Paper). On Folia that thread mayn't read blocks, players or
 * entities, so what it needs is read where it's allowed and handed to it: blocks through {@link Blocks} (read on the
 * thread of the region they're in, kept, and read again when used), players captured on their own thread and the
 * matches' entities on their region's thread every update ({@link #captureForNext}), at most a couple of ticks old; and
 * what the events (on the region threads) tell it is passed to the global thread. Block changes go to each viewer on
 * their own thread. It updates every other tick there instead of every tick. On Paper everything is read as it is.
 */
public final class CrystalView implements Listener {
    private static final Map<String, View> VIEWS = new ConcurrentHashMap<>();
    private static long clock;
    /**
     * Viewer -> until when (millis) their game gets the shown blocks again every second rather than every 5: they've
     * been clicking blocks in the view, and the server's answer to a click (the real block) overwrites what they see.
     */
    private static final Map<UUID, Long> CLICKING = new ConcurrentHashMap<>();
    private static final long CLICKING_MILLIS = 5_000;

    /** A fighter's pearl landing in a copy: shown as a small bubble on the viewing arena until {@code until}. */
    private record Landing(CrystalCopy copy, int x, int y, int z, long until) {}
    private static final List<Landing> LANDINGS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final long LANDING_MILLIS = 1_500;
    /** How long a spot put back to the real block is sent again with the regular resend. */
    private static final long PUT_BACK_RESEND_MILLIS = 30_000;

    /** Whether the merged viewing arena runs here: wherever there are copies. */
    public static boolean supported() {
        return CrystalCopies.supported();
    }

    /** Ticks between updates: every tick on Paper, every other on Folia (where reads are handed over, see the class). */
    private static final int EVERY = Threads.FOLIA ? 2 : 1;

    /** Every tick: the players and entities shown follow the real ones; every other tick, the blocks. */
    public static void start() {
        if (!supported())
            return;
        Threads.globalTimer(task -> tick(), 1L, EVERY);
    }

    /** On shutdown: viewers get the real blocks back, and everything shown goes. */
    public static void stop() {
        for (final View view : VIEWS.values())
            view.close();
        VIEWS.clear();
        Blocks.clear();
        SEEN.clear();
        THINGS.clear();
    }

    private static void tick() {
        if (SpawnArena.arenas == null)
            return;
        clock += EVERY; // (in ticks, so what's done every so many ticks is on Folia too)
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
        captureForNext();
    }

    // ── Folia: what the global thread mayn't read, read where it may ───────────

    /** Folia: players near a viewing arena or in a copy, as captured on their own thread for the next update. */
    private static final Map<UUID, CrystalViewPlayers.Seen> SEEN = new ConcurrentHashMap<>();

    /** Folia: the entities in a live copy, as captured on their region's thread (by chunk, or {@link #WHOLE} at once), and when. */
    private record Captured(List<CrystalViewEntities.Thing> things, long at) {}
    private static final Map<CrystalCopy, Map<Long, Captured>> THINGS = new ConcurrentHashMap<>();
    private static final long WHOLE = Long.MIN_VALUE;
    /** Captures older than this are left out (a chunk that unloaded, a copy whose match ended). */
    private static final long CAPTURED_MILLIS = 1_000;

    /** How this player is now: read straight away on Paper; on Folia as last captured (null until they first are). */
    private static CrystalViewPlayers.Seen seen(final Player player) {
        return Threads.FOLIA ? SEEN.get(player.getUniqueId()) : CrystalViewPlayers.Seen.live(player);
    }

    /**
     * Folia: after each update, what the next one needs is captured where it may be read. Players in a copy, or near a
     * viewing arena (who's where is only used here to pick them: their state is read on their own thread), and every
     * live copy's entities.
     */
    private static void captureForNext() {
        if (!Threads.FOLIA)
            return;
        final int range = (SpawnArena.settings == null ? 96 : SpawnArena.settings.crystalViewDistance) + 16;
        for (final Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            final World world = player.getWorld();
            boolean wanted = CrystalCopies.at(world) != null;
            if (!wanted)
                for (final View view : VIEWS.values())
                    if (world.equals(view.world()) && view.real.distanceFrom(player.getLocation()) <= range) {
                        wanted = true;
                        break;
                    }
            if (!wanted) {
                SEEN.remove(player.getUniqueId());
                continue;
            }
            player.getScheduler().run(SpawnArena.INSTANCE, task -> SEEN.put(player.getUniqueId(), CrystalViewPlayers.Seen.capture(player)), null);
        }
        SEEN.keySet().removeIf(id -> org.bukkit.Bukkit.getPlayer(id) == null);
        for (final View view : VIEWS.values())
            for (final CrystalCopy copy : CrystalCopies.of(view.real))
                if (copy.getFights().isEmpty())
                    THINGS.remove(copy);
                else
                    captureThings(copy);
    }

    /**
     * Folia: the copy's entities around its arena (the arena's region and a chunk round it), captured on the thread of
     * the region it's in; chunk by chunk, each on its own region's thread, if it lies across more than one.
     */
    private static void captureThings(final CrystalCopy copy) {
        final com.sk89q.worldedit.math.BlockVector3[] box = arenaBox(copy.real(), 16);
        if (box == null)
            return;
        final World world = copy.world();
        final org.bukkit.util.BoundingBox area = new org.bukkit.util.BoundingBox(box[0].x(), box[0].y(), box[0].z(), box[1].x() + 1, box[1].y() + 1, box[1].z() + 1);
        final int midX = ((box[0].x() + box[1].x()) / 2) >> 4, midZ = ((box[0].z() + box[1].z()) / 2) >> 4;
        org.bukkit.Bukkit.getRegionScheduler().execute(SpawnArena.INSTANCE, world, midX, midZ, () -> {
            final List<CrystalViewEntities.Thing> things = new ArrayList<>();
            try {
                for (final Entity entity : world.getNearbyEntities(area)) {
                    final CrystalViewEntities.Thing thing = CrystalViewEntities.Thing.capture(entity);
                    if (thing != null)
                        things.add(thing);
                }
            } catch (final RuntimeException acrossRegions) {
                for (int cx = box[0].x() >> 4; cx <= box[1].x() >> 4; cx++)
                    for (int cz = box[0].z() >> 4; cz <= box[1].z() >> 4; cz++) {
                        final int x = cx, z = cz;
                        org.bukkit.Bukkit.getRegionScheduler().execute(SpawnArena.INSTANCE, world, x, z, () -> {
                            if (!world.isChunkLoaded(x, z))
                                return;
                            final List<CrystalViewEntities.Thing> inChunk = new ArrayList<>();
                            for (final Entity entity : world.getChunkAt(x, z).getEntities()) {
                                final CrystalViewEntities.Thing thing = CrystalViewEntities.Thing.capture(entity);
                                if (thing != null)
                                    inChunk.add(thing);
                            }
                            final Map<Long, Captured> byChunk = THINGS.computeIfAbsent(copy, c -> new ConcurrentHashMap<>());
                            byChunk.remove(WHOLE);
                            byChunk.put(((long) x << 32) | (z & 0xFFFFFFFFL), new Captured(inChunk, System.currentTimeMillis()));
                        });
                    }
                return;
            }
            final Map<Long, Captured> whole = new ConcurrentHashMap<>();
            whole.put(WHOLE, new Captured(things, System.currentTimeMillis()));
            THINGS.put(copy, whole);
        });
    }

    /** The live copies' entities now: read straight away on Paper, as last captured on Folia. */
    private static List<CrystalViewEntities.Thing> things(final Collection<CrystalCopy> live) {
        if (!Threads.FOLIA)
            return CrystalViewEntities.liveIn(live);
        final long oldest = System.currentTimeMillis() - CAPTURED_MILLIS;
        final List<CrystalViewEntities.Thing> out = new ArrayList<>();
        for (final CrystalCopy copy : live) {
            final Map<Long, Captured> captured = THINGS.get(copy);
            if (captured != null)
                for (final Captured part : captured.values())
                    if (part.at() >= oldest)
                        out.addAll(part.things());
        }
        return out;
    }

    /** The real arena's WorldGuard region, widened by {@code margin} each way (null if it has none). */
    private static com.sk89q.worldedit.math.BlockVector3[] arenaBox(final SavableArena real, final int margin) {
        final World world = real.spawnPoint1 == null ? null : real.spawnPoint1.getWorld();
        if (world == null || Arena.ArenaUtils.REGION_CONTAINER == null)
            return null;
        final com.sk89q.worldguard.protection.managers.RegionManager manager =
                Arena.ArenaUtils.REGION_CONTAINER.get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(world));
        final com.sk89q.worldguard.protection.regions.ProtectedRegion region = manager == null ? null : manager.getRegion(real.getArenaName());
        if (region == null)
            return null;
        return new com.sk89q.worldedit.math.BlockVector3[]{
                region.getMinimumPoint().subtract(margin, margin, margin).withY(Math.max(world.getMinHeight(), region.getMinimumPoint().y() - margin)),
                region.getMaximumPoint().add(margin, margin, margin).withY(Math.min(world.getMaxHeight() - 1, region.getMaximumPoint().y() + margin))};
    }

    /**
     * Block reads for the views. On Paper straight from the world. On Folia the global thread mayn't read blocks: each
     * asked for is read a tick later on the thread of the region it's in and kept; until then (the first time) it's
     * unknown, and whenever a read finds it different from what was kept, the views showing that spot work it out again.
     */
    private static final class Blocks {
        private record Key(UUID world, int x, int y, int z) {}
        private record ChunkKey(UUID world, int x, int z) {}
        private static final Map<Key, BlockData> KEPT = new ConcurrentHashMap<>();
        private static final Map<ChunkKey, Set<Key>> ASKED = new ConcurrentHashMap<>();

        /** The block there: now on Paper; on Folia as last read (null if it never has been yet), and read again. */
        static BlockData get(final World world, final int x, final int y, final int z) {
            if (!Threads.FOLIA)
                return world.getBlockData(x, y, z);
            final Key key = new Key(world.getUID(), x, y, z);
            ASKED.compute(new ChunkKey(world.getUID(), x >> 4, z >> 4), (chunk, keys) -> {
                if (keys == null) {
                    keys = ConcurrentHashMap.newKeySet();
                    org.bukkit.Bukkit.getRegionScheduler().runDelayed(SpawnArena.INSTANCE, world, chunk.x(), chunk.z(), task -> read(world, chunk), 1L);
                }
                keys.add(key);
                return keys;
            });
            return KEPT.get(key);
        }

        /** On the chunk's thread: everything asked for in it read, and the views told what's different. */
        private static void read(final World world, final ChunkKey chunk) {
            final Set<Key> keys = ASKED.remove(chunk); // (nothing more is added to it after this)
            if (keys == null || !world.isChunkLoaded(chunk.x(), chunk.z()))
                return;
            final List<Key> changed = new ArrayList<>();
            for (final Key key : keys) {
                final BlockData now = world.getBlockData(key.x(), key.y(), key.z());
                if (!now.equals(KEPT.put(key, now)))
                    changed.add(key);
            }
            if (!changed.isEmpty())
                Threads.global(() -> {
                    for (final View view : VIEWS.values())
                        view.read(world, changed);
                });
        }

        /** What was kept of this world goes (a copy whose match is over: it's put back). */
        static void forget(final World world) {
            final UUID id = world.getUID();
            KEPT.keySet().removeIf(key -> key.world().equals(id));
        }

        static void clear() {
            KEPT.clear();
            ASKED.clear();
        }
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
        /** Spots that went back to the real block (a match ended, a bubble moved on) -> when; sent again for a while, as one lost packet would leave a viewer seeing the copy's block there for good. */
        private final Map<Long, Long> putBack = new HashMap<>();
        private final Set<UUID> viewers = new HashSet<>();
        /** The same, readable from any thread (the network threads relay sounds to them) */
        private volatile Set<UUID> viewersNow = Set.of();
        /** The fighters, shown to the watchers on spawn */
        private final CrystalViewPlayers fightersShown = new CrystalViewPlayers();
        /**
         * The real arena was changed while no match was fought in it (staff, or something left over), so nothing else
         * puts it back. A match fought in the real arena itself (a tournament's) puts its own changes back as it ends.
         */
        volatile boolean realTouched;
        /** What changed it first since the last putting back (for the log) */
        volatile String touchedBy;
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

        /** Folia: these blocks were read and are different from before: if they're this view's, worked out again. */
        void read(final World world, final List<Blocks.Key> blocks) {
            final World real = this.world();
            final boolean ours = world.equals(real) || CrystalCopies.of(this.real).stream().anyMatch(c -> c.world().equals(world));
            if (ours)
                for (final Blocks.Key key : blocks)
                    this.dirty.add(pack(key.x(), key.y(), key.z()));
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
                    if (Threads.FOLIA)
                        Blocks.forget(layer.getKey().world()); // put back after its match: read afresh next time
                }
            }
            // The real arena was built in or blown up (staff, or something left over): put back as soon as no match is on,
            // in a copy or in the real arena itself (a tournament's, which puts its own blocks back when it ends)
            if (this.realTouched && !this.real.isResetting() && this.real.getFights().isEmpty()
                    && CrystalCopies.of(this.real).stream().allMatch(c -> c.getFights().isEmpty())) {
                this.realTouched = false;
                SpawnArena.INSTANCE.getLogger().info("Putting back the viewing arena of " + this.real.getArenaName() + " (changed by "
                        + (this.touchedBy == null ? "something" : this.touchedBy) + ").");
                this.touchedBy = null;
                this.resetReal(world);
            }
            final List<CrystalViewPlayers.Seen> nowViewing = this.viewersIn(world);
            final List<Player> viewing = new ArrayList<>(nowViewing.size());
            for (final CrystalViewPlayers.Seen viewer : nowViewing)
                viewing.add(viewer.player);
            final List<Fighter> fighters = this.fighters();
            final List<CrystalViewPlayers.Seen> fighting = new ArrayList<>(fighters.size());
            for (final Fighter fighter : fighters)
                fighting.add(fighter.seen);
            this.fightersShown.update(fighting, viewing);
            this.showWatchers(fighters, nowViewing);
            final Set<CrystalCopy> live = new HashSet<>();
            for (final CrystalCopy copy : CrystalCopies.of(this.real))
                if (!copy.getFights().isEmpty())
                    live.add(copy);
            this.entities.tick(things(live), viewing);
            if (clock % 2 != 0)
                return;
            this.routeVoices(fighters, nowViewing);

            // The bubbles: around each fighter, their own copy's blocks
            final Map<Long, CrystalCopy> bubbles = new HashMap<>();
            final Map<Long, Double> closest = new HashMap<>();
            final int radius = Math.max(0, SpawnArena.settings == null ? 3 : SpawnArena.settings.crystalViewBubbleRadius);
            final int below = Math.max(0, SpawnArena.settings == null ? 2 : SpawnArena.settings.crystalViewBubbleBelow);
            final int above = Math.max(0, SpawnArena.settings == null ? 2 : SpawnArena.settings.crystalViewBubbleAbove);
            for (final Fighter fighter : fighters) {
                final Location at = fighter.at;
                final int fx = at.getBlockX(), fy = at.getBlockY(), fz = at.getBlockZ();
                // A little thinner while a fighter of another match is close, so the two don't swallow each other's
                // surroundings (never under 2 around them: they're never seen standing on air)
                int r = radius;
                for (final Fighter other : fighters)
                    if (other.copy != fighter.copy) {
                        final double d = Math.hypot(other.at.getX() - at.getX(), other.at.getZ() - at.getZ());
                        if (d < 2 * radius + 1)
                            r = Math.min(r, Math.max(Math.min(2, radius), (int) Math.floor(d / 2)));
                    }
                final double reach = (r + 0.5) * (r + 0.5);
                for (int dx = -r; dx <= r; dx++)
                    for (int dz = -r; dz <= r; dz++) {
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
            // Where a fighter's pearl just landed: a small bubble of its copy for a moment (the ground it hit)
            final long landingNow = System.currentTimeMillis();
            LANDINGS.removeIf(l -> landingNow > l.until());
            for (final Landing l : LANDINGS)
                if (CrystalCopies.of(this.real).contains(l.copy()))
                    for (int dx = -1; dx <= 1; dx++)
                        for (int dy = -1; dy <= 1; dy++)
                            for (int dz = -1; dz <= 1; dz++)
                                bubbles.putIfAbsent(pack(l.x() + dx, l.y() + dy, l.z() + dz), l.copy());
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
                // (Folia: a block not read yet is left until it has been: the views are told then)
                final BlockData realData = Blocks.get(world, x, y, z);
                if (realData == null)
                    continue;
                BlockData want = null;
                if (from != null) {
                    want = Blocks.get(from.world(), x, y, z);
                    if (want == null)
                        continue;
                }
                if (want != null && want.equals(realData))
                    want = null;
                final BlockData had = this.shown.get(p);
                if (Objects.equals(had, want))
                    continue;
                if (want == null) {
                    this.shown.remove(p);
                    this.putBack.put(p, System.currentTimeMillis());
                    changes.put(p, realData);
                } else {
                    this.shown.put(p, want);
                    this.putBack.remove(p);
                    changes.put(p, want);
                }
            }
            this.dirty.clear();

            // Who's watching: newcomers get everything, those who left get the real blocks back
            final Set<UUID> now = new HashSet<>();
            final boolean resend = clock % 100 == 0; // anything a viewer's game lost (a click on a fake block, a chunk reloaded)
            final long millis = System.currentTimeMillis();
            Map<Long, BlockData> again = this.shown;
            if (resend && !this.putBack.isEmpty()) {
                this.putBack.values().removeIf(when -> millis - when > PUT_BACK_RESEND_MILLIS);
                again = new HashMap<>(this.shown);
                for (final long p : this.putBack.keySet()) {
                    final BlockData real = Blocks.get(world, unpackX(p), unpackY(p), unpackZ(p));
                    if (real != null)
                        again.put(p, real);
                }
            }
            for (final Player viewer : viewing) {
                now.add(viewer.getUniqueId());
                final Long clicking = CLICKING.get(viewer.getUniqueId());
                final boolean clicked = clicking != null && clicking > millis && clock % 20 == 0;
                if (resend)
                    send(viewer, world, again);
                else if (clicked || !this.viewers.contains(viewer.getUniqueId()))
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
            this.viewersNow = Set.copyOf(now);
        }

        private List<CrystalViewPlayers.Seen> viewersIn(final World world) {
            final int range = SpawnArena.settings == null ? 96 : SpawnArena.settings.crystalViewDistance;
            final List<CrystalViewPlayers.Seen> out = new ArrayList<>();
            if (!Threads.FOLIA) {
                for (final Player player : world.getPlayers())
                    if (this.real.distanceFrom(player.getLocation()) <= range)
                        out.add(CrystalViewPlayers.Seen.live(player));
                return out;
            }
            for (final Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
                final CrystalViewPlayers.Seen seen = seen(player);
                if (seen != null && world.equals(seen.world()) && this.real.distanceFrom(seen.at()) <= range)
                    out.add(seen);
            }
            return out;
        }

        /**
         * Each copy with a match on shows the watchers (not anyone in spectator mode or vanished) to its fighters, and
         * only to them: those watching that match up close in the copy see just its own fighters and watchers.
         */
        private void showWatchers(final List<Fighter> fighters, final List<CrystalViewPlayers.Seen> viewers) {
            final Map<CrystalCopy, List<Player>> live = new HashMap<>();
            for (final Fighter fighter : fighters)
                live.computeIfAbsent(fighter.copy, c -> new ArrayList<>()).add(fighter.seen.player);
            final List<CrystalViewPlayers.Seen> watching = new ArrayList<>(viewers.size());
            for (final CrystalViewPlayers.Seen viewer : viewers)
                if (viewer.mode() != GameMode.SPECTATOR && !viewer.vanished())
                    watching.add(viewer);
            live.forEach((copy, itsFighters) ->
                    this.watchersShown.computeIfAbsent(copy, c -> new CrystalViewPlayers()).update(watching, itsFighters));
            this.watchersShown.entrySet().removeIf(e -> {
                if (live.containsKey(e.getKey()))
                    return false;
                e.getValue().close();
                return true;
            });
        }

        /** Who hears whom across the worlds: watchers hear the fighters near them, fighters the watchers near them. */
        private void routeVoices(final List<Fighter> fighters, final List<CrystalViewPlayers.Seen> viewers) {
            if (!voiceOn) {
                this.routes = Map.of();
                return;
            }
            final double range = voiceDistance;
            final Map<UUID, VoiceRoute> routes = new HashMap<>();
            for (final Fighter fighter : fighters) {
                final Location from = fighter.at;
                final List<VoiceRoute.Ear> ears = new ArrayList<>();
                for (final CrystalViewPlayers.Seen viewer : viewers) {
                    final double d = distance(from, viewer.at());
                    if (d <= range)
                        ears.add(new VoiceRoute.Ear(viewer.player.getUniqueId(), d));
                }
                if (!ears.isEmpty())
                    routes.put(fighter.seen.player.getUniqueId(), new VoiceRoute(from.getX(), from.getY(), from.getZ(), ears));
            }
            for (final CrystalViewPlayers.Seen viewer : viewers) {
                if (viewer.mode() == GameMode.SPECTATOR || viewer.vanished())
                    continue;
                final Location from = viewer.at();
                final List<VoiceRoute.Ear> ears = new ArrayList<>();
                for (final Fighter fighter : fighters) {
                    final double d = distance(from, fighter.at);
                    if (d <= range)
                        ears.add(new VoiceRoute.Ear(fighter.seen.player.getUniqueId(), d));
                }
                if (!ears.isEmpty())
                    routes.put(viewer.player.getUniqueId(), new VoiceRoute(from.getX(), from.getY(), from.getZ(), ears));
            }
            this.routes = routes;
        }

        /** A fighter, how they are, where (read once per update), and which copy they're fighting in. */
        private record Fighter(CrystalViewPlayers.Seen seen, Location at, CrystalCopy copy) {}

        /** Everyone still fighting in a copy, there and alive. */
        private List<Fighter> fighters() {
            final List<Fighter> out = new ArrayList<>();
            for (final CrystalCopy copy : CrystalCopies.of(this.real))
                for (final Fight fight : copy.getFights()) {
                    if (fight.getState() != FightState.IN_FIGHT && fight.getState() != FightState.PRE_FIGHT_START
                            && fight.getState() != FightState.ENDING)
                        continue;
                    for (final Player player : fight.getFighters()) {
                        final CrystalViewPlayers.Seen seen = player.isOnline() ? seen(player) : null;
                        if (seen != null && !seen.dead() && seen.mode() != GameMode.SPECTATOR && copy.world().equals(seen.world()))
                            out.add(new Fighter(seen, seen.at(), copy));
                    }
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
            this.putBack.clear();
            this.fightersShown.close();
            this.watchersShown.values().forEach(CrystalViewPlayers::close);
            this.watchersShown.clear();
            this.routes = Map.of();
            this.entities.close();
        }

        /** The real arena's schematic pasted back, and end crystals or lit TNT left in it removed. */
        private void resetReal(final World world) {
            if (!Threads.FOLIA) {
                for (final Entity entity : world.getEntities())
                    if (leftOver(entity))
                        entity.remove();
            } else {
                // Folia: each chunk of the arena on its own region's thread
                final com.sk89q.worldedit.math.BlockVector3[] box = arenaBox(this.real, 0);
                if (box != null)
                    for (int cx = box[0].x() >> 4; cx <= box[1].x() >> 4; cx++)
                        for (int cz = box[0].z() >> 4; cz <= box[1].z() >> 4; cz++) {
                            final int x = cx, z = cz;
                            org.bukkit.Bukkit.getRegionScheduler().execute(SpawnArena.INSTANCE, world, x, z, () -> {
                                if (world.isChunkLoaded(x, z))
                                    for (final Entity entity : world.getChunkAt(x, z).getEntities())
                                        if (leftOver(entity))
                                            entity.remove();
                            });
                        }
            }
            try {
                this.real.resetArena();
            } catch (final RuntimeException e) {
                SpawnArena.INSTANCE.getLogger().warning("Couldn't put back the real arena of " + this.real.getArenaName() + ": " + e.getMessage());
            }
        }

        /** An end crystal, lit TNT or falling block left in the real arena. */
        private boolean leftOver(final Entity entity) {
            return (entity instanceof org.bukkit.entity.EnderCrystal || entity instanceof org.bukkit.entity.TNTPrimed
                    || entity instanceof org.bukkit.entity.FallingBlock) && this.real.equals(SpawnArena.arenas.of(entity.getLocation()));
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
                final float pitch = (float) (0.7 + Math.random() * 0.2);
                Threads.entity(viewer, () -> { // (on their own thread on Folia)
                    viewer.spawnParticle(power >= 2 ? org.bukkit.Particle.EXPLOSION_EMITTER : org.bukkit.Particle.EXPLOSION, at, 1);
                    viewer.playSound(at, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, org.bukkit.SoundCategory.BLOCKS, 4f, pitch);
                });
            }
        }

        /** The shown blocks within {@code r} of this spot, sent to this viewer again (their game lost them to a click). */
        void resendNear(final Player viewer, final int x, final int y, final int z, final int r) {
            final World world = this.world();
            if (world == null || !viewer.isOnline() || !viewer.getWorld().equals(world) || !this.viewers.contains(viewer.getUniqueId()))
                return;
            final Map<Long, BlockData> near = new HashMap<>();
            for (int dx = -r; dx <= r; dx++)
                for (int dy = -r; dy <= r; dy++)
                    for (int dz = -r; dz <= r; dz++) {
                        final long p = pack(x + dx, y + dy, z + dz);
                        final BlockData data = this.shown.get(p);
                        if (data != null)
                            near.put(p, data);
                    }
            send(viewer, world, near);
        }

        /** This viewer's game shows the real blocks again. */
        /**
         * Their game was just sent this chunk (they teleported here, walked up, or it was reloaded): block changes sent
         * before it had it were thrown away, so the shown blocks in it go again, a tick on (after the chunk itself).
         */
        void chunkSent(final Player player, final org.bukkit.Chunk chunk) {
            final World world = this.world();
            if (world == null || this.shown.isEmpty() || !chunk.getWorld().equals(world))
                return;
            final int range = SpawnArena.settings == null ? 96 : SpawnArena.settings.crystalViewDistance;
            if (this.real.distanceFrom(player.getLocation()) > range)
                return; // not watching (they get everything when they come close enough)
            final Map<Long, BlockData> in = new HashMap<>();
            for (final Map.Entry<Long, BlockData> e : this.shown.entrySet())
                if (unpackX(e.getKey()) >> 4 == chunk.getX() && unpackZ(e.getKey()) >> 4 == chunk.getZ())
                    in.put(e.getKey(), e.getValue());
            if (!in.isEmpty())
                Threads.globalLater(() -> {
                    if (player.isOnline() && player.getWorld().equals(world))
                        send(player, world, in);
                }, 1L);
        }

        private void revert(final Player player, final World world) {
            final Map<Long, BlockData> back = new HashMap<>();
            for (final long p : this.shown.keySet()) {
                final BlockData real = Blocks.get(world, unpackX(p), unpackY(p), unpackZ(p));
                if (real != null)
                    back.put(p, real);
            }
            send(player, world, back);
        }
    }

    static boolean isVanished(final Player player) {
        for (final org.bukkit.metadata.MetadataValue value : player.getMetadata("vanished"))
            if (value.asBoolean())
                return true;
        return false;
    }

    /** Same coordinates, different worlds (a copy and the real arena) */
    /** Sounds relayed lately (key -> when), so one heard by both fighters is played to watchers once */
    private static final Map<Long, Long> RELAYED = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A sound a player in a crystal copy was sent (any thread: the network's): the viewing arena's watchers on spawn are
     * sent it too, at the same spot (the copy has the arena at the same coordinates), once however many in the copy
     * heard it: hits, crystals, blocks, eating... (explosions are shown with their particles already, see explosion).
     */
    static void relaySound(final World from, final Object packet, final double x, final double y, final double z, final long seed) {
        final CrystalCopy copy = CrystalCopies.at(from);
        final View view = copy == null ? null : viewOf(copy);
        if (view == null || view.viewersNow.isEmpty())
            return;
        final long key = seed * 31 + ((long) Math.floor(x * 8) * 73856093L ^ (long) Math.floor(y * 8) * 19349663L ^ (long) Math.floor(z * 8) * 83492791L);
        final long now = System.currentTimeMillis();
        if (RELAYED.putIfAbsent(key, now) != null)
            return;
        if (RELAYED.size() > 512)
            RELAYED.values().removeIf(when -> now - when > 1000);
        for (final UUID id : view.viewersNow) {
            final Player viewer = org.bukkit.Bukkit.getPlayer(id);
            if (viewer == null || !viewer.isOnline() || CrystalCopies.at(viewer.getWorld()) != null)
                continue;
            try {
                BotPuppet.send(viewer, packet);
            } catch (final ReflectiveOperationException | RuntimeException ignored) {
                // they've left
            }
        }
    }

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

    /** The blocks shown to this viewer (on their own thread on Folia: still there and in that world by then). */
    private static void send(final Player player, final World world, final Map<Long, BlockData> blocks) {
        if (blocks.isEmpty())
            return;
        final Map<Location, BlockData> out = new HashMap<>(blocks.size() * 2);
        for (final Map.Entry<Long, BlockData> e : blocks.entrySet())
            out.put(new Location(world, unpackX(e.getKey()), unpackY(e.getKey()), unpackZ(e.getKey())), e.getValue());
        if (!Threads.FOLIA) {
            player.sendMultiBlockChange(out);
            return;
        }
        player.getScheduler().run(SpawnArena.INSTANCE, task -> {
            if (player.isOnline() && player.getWorld().equals(world))
                player.sendMultiBlockChange(out);
        }, null);
    }

    // ── Viewers clicking the blocks they're shown ───────────────────────────

    /**
     * A viewer clicked, hit, broke or placed against a block in a MULTI arena's view: whatever the server answers (the
     * real block, the click being refused) overwrites the fake blocks there in their game, so those around it are sent
     * again a tick and three ticks later, and all of them every second for a while after.
     */
    private static void clickedInView(final Player player, final Block block) {
        if (VIEWS.isEmpty() || block == null || CrystalCopies.at(block.getWorld()) != null)
            return;
        for (final View view : VIEWS.values()) {
            final World world = view.world();
            if (world == null || !world.equals(block.getWorld()) || view.real.distanceFrom(block.getLocation()) > 16)
                continue;
            CLICKING.put(player.getUniqueId(), System.currentTimeMillis() + CLICKING_MILLIS);
            final int x = block.getX(), y = block.getY(), z = block.getZ();
            Threads.globalLater(() -> view.resendNear(player, x, y, z, 2), 1L);
            Threads.globalLater(() -> view.resendNear(player, x, y, z, 2), 3L);
            return;
        }
    }

    // Not ignoring cancelled ones: a refused click is exactly the one whose answer overwrites the view
    @EventHandler(priority = EventPriority.MONITOR)
    public void onViewerClick(final org.bukkit.event.player.PlayerInteractEvent e) {
        if (e.getClickedBlock() != null && !SpawnArena.arenas.hasFighter(e.getPlayer()))
            clickedInView(e.getPlayer(), e.getClickedBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onViewerDig(final BlockDamageEvent e) {
        if (!SpawnArena.arenas.hasFighter(e.getPlayer()))
            clickedInView(e.getPlayer(), e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onViewerBreak(final BlockBreakEvent e) {
        if (e.isCancelled() && !SpawnArena.arenas.hasFighter(e.getPlayer()))
            clickedInView(e.getPlayer(), e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onViewerPlace(final BlockPlaceEvent e) {
        if (e.isCancelled() && !SpawnArena.arenas.hasFighter(e.getPlayer()))
            clickedInView(e.getPlayer(), e.getBlockAgainst());
    }

    @EventHandler
    public void onViewerQuit(final org.bukkit.event.player.PlayerQuitEvent e) {
        CLICKING.remove(e.getPlayer().getUniqueId());
    }

    // ── What happens in the copies ──────────────────────────────────────────

    private static void record(final Block block) {
        final CrystalCopy copy = CrystalCopies.at(block.getWorld());
        if (copy == null || copy.getFights().isEmpty())
            return;
        final int x = block.getX(), y = block.getY(), z = block.getZ();
        Threads.global(() -> { // (the views are the global thread's; this is right away on Paper)
            final View view = viewOf(copy);
            if (view != null)
                view.changed(copy, x, y, z);
        });
    }

    /**
     * Someone changed a MULTI crystal arena's real arena itself (staff building in it, an explosion): put back when no
     * match is on. Only changes made by players or explosions: grass spreading or ice melting don't count.
     */
    private static void touchedReal(final Location at) {
        touchedReal(at, null);
    }

    /**
     * As {@link #touchedReal(Location, String)}, by a player: not when they're allowed to build the arena (/arenabuild,
     * or the crystal arena build permission), as that's staff editing it, which stays (copied on the next re-copy).
     */
    private static void touchedReal(final Location at, final Player player, final String by) {
        if (me.zeronull.spawnarena.commands.impl.ArenaBuildCommand.isOn(player)
                || player.hasPermission(me.zeronull.spawnarena.events.CrystalArenaListener.BUILD_BYPASS_PERMISSION))
            return;
        touchedReal(at, player.getName() + " " + by);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPearlLands(final org.bukkit.event.entity.ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof org.bukkit.entity.EnderPearl pearl) || !(pearl.getShooter() instanceof Player))
            return;
        final CrystalCopy copy = CrystalCopies.at(pearl.getWorld());
        if (copy == null || copy.getFights().isEmpty())
            return;
        final Location at = pearl.getLocation();
        LANDINGS.add(new Landing(copy, at.getBlockX(), at.getBlockY(), at.getBlockZ(), System.currentTimeMillis() + LANDING_MILLIS));
    }

    /** As {@link #touchedReal(Location)}, saying what did it (for the log line when it's put back). */
    private static void touchedReal(final Location at, final String by) {
        if (VIEWS.isEmpty() || SpawnArena.arenas == null || CrystalCopies.at(at.getWorld()) != null || !Arena.isArenaWorld(at.getWorld()))
            return;
        final Arena arena = SpawnArena.arenas.of(at);
        if (arena == null)
            return;
        final View view = VIEWS.get(arena.getArenaName().toLowerCase(Locale.ROOT));
        // A match fought in the real arena (a tournament's) changes it on purpose, and puts it back itself as it ends
        if (view != null && !arena.getFights().isEmpty())
            return;
        if (view != null) {
            if (!view.realTouched || view.touchedBy == null)
                view.touchedBy = (by == null ? "a change" : by) + " at " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ();
            view.realTouched = true;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaceEntity(final org.bukkit.event.entity.EntityPlaceEvent e) {
        if (CrystalCopies.at(e.getEntity().getWorld()) == null)
            touchedReal(e.getEntity().getLocation(), (e.getPlayer() == null ? "" : e.getPlayer().getName() + " placing ") + e.getEntity().getType());
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
        touchedReal(e.getBlock().getLocation(), e.getPlayer(), "placing " + e.getBlock().getType());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent e) {
        record(e.getBlock());
        record(e.getBlock().getRelative(org.bukkit.block.BlockFace.UP)); // what stood on it
        touchedReal(e.getBlock().getLocation(), e.getPlayer(), "breaking " + e.getBlock().getType());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(final EntityExplodeEvent e) {
        record(e.blockList());
        explosionAt(e.getLocation(), e.getYield());
        touchedReal(e.getLocation(), e.getEntityType() + " exploding");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent e) {
        record(e.getBlock());
        record(e.blockList());
        explosionAt(e.getBlock().getLocation().add(0.5, 0.5, 0.5), e.getYield());
        touchedReal(e.getBlock().getLocation(), "a block explosion");
    }

    private static void explosionAt(final Location at, final float yield) {
        final CrystalCopy copy = CrystalCopies.at(at.getWorld());
        if (copy == null)
            return;
        final double x = at.getX(), y = at.getY(), z = at.getZ();
        Threads.global(() -> {
            final View view = viewOf(copy);
            if (view != null)
                view.explosion(x, y, z, 2f);
        });
    }

    /** A fighter's totem pops: where they're shown, too. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTotem(final org.bukkit.event.entity.EntityResurrectEvent e) {
        if (e.getEntity() instanceof Player player && !VIEWS.isEmpty())
            Threads.global(() -> {
                for (final View view : VIEWS.values())
                    for (final CrystalViewPlayers shown : view.shownPlayers())
                        shown.totem(player);
            });
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
        touchedReal(e.getBlock().getLocation(), e.getPlayer(), "emptying a bucket");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(final PlayerBucketFillEvent e) {
        record(e.getBlock());
        touchedReal(e.getBlock().getLocation(), e.getPlayer(), "filling a bucket");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(final StructureGrowEvent e) {
        for (final BlockState state : e.getBlocks())
            record(state.getBlock());
    }

    // ── The fakes act like the real players ─────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(final PlayerAnimationEvent e) {
        if (VIEWS.isEmpty())
            return;
        final boolean offHand = e.getAnimationType() == org.bukkit.event.player.PlayerAnimationType.OFF_ARM_SWING;
        final Player player = e.getPlayer();
        Threads.global(() -> {
            for (final View view : VIEWS.values())
                for (final CrystalViewPlayers shown : view.shownPlayers())
                    shown.swing(player, offHand);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(final EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player player) || VIEWS.isEmpty())
            return;
        final float yaw = player.getLocation().getYaw();
        Threads.global(() -> {
            for (final View view : VIEWS.values())
                for (final CrystalViewPlayers shown : view.shownPlayers())
                    shown.hurt(player, yaw);
        });
    }

    /**
     * Someone shown as a fake is about to arrive in a world where it's shown (a fighter back on spawn), or leaving: the
     * fake goes before they move (their game can't have two entities with one UUID, and would refuse the real one).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(final org.bukkit.event.player.PlayerTeleportEvent e) {
        if (e.getTo() != null && !e.getFrom().getWorld().equals(e.getTo().getWorld()))
            forget(e.getPlayer());
    }

    /**
     * Arrived: gone from every fake list, and shown to everyone there afresh, in case the game of someone who saw their
     * fake got the real player before the fake had gone (it then drops the real one).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangeWorld(final org.bukkit.event.player.PlayerChangedWorldEvent e) {
        final Player arrived = e.getPlayer();
        forget(arrived);
        // Only into or out of a MULTI arena's copy: that's where fakes of them are shown
        if (CrystalCopies.at(e.getFrom()) == null && CrystalCopies.at(arrived.getWorld()) == null)
            return;
        // Each of them on their own thread (Folia); only those who could see them are shown them again
        Threads.globalLater(() -> {
            if (!arrived.isOnline())
                return;
            final World there = arrived.getWorld();
            for (final Player other : org.bukkit.Bukkit.getOnlinePlayers())
                if (!other.equals(arrived) && other.getWorld().equals(there))
                    Threads.entity(other, () -> {
                        if (!other.canSee(arrived))
                            return;
                        other.hidePlayer(SpawnArena.INSTANCE, arrived);
                        Threads.entityLater(other, () -> {
                            if (arrived.isOnline())
                                other.showPlayer(SpawnArena.INSTANCE, arrived);
                        }, 2L);
                    });
        }, 5L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final org.bukkit.event.player.PlayerQuitEvent e) {
        forget(e.getPlayer());
    }

    /** A chunk sent to someone's game: the matches' blocks in it go to them again (see View#chunkSent). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkSent(final io.papermc.paper.event.packet.PlayerChunkLoadEvent e) {
        if (VIEWS.isEmpty())
            return;
        final Player player = e.getPlayer();
        final org.bukkit.Chunk chunk = e.getChunk();
        Threads.global(() -> {
            for (final View view : VIEWS.values())
                view.chunkSent(player, chunk);
        });
    }

    private static void forget(final Player player) {
        if (VIEWS.isEmpty())
            return;
        Threads.global(() -> {
            for (final View view : VIEWS.values())
                for (final CrystalViewPlayers shown : view.shownPlayers())
                    shown.forget(player.getUniqueId());
        });
    }

    /**
     * A player is about to be taken into another world by SpawnArena (on Folia, a tick on): their fakes go first, as no
     * teleport event tells this class there (see {@link #onTeleport}).
     */
    public static void beforeWorldChange(final Player player) {
        forget(player);
    }
}
