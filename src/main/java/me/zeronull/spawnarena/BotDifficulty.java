package me.zeronull.spawnarena;

import org.bukkit.Material;

/**
 * How a sumo bot plays. Every level moves and takes knockback with real player physics; what changes is how well it
 * plays: how far it reaches, how fast and steadily it aims, how quickly it reacts, how often it clicks and misses,
 * whether it w-taps (lets go of sprint after a hit, so the next one gets full knockback), strafes and jump-resets
 * (jumps as it's hit, to take less knockback), sprint-jumps in, crouches when knocked near the edge, and how careful
 * it is near the edge.
 */
public enum BotDifficulty {
    //   label      colour          icon                    reach turn noise react swing  miss  wtap  strafe jumpRst edge kbTaken combo  sprintJump sneak
    EASY("Easy", "<green>", Material.LIME_DYE, 2.6, 8, 7, 8, 10, 15, 0.35, 0.0, 0.0, 0.0, 0.45, 1.0, false, 0.0, 0.0),
    MEDIUM("Medium", "<yellow>", Material.YELLOW_DYE, 2.75, 12, 5, 7, 8, 12, 0.25, 0.3, 0.3, 0.15, 0.6, 1.0, false, 0.05, 0.15),
    HARD("Hard", "<red>", Material.RED_DYE, 3.0, 32, 1.5, 3, 5, 7, 0.05, 0.95, 0.85, 0.65, 1.0, 1.0, false, 0.35, 0.7),
    CHEATER("Cheater", "<dark_purple>", Material.PURPLE_DYE, 3.8, 70, 0.3, 1, 5, 5, 0.0, 1.0, 1.0, 0.85, 1.0, 0.75, false, 0.45, 0.85),
    COMBO("Combo", "<gold>", Material.BLAZE_POWDER, 3.0, 32, 1.5, 3, 3, 4, 0.05, 0.95, 0.85, 0.65, 1.0, 1.0, true, 0.35, 0.7);

    public final String label, colour;
    public final Material icon;
    /** How far it can hit, from its eyes to the nearest point of your hitbox (a player's is 3). */
    public final double reach;
    /** Most degrees it turns its aim each tick, and how far its aim wanders. */
    public final double turnSpeed, aimNoise;
    /** Ticks before it reacts to where you've moved. */
    public final int reactionTicks;
    /** Ticks between swings (a random number between these). */
    public final int minSwingTicks, maxSwingTicks;
    /** Chance a swing in reach misses anyway. */
    public final double missChance;
    /** Chance it w-taps after a hit, how much it strafes (0-1), chance it jump-resets a hit, and how careful it is near the edge (0-1). */
    public final double wTapChance, strafe, jumpResetChance, edgeCaution;
    /** How much of the knockback it takes (cheater: less). */
    public final double knockbackTaken;
    /** No hit delay (both ways), fists, and the softer knockback of combo sumo. */
    public final boolean combo;
    /** Chance, each moment it's 3 to 6 blocks away, that it sprint-jumps in to close the gap. */
    public final double sprintJumpChance;
    /** Chance it crouches when knocked near the edge (slower, and it can't walk off), like players do. */
    public final double sneakChance;

    BotDifficulty(final String label, final String colour, final Material icon, final double reach, final double turnSpeed,
                  final double aimNoise, final int reactionTicks, final int minSwingTicks, final int maxSwingTicks,
                  final double missChance, final double wTapChance, final double strafe, final double jumpResetChance,
                  final double edgeCaution, final double knockbackTaken, final boolean combo, final double sprintJumpChance,
                  final double sneakChance) {
        this.label = label;
        this.colour = colour;
        this.icon = icon;
        this.reach = reach;
        this.turnSpeed = turnSpeed;
        this.aimNoise = aimNoise;
        this.reactionTicks = reactionTicks;
        this.minSwingTicks = minSwingTicks;
        this.maxSwingTicks = maxSwingTicks;
        this.missChance = missChance;
        this.wTapChance = wTapChance;
        this.strafe = strafe;
        this.jumpResetChance = jumpResetChance;
        this.edgeCaution = edgeCaution;
        this.knockbackTaken = knockbackTaken;
        this.combo = combo;
        this.sprintJumpChance = sprintJumpChance;
        this.sneakChance = sneakChance;
    }

    /** "<red>Hard" etc. (MiniMessage). */
    public String coloured() {
        return this.colour + this.label;
    }

    public static BotDifficulty of(final String name) {
        for (final BotDifficulty d : values())
            if (d.name().equalsIgnoreCase(name) || d.label.equalsIgnoreCase(name))
                return d;
        return null;
    }
}
