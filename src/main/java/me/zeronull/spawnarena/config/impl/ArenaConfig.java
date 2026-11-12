package me.zeronull.spawnarena.config.impl;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import me.zeronull.spawnarena.ArenaMode;
import org.bukkit.Location;

@Configuration
public final class ArenaConfig {
    @Comment("Name of the arena")
    public String arenaName; // To keep everything simple, this should be the region name in world guard

    @Comment("Spawnpoint for 1st fighter")
    public Location spawnPoint1;

    @Comment("Spawnpoint for 2nd fighter")
    public Location spawnPoint2;

    @Comment("Should it clear the inventory on arena start of fighters?")
    public boolean shouldClearItems = false;

    @Comment("Should fighters be able to deal damage to each other?")
    public boolean allowDamage = true;

    @Comment("Should fighters be able to pvp each other?")
    public boolean allowPvp = true;

    @Comment("Should a fighter die if they touch liquid?")
    public boolean deathOnTouchLiquid;

    @Comment("Should a fighter win against their opponent if they stand on a stone pressure plate?")
    public boolean winOnStepStonePressurePlate;

    @Comment("Should it give the fighters a knockback fish (tropical fish) when they are first teleported into the arena")
    public boolean giveKnockBackStick;

    @Comment("Money (via Vault) paid to the winner of a fight. 0 disables it.")
    public double winReward = 50000;

    @Comment("Minutes before the same player can earn this arena's win reward again")
    public int winRewardCooldownMinutes = 30;

    @Comment("Win reward multiplier for the #1 current win streak holder in this arena (one player; on a tie, whoever got there first)")
    public double leaderRewardMultiplier = 3;

    @Comment("Minutes between win rewards for the #1 current win streak holder")
    public int leaderRewardCooldownMinutes = 10;

    @Comment("Win reward multiplier for the #2 current win streak")
    public double secondRewardMultiplier = 1.5;

    @Comment("Minutes between win rewards for the #2 current win streak")
    public int secondRewardCooldownMinutes = 15;

    @Comment("Win reward multiplier for the #3 current win streak")
    public double thirdRewardMultiplier = 1.2;

    @Comment("Minutes between win rewards for the #3 current win streak")
    public int thirdRewardCooldownMinutes = 20;

    @Comment("Totems of Undying that can save each fighter per fight (in a free-for-all, each time they come in); once\n"
            + "they're used up, a held totem doesn't save them. Shown in their action bar. 0 = no limit. Set with /arenatotems.")
    public int totemLimit = 0;

    @Comment("Free-for-all only (it has no wins): money paid for reaching a kill streak of 5, 15, 25... 0 disables it.")
    public double ffaStreakReward = 50000;

    @Comment("Free-for-all only: money paid for reaching a kill streak of 10, 20, 30... 0 disables it.")
    public double ffaStreakRewardTens = 100000;

    @Comment("Free-for-all only: minutes before a player can be paid for the same kill streak (5, 10, 15...) again. The\n"
            + "others still pay meanwhile, so to keep earning they have to reach streaks they haven't been paid for lately.")
    public int ffaStreakRewardCooldownMinutes = 30;

    @Comment("Free-for-all only: minutes before killing the same player counts towards a paid streak again (every kill still\n"
            + "counts in chat and on the boards). Stops a friend dying to someone over and over to pay them.")
    public int ffaSameVictimMinutes = 5;

    @Comment({"Seconds a fighter has to wait after eating an enchanted golden apple before eating another (shown on",
            "the item, like an ender pearl's). Only while fighting in this arena: it's cleared when they're out of the",
            "fight, and never touches golden apples anywhere else. 0 = no cooldown."})
    public int egapCooldownSeconds = 0;

    @Comment({"How players see this arena's name in messages, menus and holograms, e.g. CrystalPvP. Blank = worked out from",
            "its name (crystalpvp shows as Crystalpvp). After changing it: /arenareload, then /arenaleaderboard update."})
    public String displayName = "";

    @Comment({"Crystal PvP: fighters can place blocks and end crystals inside the arena's region, but can only",
            "break or blow up blocks placed during the fight. Everything placed is removed when the fight ends.",
            "The plugin lets fighters past WorldGuard itself, so the region's flags can stay as they are (build denied).",
            "Always runs as SINGLE."})
    public boolean crystalPvp = false;

    @Comment({"Spleef: fighters get a shovel and break the floor (spleefFloor blocks, nothing else) to drop each other",
            "through it into the liquid at the bottom; each block broken gives them a snowball, and a snowball breaks the",
            "floor where it lands (or knocks someone back). No other damage. The floor is put back after every match.",
            "Elo, wins, streaks and duels as in any arena. Toggle with /togglearenamode <arena> spleef."})
    public boolean spleef = false;

    @Comment("Spleef: the blocks the floor is made of, the only ones fighters can break")
    public java.util.List<String> spleefFloor = java.util.List.of("SNOW_BLOCK");

    @Comment({"Free-for-all: /joinarenaqueue sends players straight in (no queue, no countdown) and everyone fights",
            "everyone until they die or /leavearena (only out of combat). Players keep their own things, which are given",
            "back as they were when they leave or die. Kills, deaths and kill streaks are counted separately; no wins, win",
            "streaks, Elo or bets: kill streaks pay instead (ffaStreakReward). Always runs as SINGLE."})
    public boolean ffa = false;

    @Comment({"Random sumo (arenas where touching water loses): each match gets a random variant, the same for both sides:",
            "Classic (Slap Fish), Archer (Punch bow), Ice Rink (the floor turns to ice; only when one match has the arena",
            "to itself, or shown only to that match in MULTI: see sumoIceInMulti), Snowball Fight, Rod (knockback fishing rod),",
            "Moon Gravity, Shrinking Platform (the edges fall away; SINGLE arenas only, not free-for-alls) or Combo (no hit delay).",
            "Replaces giveKnockBackStick when on."})
    public boolean randomSumo = false;

    @Comment({"Combo: every fight here has no hit delay (hits chain into combos) and softer knockback, like random sumo's",
            "Combo variant. Toggle with /togglearenamode <arena> combo."})
    public boolean combo = false;

    @Comment({"Mace PvP (crystal arenas only): fighters bring their own kit (mace, wind charges, pearls...) and can build as in",
            "crystal PvP, but end crystals, respawn anchors and beds can't be used. Toggle with /togglearenamode <arena> mace."})
    public boolean mace = false;

    @Comment("Mode of the arena: SINGLE, MULTI")
    public String arenaMode = ArenaMode.SINGLE.toString();

    @Comment({"Where non-fighters are sent if they're in the arena during a fight (set with /setarenaexit).",
            "If unset, they're sent to the world spawn."})
    public Location exitLocation = null;

    @Comment("Paste location of the arena (pastes the schematic)")
    public Location pasteLocation = null;

    @Comment("Schematic of the arena")
    public String schematic = null;

    @Comment({"How far the schematic is turned when it's pasted, clockwise seen from above: 0, 90, 180 or 270. Set by /arenamove",
            "when the arena's build was pasted turned in its new spot."})
    public int pasteTurn = 0;
}