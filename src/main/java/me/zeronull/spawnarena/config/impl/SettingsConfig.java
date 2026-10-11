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
}
