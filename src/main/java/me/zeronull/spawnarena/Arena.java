package me.zeronull.spawnarena;

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;

import java.util.ArrayList;
import java.util.List;

public abstract class Arena extends ArenaOptions {
    /** Loaded from settings.yml on enable. */
    public static volatile String SPAWN_WORLD = "Spawn";
    public ArenaQueue queue;
    /** True while a crystal arena is being pasted back after a fight: no fight starts in it until that's done. */
    private volatile boolean resetting;

    public boolean isResetting() {
        return this.resetting;
    }

    public void setResetting(final boolean resetting) {
        this.resetting = resetting;
    }
    protected String arenaName;
    // Changed by reloads and setup commands while every player's thread (on Folia) reads them
    protected volatile Location spawnPoint1;
    protected volatile Location spawnPoint2;
    protected volatile Location exitLocation;
    /**
     * Whether a non-fighter at {@code loc} counts as being in this arena: inside its region, unless
     * they're standing in a non-full block such as a slab (e.g. on top of the walls, which the region covers).
     */
    public boolean isInFightingSpace(final Location loc) {
        return this.equals(SpawnArena.arenas.of(loc)) && !isInPartialBlock(loc);
    }

    private static boolean isInPartialBlock(final Location loc) {
        final org.bukkit.block.Block block = loc.getBlock();
        if (block.isPassable())
            return false;
        return block.getBoundingBox().getVolume() < 0.999;
    }
    // Read from every player's thread on Folia
    private final List<Fight> fights = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** True while a fight is actually inside the arena (not just counting down at spawn). */
    public boolean hasRunningFight() {
        for (final Fight fight : this.fights)
            if (fight.getState() == FightState.PRE_FIGHT_START || fight.getState() == FightState.IN_FIGHT)
                return true;
        return false;
    }

    private boolean warnedNoExit;

    /**
     * Sends a non-fighter out of the arena by teleporting them directly (running /warp as the player
     * fails on permissions/warm-ups): to the exit location if set, otherwise to the world's spawn.
     */
    public void kickOut(final Player player) {
        FoliaUtils.teleport(player, this.exitFor(player));
        player.sendMessage(org.bukkit.ChatColor.RED + "You can't be in the arena while a fight is going on.");
    }

    /** Where to send someone leaving this arena: the exit location if set, otherwise their world's spawn. */
    public Location exitFor(final Player player) {
        Location target = this.exitLocation;
        if (target == null || target.getWorld() == null) {
            target = player.getWorld().getSpawnLocation();
            if (!this.warnedNoExit) {
                this.warnedNoExit = true;
                SpawnArena.INSTANCE.getLogger().warning("Arena " + this.arenaName
                        + " has no exit location; sending spectators to the world spawn. Set one with /setarenaexit " + this.arenaName);
            }
        }
        return target;
    }

