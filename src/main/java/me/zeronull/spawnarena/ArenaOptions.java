package me.zeronull.spawnarena;

public abstract class ArenaOptions {
    private volatile boolean shouldClearItems;
    private volatile boolean allowDamage;
    private volatile boolean allowPvp;
    private volatile boolean deathOnTouchLiquid;
    private volatile boolean winOnStepStonePressurePlate;
    private volatile boolean giveKnockBackStick;
    private volatile ArenaMode arenaMode;
    private volatile boolean crystalPvp;
    private volatile boolean ffa;
    private volatile boolean randomSumo;

    public boolean isRandomSumo() {
        return this.randomSumo;
    }

    public ArenaOptions setRandomSumo(final boolean value) {
        this.randomSumo = value;
        return this;
    }
    private volatile double winReward;
    private volatile int winRewardCooldownMinutes;

    public double getWinReward() {
        return this.winReward;
    }

    public int getWinRewardCooldownMinutes() {
        return this.winRewardCooldownMinutes;
    }

    private volatile double leaderRewardMultiplier;
    private volatile int leaderRewardCooldownMinutes;

    public double getLeaderRewardMultiplier() {
        return this.leaderRewardMultiplier;
    }

    public int getLeaderRewardCooldownMinutes() {
        return this.leaderRewardCooldownMinutes;
    }

    public void setLeaderReward(final double multiplier, final int cooldownMinutes) {
        this.leaderRewardMultiplier = multiplier;
        this.leaderRewardCooldownMinutes = cooldownMinutes;
    }

    // #2 and #3 on the current win streak leaderboard
    private volatile double secondRewardMultiplier = 1, thirdRewardMultiplier = 1;
    private volatile int secondRewardCooldownMinutes, thirdRewardCooldownMinutes;

    public void setPodiumRewards(final double second, final int secondCooldown, final double third, final int thirdCooldown) {
        this.secondRewardMultiplier = second;
        this.secondRewardCooldownMinutes = secondCooldown;
        this.thirdRewardMultiplier = third;
        this.thirdRewardCooldownMinutes = thirdCooldown;
    }

    /** Win reward multiplier for this place on the current win streak board: 1, 2 or 3 (anything else = 1). */
    public double rewardMultiplierFor(final int rank) {
        return switch (rank) {
            case 1 -> this.leaderRewardMultiplier;
            case 2 -> this.secondRewardMultiplier;
            case 3 -> this.thirdRewardMultiplier;
            default -> 1;
        };
    }

    /** Minutes between win rewards for this place on the current win streak board (anything else = the normal wait). */
    public int rewardCooldownMinutesFor(final int rank) {
        return switch (rank) {
            case 1 -> this.leaderRewardCooldownMinutes;
            case 2 -> this.secondRewardCooldownMinutes;
            case 3 -> this.thirdRewardCooldownMinutes;
            default -> this.getWinRewardCooldownMinutes();
        };
    }

    private volatile int egapCooldownSeconds;

    public int getEgapCooldownSeconds() {
        return this.egapCooldownSeconds;
    }

    public void setEgapCooldownSeconds(final int seconds) {
        this.egapCooldownSeconds = Math.max(0, seconds);
    }

    /** How players see the arena's name (blank: worked out from its name, e.g. "sumo" shows as "Sumo"). */
    private volatile String displayName = "";

    public String getDisplayName() {
        return this.displayName;
    }

    public void setDisplayName(final String name) {
        this.displayName = name == null ? "" : name.trim();
    }

    public void setWinReward(final double amount, final int cooldownMinutes) {
        this.winReward = amount;
        this.winRewardCooldownMinutes = cooldownMinutes;
    }

    public boolean isShouldClearItems() {
        return this.shouldClearItems;
    }

    public ArenaOptions setShouldClearItems(final boolean value) {
        this.shouldClearItems = value;
        return this;
    }

    public boolean isAllowDamage() {
        return this.allowDamage;
    }

    public ArenaOptions setAllowDamage(final boolean value) {
        this.allowDamage = value;
        return this;
    }

    public boolean isAllowPvp() {
        return this.allowPvp;
    }

    public ArenaOptions setAllowPvp(final boolean value) {
        this.allowPvp = value;
        return this;
    }

    public boolean isDeathOnTouchLiquid() {
        return this.deathOnTouchLiquid;
    }

    public ArenaOptions setDeathOnTouchLiquid(final boolean value) {
        this.deathOnTouchLiquid = value;
        return this;
    }

    public boolean isWinOnStepStonePressurePlate() {
        return this.winOnStepStonePressurePlate;
    }

    public ArenaOptions setWinOnStepStonePressurePlate(final boolean value) {
        this.winOnStepStonePressurePlate = value;
        return this;
    }

    public boolean isGiveKnockBackStick() {
        return this.giveKnockBackStick;
    }

    public ArenaOptions setGiveKnockBackStick(final boolean value) {
        this.giveKnockBackStick = value;
        return this;
    }

    /**
     * Crystal PvP arenas always run one fight at a time: blocks placed in one fight would show up in the other.
     * Free-for-all arenas have one session that everyone joins.
     */
    public ArenaMode getArenaMode() {
        return this.crystalPvp || this.ffa ? ArenaMode.SINGLE : this.arenaMode;
    }

    public boolean isFfa() {
        return this.ffa;
    }

    public ArenaOptions setFfa(final boolean value) {
        this.ffa = value;
        return this;
    }

    public boolean isCrystalPvp() {
        return this.crystalPvp;
    }

    public ArenaOptions setCrystalPvp(final boolean value) {
        this.crystalPvp = value;
        return this;
    }

    public ArenaOptions setArenaMode(final String arenaMode) {
        this.arenaMode = ArenaMode.valueOf(arenaMode);
        return this;
    }
}