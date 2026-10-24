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
    public double dailyRewardCap = 1_500_000;

    @Comment({"Payouts in a row against the same opponent. After that many, beating them again doesn't pay",
            "until the player beats someone else (paid or not). 0 = no limit."})
    public int sameOpponentLimit = 3;

    @Comment({"Rated fights a player needs in an arena before they show on its Elo leaderboard (their \"You\" line says",
            "unranked until then), so winning a couple and never playing again can't top it. 0 = everyone shows."})
    public int eloMinGames = 5;

    @Comment({"Don't pay win rewards when the winner and loser have ever connected from the same IP address.",
            "IPs are stored hashed. Local/LAN addresses are ignored."})
    public boolean blockSameIpRewards = true;

    @Comment({"Don't count fights between players who have ever connected from the same IP address (alts)",
            "towards wins, win streaks or the leaderboards."})
    public boolean blockSameIpStats = true;

    @Comment({"Seconds of \"Starting in...\" after players are paired, before the fight starts. They can still back out",
            "during it. 0 = straight in."})
    public int countdownSeconds = 3;

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

    @Comment({"Random sumo's Ice Rink in MULTI arenas (several matches on the same platform): the ice is only shown to that",
            "match's players (the real floor doesn't change). Their game slides on it while the server still sees the old floor,",
            "which some anticheats may flag. Set to false and Ice Rink is never picked in MULTI arenas (SINGLE arenas use real ice)."})
    public boolean sumoIceInMulti = true;

    @Comment({"Crystal arenas: blocks fighters can dig out of the arena's own floor during a fight (put back afterwards, like",
            "everything else). Only below the spawns' feet and under the open pit (never under a wall), and only inside",
            "the arena's region. Bedrock is never diggable, even if listed. Empty list = nothing of the floor can be dug."})
    public java.util.List<String> crystalDiggableBlocks = new java.util.ArrayList<>(java.util.List.of(
            "dirt", "grass_block", "coarse_dirt", "rooted_dirt", "podzol", "mud", "sand", "gravel", "netherrack", "obsidian", "stone",
            "coal_ore", "copper_ore", "iron_ore", "lapis_ore", "gold_ore", "redstone_ore", "diamond_ore", "emerald_ore"));

    @Comment({"With bunnyAC installed: MULTI Ice Rink and Moon Gravity players are taken out of bunnyAC for their match (it",
            "flags the sliding, as it sees the real floor, and the moon jumps), and put back as soon as they're out.",
            "Staff-exempted players are left alone. Until bunnyAC can exempt players from single checks, this means no",
            "anticheat checks at all for that match."})
    public boolean iceRinkAnticheatExempt = true;

    @Comment({"Commands fighters can still use mid-fight (all others are blocked): messaging, and staff punishing someone",
            "(players without the permission just get the usual \"no permission\"). Each one's aliases are allowed too (e.g. msg",
            "also lets through tell, w and whisper if the plugin that owns it registers them)."})
    public java.util.List<String> fightAllowedCommands = new java.util.ArrayList<>(java.util.List.of(
            "msg", "tell", "whisper", "w", "m", "t", "pm", "dm", "message", "r", "reply", "ignore", "unignore",
            "msgtoggle", "togglemsg",
            "kick", "ban", "tempban", "ipban", "banip", "tempipban", "mute", "tempmute", "ipmute", "tempipmute",
            "warn", "unban", "unmute", "unwarn", "history", "checkban", "checkmute", "report", "helpop"));
}
