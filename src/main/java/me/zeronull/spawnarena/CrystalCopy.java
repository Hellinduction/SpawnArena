package me.zeronull.spawnarena;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * One copy of a MULTI crystal arena that a match is actually fought in: the whole arena, pasted at the same
 * coordinates in a world of its own (see {@link CrystalCopies}). It has the real arena's name and settings, so stats,
 * Elo and rewards count for that arena, and shares its queue; but its own world, spawns, fights and clean-up.
 * Copies are only ever equal to themselves (a real arena is equal to anything with its name).
 */
public final class CrystalCopy extends Arena {
    private final SavableArena real;
    private final World world;
    private final int index;

    CrystalCopy(final SavableArena real, final World world, final int index) {
        super(real.getArenaName());
        this.real = real;
        this.world = world;
        this.index = index;
        this.queue = real.queue;
        this.sync();
    }

    /** Saved on the copy's world while a match there hasn't been put back yet (see {@link CrystalCopies#setUp}). */
    private static final org.bukkit.NamespacedKey IN_USE = new org.bukkit.NamespacedKey(SpawnArena.INSTANCE, "copy_in_use");

    /** A match is starting here: until it's put back, a restart copies the arena in again (its blocks may be left). */
    public void markInUse() {
        this.world.getPersistentDataContainer().set(IN_USE, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
    }

    /** Put back after its match. */
    public void markClean() {
        Threads.global(() -> this.world.getPersistentDataContainer().remove(IN_USE));
    }

    /** A match here was never put back (the server stopped or SpawnArena was reloaded during it, or it crashed). */
    public static boolean leftInUse(final org.bukkit.World world) {
        return world.getPersistentDataContainer().has(IN_USE, org.bukkit.persistence.PersistentDataType.BYTE);
    }

    /** The real arena this is a copy of. */
    public SavableArena real() {
        return this.real;
    }

    public World world() {
        return this.world;
    }

    /** 1, 2, 3...: which copy this is. */
    public int index() {
        return this.index;
    }

    /** Takes the real arena's current settings (after it's set up or reloaded), with its spawns moved into this world. */
    public void sync() {
        this.setSpawnPoint1(this.here(this.real.spawnPoint1));
        this.setSpawnPoint2(this.here(this.real.spawnPoint2));
        this.exitLocation = this.real.exitLocation; // the real arena's exit (on spawn)
        this.setShouldClearItems(this.real.isShouldClearItems());
        this.setAllowDamage(this.real.isAllowDamage());
        this.setAllowPvp(this.real.isAllowPvp());
        this.setDeathOnTouchLiquid(this.real.isDeathOnTouchLiquid());
        this.setWinOnStepStonePressurePlate(this.real.isWinOnStepStonePressurePlate());
        this.setGiveKnockBackStick(this.real.isGiveKnockBackStick());
        this.setArenaMode(ArenaMode.SINGLE.name()); // one match per copy
        this.setCrystalPvp(true);
        this.setFfa(false);
        this.setRandomSumo(this.real.isRandomSumo());
        this.setCombo(this.real.isCombo());
        this.setWinReward(this.real.getWinReward(), this.real.getWinRewardCooldownMinutes());
        this.setLeaderReward(this.real.getLeaderRewardMultiplier(), this.real.getLeaderRewardCooldownMinutes());
        this.setPodiumRewards(this.real.rewardMultiplierFor(2), this.real.rewardCooldownMinutesFor(2),
                this.real.rewardMultiplierFor(3), this.real.rewardCooldownMinutesFor(3));
        this.setEgapCooldownSeconds(this.real.getEgapCooldownSeconds());
        this.setDisplayName(this.real.getDisplayName());
        this.setTotemLimit(this.real.getTotemLimit());
    }

    /** The same spot in this copy's world. */
    public Location here(final Location onSpawn) {
        if (onSpawn == null)
            return null;
        final Location at = onSpawn.clone();
        at.setWorld(this.world);
        return at;
    }

    /** After a fight: the arena's schematic pasted back, in this copy, exactly as the real arena does it. */
    public java.util.concurrent.CompletableFuture<Void> resetArena() {
        return this.real.resetArena(this.world);
    }

    /** Distances (queue range, duels) are from the real arena on spawn, where players wait for a match here. */
    @Override
    public double distanceFrom(final Location loc) {
        return this.real.distanceFrom(loc);
    }

    @Override
    public boolean equals(final Object object) {
        return this == object;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public String toString() {
        return this.getArenaName() + " (copy " + this.index + ")";
    }
}
