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
    public boolean arenasOpen = true;

    @Comment({"Currency symbol for short reward amounts like \"$5k\". Leave empty to copy it from the",
            "economy plugin's own formatting (falls back to \"$\" if there's no economy)."})
    public String currencySymbol = "";

    @Comment({"Most money one player can earn from arena win rewards per day (server time), across all arenas.",
            "0 = no limit."})
    public double dailyRewardCap = 2_000_000;

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
    public int streakEndAnnounceThreshold = 10;

    @Comment("How close (in blocks) to an arena's region you must be to shift-right-click someone for a duel there")
    public double duelRadius = 10;

    @Comment("Seconds before an unanswered duel request expires")
    public int duelRequestSeconds = 30;}
