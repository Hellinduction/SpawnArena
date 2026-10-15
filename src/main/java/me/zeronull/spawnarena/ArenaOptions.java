package me.zeronull.spawnarena;

public abstract class ArenaOptions {
    private boolean shouldClearItems;
    private boolean allowDamage;
    private boolean allowPvp;
    private boolean deathOnTouchLiquid;
    private boolean winOnStepStonePressurePlate;
    private boolean giveKnockBackStick;
    private ArenaMode arenaMode;
    private boolean crystalPvp;
    private double winReward;
    private int winRewardCooldownMinutes;

    public double getWinReward() {
        return this.winReward;
    }

    public int getWinRewardCooldownMinutes() {
        return this.winRewardCooldownMinutes;
    }

    private double leaderRewardMultiplier;
    private int leaderRewardCooldownMinutes;

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

    private int egapCooldownSeconds;

    public int getEgapCooldownSeconds() {
        return this.egapCooldownSeconds;
    }

    public void setEgapCooldownSeconds(final int seconds) {
        this.egapCooldownSeconds = Math.max(0, seconds);
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

    /** Crystal PvP arenas always run one fight at a time: blocks placed in one fight would show up in the other. */
    public ArenaMode getArenaMode() {
        return this.crystalPvp ? ArenaMode.SINGLE : this.arenaMode;
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