    /** All-lowercase names are title cased ("sumo_pit" → "Sumo Pit"); any other casing is kept as typed. */
    public static String displayName(final String arena) {
        if (!arena.equals(arena.toLowerCase()))
            return arena;

        final StringBuilder out = new StringBuilder();
        for (final String word : arena.split("[_\\-\\s]+")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.isEmpty() ? arena : out.toString();
    }

    public Arena(String arenaName) {
        this.arenaName = arenaName;
    }

    public void setSpawnPoint1(final Location spawnPoint1) {
        this.spawnPoint1 = spawnPoint1;
    }

    public void setSpawnPoint2(final Location spawnPoint2) {
        this.spawnPoint2 = spawnPoint2;
    }

    public void addFight(Fight fight) {
        this.fights.add(fight);
    }

    public void removeFight(Fight fight) {
        this.fights.remove(fight);
    }

    public List<Fight> getFights() {
        return this.fights;
    }

    public Fight getFight(final Player p) {
        Fight found = null;

        for (final Fight fight : this.getFights()) {
            if (!fight.isFighter(p))
                continue;

            found = fight;
            break;
        }

        return found;
    }

    public List<Player> getOtherPlayers(final Fight fight) {
        final List<Player> players = new ArrayList<>();

        for (final Fight f : this.fights) {
            if (f.equals(fight))
                continue;

            players.addAll(f.getFighters());
        }

        return players;
    }

    /** Fighters of other fights that are actually inside the arena (not still counting down at spawn). */
    public List<Player> getOtherPlayersInArena(final Fight fight) {
        final List<Player> players = new ArrayList<>();

        for (final Fight f : this.fights) {
            if (f.equals(fight))
                continue;
            if (f.getState() != FightState.PRE_FIGHT_START && f.getState() != FightState.IN_FIGHT)
                continue;

            players.addAll(f.getFighters());
        }

        return players;
    }

    public void clearFights() {
        this.fights.clear();
    }

    /** Call on the fighter's thread. Completes once they've arrived (or the teleport failed). */
    public java.util.concurrent.CompletableFuture<Boolean> teleportFighter(final Player fighter, final Location spawn) {
        if (spawn == null)
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        this.dismount(fighter);
        return Threads.teleport(fighter, spawn);
    }

    private void dismount(final Player player) {
        final Entity vehicle = player.getVehicle();

        if (vehicle == null)
            return;

        vehicle.eject();
    }

    public void setQueue(ArenaQueue queue) {
        this.queue = queue;
    }

    /**
     * Horizontal distance from {@code loc} to this arena's WorldGuard region (0 inside its bounds).
     * Falls back to the nearest spawn point without a region, and is infinite from another world.
     */
    public double distanceFrom(final Location loc) {
        if (this.spawnPoint1 == null || loc.getWorld() == null || !loc.getWorld().equals(this.spawnPoint1.getWorld()))
            return Double.POSITIVE_INFINITY;

        if (ArenaUtils.WORLD_EDIT_SUPPORT) {
            final com.sk89q.worldguard.protection.managers.RegionManager manager =
                    ArenaUtils.REGION_CONTAINER.get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(loc.getWorld()));
            final com.sk89q.worldguard.protection.regions.ProtectedRegion region =
                    manager == null ? null : manager.getRegion(this.arenaName);

            if (region != null) {
                final com.sk89q.worldedit.math.BlockVector3 min = region.getMinimumPoint();
                final com.sk89q.worldedit.math.BlockVector3 max = region.getMaximumPoint();
                final double dx = Math.max(0, Math.max(min.x() - loc.getX(), loc.getX() - (max.x() + 1)));
                final double dz = Math.max(0, Math.max(min.z() - loc.getZ(), loc.getZ() - (max.z() + 1)));
                return Math.hypot(dx, dz);
            }
        }

        double distance = loc.distance(this.spawnPoint1);
        if (this.spawnPoint2 != null && this.spawnPoint2.getWorld().equals(loc.getWorld()))
            distance = Math.min(distance, loc.distance(this.spawnPoint2));
        return distance;
    }

