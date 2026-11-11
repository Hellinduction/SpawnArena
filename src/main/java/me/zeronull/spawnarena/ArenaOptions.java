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
    private volatile boolean spleef;
    private volatile java.util.Set<org.bukkit.Material> spleefFloor = java.util.EnumSet.of(org.bukkit.Material.SNOW_BLOCK);
    private volatile boolean ffa;
    private volatile boolean randomSumo;

    private volatile boolean combo;

    /** Combo rules for every fight here: no hit delay, softer knockback (see SumoCombo). */
    public boolean isCombo() {
        return this.combo;
    }

    public ArenaOptions setCombo(final boolean value) {
        this.combo = value;
        return this;
    }

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

    /** Totems that can save each fighter per fight; 0 = no limit. */
    private volatile int totemLimit;

    public int getTotemLimit() {
        return this.totemLimit;
    }

    public void setTotemLimit(final int limit) {
        this.totemLimit = Math.max(0, limit);
    }

    // Free-for-all kill streak payouts
    private volatile double ffaStreakReward, ffaStreakRewardTens;
    private volatile int ffaStreakRewardCooldownMinutes, ffaSameVictimMinutes;

    public void setFfaRewards(final double fives, final double tens, final int cooldownMinutes, final int sameVictimMinutes) {
        this.ffaStreakReward = Math.max(0, fives);
        this.ffaStreakRewardTens = Math.max(0, tens);
        this.ffaStreakRewardCooldownMinutes = Math.max(0, cooldownMinutes);
        this.ffaSameVictimMinutes = Math.max(0, sameVictimMinutes);
    }

    /** What reaching this kill streak pays in a free-for-all: 10, 20, 30... pay the tens amount, 5, 15, 25... the other; 0 otherwise. */
    public double ffaRewardFor(final int streak) {
        if (streak <= 0 || streak % 5 != 0)
            return 0;
        return streak % 10 == 0 ? this.ffaStreakRewardTens : this.ffaStreakReward;
    }

    public double getFfaStreakReward() {
        return this.ffaStreakReward;
    }

    public double getFfaStreakRewardTens() {
        return this.ffaStreakRewardTens;
    }

    public int getFfaStreakRewardCooldownMinutes() {
        return this.ffaStreakRewardCooldownMinutes;
    }

    public int getFfaSameVictimMinutes() {
        return this.ffaSameVictimMinutes;
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
     * Free-for-all arenas have one session that everyone joins. Crystal PvP arenas run one fight at a time in the arena
     * itself (blocks placed in one fight would show up in the other), unless they're MULTI where that's supported:
     * then each match is fought in a copy of its own (CrystalCopies), each copy running one at a time.
     */
    public ArenaMode getArenaMode() {
        // (spleef: one match at a time in the arena itself, as its floor is broken; copies of their own to come)
        if (this.ffa || this.spleef)
            return ArenaMode.SINGLE;
        if (this.crystalPvp)
            return this.arenaMode == ArenaMode.MULTI && CrystalCopies.supported() && !(this instanceof CrystalCopy)
                    ? ArenaMode.MULTI : ArenaMode.SINGLE;
        return this.arenaMode;
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

    /** Spleef: fighters break the floor under each other (see Spleef). */
    public boolean isSpleef() {
        return this.spleef;
    }

    public ArenaOptions setSpleef(final boolean value) {
        this.spleef = value;
        return this;
    }

    /** Spleef: what the floor is made of, the only blocks fighters can break. */
    public java.util.Set<org.bukkit.Material> getSpleefFloor() {
        return this.spleefFloor;
    }

    public ArenaOptions setSpleefFloor(final java.util.Collection<String> materials) {
        final java.util.Set<org.bukkit.Material> floor = java.util.EnumSet.noneOf(org.bukkit.Material.class);
        if (materials != null)
            for (final String name : materials) {
                final org.bukkit.Material m = org.bukkit.Material.matchMaterial(name);
                if (m != null && m.isBlock())
                    floor.add(m);
            }
        if (floor.isEmpty())
            floor.add(org.bukkit.Material.SNOW_BLOCK);
        this.spleefFloor = floor;
        return this;
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