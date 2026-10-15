package me.zeronull.spawnarena.config.impl;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import de.exlll.configlib.YamlConfigurationProperties;

@Configuration
public final class SettingsConfig {

    public static final YamlConfigurationProperties PROPERTIES = YamlConfigurationProperties.newBuilder().build();

    @Comment("Name of the world players must be in to create arenas, toggle arena modes and join queues")
    public String spawnWorld = "Spawn";

    @Comment({"How far (in blocks) a queued player can walk from their arena's WorldGuard region before",
            "being removed from the queue. Also cancels a fight that's still counting down. 0 disables it."})
    public double queueMaxDistance = 64;

    @Comment({"Broadcast fight results to the whole server. When false, only the winner and loser see them.",
            "Toggle in game with /arenaannounce"})
    public boolean broadcastWins = true;

    @Comment({"Whether players can join arenas and new fights can start. Fights already running always finish.",
            "Toggle in game with /arenalock (e.g. close before reloading the plugin)"})
    public volatile boolean arenasOpen = true;

    @Comment({"Currency symbol for short reward amounts like \"$5k\". Leave empty to copy it from the",
            "economy plugin's own formatting (falls back to \"$\" if there's no economy)."})
    public String currencySymbol = "";

    @Comment({"Most money one player can earn from arena win rewards per day (server time), across all arenas.",
            "0 = no limit."})
    public double dailyRewardCap = 1_000_000;

    @Comment({"Payouts in a row against the same opponent. After that many, beating them again doesn't pay",
            "until the player beats someone else (paid or not). 0 = no limit."})
    public int sameOpponentLimit = 3;

    @Comment({"Don't pay win rewards when the winner and loser have ever connected from the same IP address.",
            "IPs are stored hashed. Local/LAN addresses are ignored."})
    public boolean blockSameIpRewards = true;

    @Comment({"Don't count fights between players who have ever connected from the same IP address (alts)",
            "towards wins, win streaks or the leaderboards."})
    public boolean blockSameIpStats = true;

    @Comment("Losing a win streak at least this long gets a dramatic announcement. 0 disables it.")
    public int streakEndAnnounceThreshold = 5;

    @Comment("How close (in blocks) to an arena's region you must be to shift-right-click someone for a duel there")
    public double duelRadius = 10;

    @Comment("Seconds before an unanswered duel request expires")
    public int duelRequestSeconds = 60;

    @Comment({"With the SpawnArena bridge plugin on your Velocity proxy, win announcements go to every server on the",
            "network. Without the bridge (or with this off), they're announced in this server's chat as usual."})
    public boolean proxyBroadcast = true;

    @Comment({"Most a player can bet with /arenabet. One bet at a time, only for their next fight: it's cancelled if that",
            "fight can't pay out (their win reward there isn't ready) or is a duel. 0 turns betting off."})
    public double betMax = 75000;

    @Comment({"What winning a bet pays on top of getting it back, as a percentage of the bet (50 = bet 50k, win 25k extra).",
            "Losing loses the bet. The bonus counts toward dailyRewardCap."})
    public int betWinBonusPercent = 50;

    @Comment({"The #1 win streak only earns the leader bonus (an arena's leaderRewardMultiplier) once it's at least",
            "this long, so the first winner in an empty arena doesn't get it straight away. 1 = any streak."})
    public int leaderBonusMinStreak = 3;

    @Comment({"If the arena's tick rate (TPS) averaged below this during a fight, the loser gets lagRefundPercent of their",
            "bet back (not if they quit). On Folia it's the tick rate of the part of the world the arena is in. 0 turns it off."})
    public double lagRefundBelowTps = 17;

    @Comment("How much of a lost bet comes back after a laggy fight (see lagRefundBelowTps), as a percentage")
    public int lagRefundPercent = 50;

    @Comment({"A fighter whose connection drops mid-fight (the server times them out, or they send nothing for",
            "lagOutQuietSeconds and then disconnect) doesn't lose: a 1v1 ends as a draw with no stats changed and bets",
            "given back; in 2v2 they're out but get their bet back. At most this many per player per day, after which",
            "it counts as quitting. 0 turns it off."})
    public int lagOutsPerDay = 2;

    @Comment("How long a fighter must have sent nothing before disconnecting for it to count as a lag-out")
    public double lagOutQuietSeconds = 3;

    @Comment({"Outline everyone in a running fight with a glow in that fight's own colour, so spectators can tell",
            "matches apart. Fighters see it too (it shows through walls)."})
    public boolean fightGlow = true;

    @Comment({"Free-for-all arenas: seconds after hitting or being hit by another player that someone counts as in combat.",
            "They can't /leavearena until it runs out, logging out or teleporting away in combat counts as dying, and",
            "everyone who hit a player in this window gets the kill."})
    public int ffaCombatSeconds = 10;

    @Comment({"Free-for-all arenas: seconds a player can't be hurt after joining, so they can't be killed as they appear.",
            "Ends early if they attack someone. 0 turns it off."})
    public int ffaSpawnProtectionSeconds = 3;

    @Comment({"Free-for-all arenas: a kill streak of every this many kills (5, 10, 15...) is announced to the server,",
            "and so is someone ending a streak at least this long. 0 keeps every free-for-all message inside the arena."})
    public int ffaStreakAnnounce = 5;
}