    /**
     * Blocks between the higher spawn's feet and the top of the arena's WorldGuard region (how high a fighter can go
     * before they're out of it), or infinite without a region.
     */
    public double headroom() {
        if (!ArenaUtils.WORLD_EDIT_SUPPORT || this.spawnPoint1 == null || this.spawnPoint1.getWorld() == null)
            return Double.POSITIVE_INFINITY;
        final com.sk89q.worldguard.protection.managers.RegionManager manager =
                ArenaUtils.REGION_CONTAINER.get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(this.spawnPoint1.getWorld()));
        final com.sk89q.worldguard.protection.regions.ProtectedRegion region = manager == null ? null : manager.getRegion(this.arenaName);
        if (region == null)
            return Double.POSITIVE_INFINITY;
        final double feet = Math.max(this.spawnPoint1.getY(), this.spawnPoint2 == null ? this.spawnPoint1.getY() : this.spawnPoint2.getY());
        return region.getMaximumPoint().y() + 1 - feet;
    }

    /** Block Y of the lower spawn's feet (the floor is just below it), or null without spawns. */
    public Integer lowestSpawnY() {
        final Location a = this.spawnPoint1, b = this.spawnPoint2;
        if (a == null && b == null)
            return null;
        if (a == null || b == null)
            return (a != null ? a : b).getBlockY();
        return Math.min(a.getBlockY(), b.getBlockY());
    }

    public String getArenaName() {
        return this.arenaName;
    }

    public boolean isSetUp() {
        return this.spawnPoint1 != null && this.spawnPoint2 != null;
    }

    /**
     * Convert Arena class to any of its subtypes
     *
     * @param clazz
     * @param <T>
     * @return
     */
    public <T> T to(final Class<?> clazz) {
        if (!clazz.isInstance(this))
            throw new IllegalArgumentException("Arena is not of type " + clazz.getSimpleName());

        return (T) clazz.cast(this);
    }

    @Override
    public boolean equals(final Object object) {
        if (!(object instanceof Arena))
            return false;

        final Arena arena = (Arena) object;
        return arena.getArenaName().equals(this.getArenaName());
    }

    @Override
    public int hashCode() {
        return this.getArenaName().hashCode();
    }

    public static final class ArenaUtils {
        public static final boolean WORLD_EDIT_SUPPORT;
        public static final WorldGuard WORLD_GUARD_INSTANCE;
        public static final RegionContainer REGION_CONTAINER;

        static {
            boolean worldEditSupport;

            try {
                Class.forName("com.sk89q.worldguard.WorldGuard");
                worldEditSupport = true;
            } catch (final ClassNotFoundException exception) {
                worldEditSupport = false;
            }

            WORLD_EDIT_SUPPORT = worldEditSupport;

            WORLD_GUARD_INSTANCE = !WORLD_EDIT_SUPPORT ? null : WorldGuard.getInstance();
            REGION_CONTAINER = !WORLD_EDIT_SUPPORT ? null : WORLD_GUARD_INSTANCE.getPlatform().getRegionContainer();
        }

        public static void kickOutLingeringPlayers() {
            for (final Player p : Bukkit.getOnlinePlayers())
                Threads.entity(p, () -> kickOutLingeringPlayer(p));
        }

        /** Fighters, vanished players, spectator mode and spawnarena.bypasskickout may stay in arenas. */
        public static boolean isExemptFromKickOut(final Player p) {
            return isVanished(p)
                    || p.getGameMode() == GameMode.SPECTATOR
                    || p.hasPermission("spawnarena.bypasskickout")
                    || SpawnArena.arenas.hasFighter(p)
                || FfaFight.isJoining(p)
                // Out of their match a moment ago and about to be sent back with their things: not an onlooker
                || Fight.AWAITING_RESTORE.contains(p.getUniqueId());
        }

        /** Sends non-fighters out of this one arena only. */
        public static void kickOutLingeringPlayers(final Arena arena) {
            if (!WORLD_EDIT_SUPPORT)
                return;

            // Each player is checked on their own thread (block checks, teleport)
            for (final Player p : Bukkit.getOnlinePlayers())
                Threads.entity(p, () -> {
                    if (!isExemptFromKickOut(p) && arena.isInFightingSpace(p.getLocation()))
                        arena.kickOut(p);
                });
        }

        public static void kickOutLingeringPlayer(final Player p) {
            if (!WORLD_EDIT_SUPPORT)
                return;

            if (isExemptFromKickOut(p))
                return;

            final Arena arena = SpawnArena.arenas.of(p.getLocation());
            if (arena != null && arena.isInFightingSpace(p.getLocation()))
                arena.kickOut(p);
        }

        /**
         * Checking if the player is vanished on SuperVanish/PremiumVanish
         *
         * @param player
         * @return
         */
        public static boolean isVanished(final Player player) {
            final List<MetadataValue> values = player.getMetadata("vanished");


            for (final MetadataValue meta : values)
                if (meta.asBoolean())
                    return true;

            return false;
        }
    }

}
