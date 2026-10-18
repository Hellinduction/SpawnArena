package me.zeronull.spawnarena;

import me.zeronull.spawnarena.commands.impl.*;
import me.zeronull.spawnarena.config.ConfigHandler;
import me.zeronull.spawnarena.events.*;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.logging.Level;

public class SpawnArena extends JavaPlugin {
    public static SpawnArena INSTANCE;
    // Replaced on enable/reload and read from every thread
    public static volatile Arenas arenas;
    public static volatile StatsManager statsManager;
    public static volatile me.zeronull.spawnarena.config.impl.SettingsConfig settings;

    public SpawnArena() {
        INSTANCE = this;
    }

    @Override
    public void onEnable() {

        settings = ConfigHandler.getInstance().getSettingsConfig();
        Arena.SPAWN_WORLD = settings.spawnWorld;
        Bukkit.getPluginManager().registerEvents(new QueueDistanceListener(), this);

        try {
            statsManager = new StatsManager(this, ConfigHandler.getInstance().getDatabaseConfig());
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialise database — disabling plugin", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // Register PlaceholderAPI expansion if PAPI is present
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new SpawnArenaPapiExpansion().register();
        }

        registerEvents();
        registerCommands();
        ProxyBridge.enable();
        FightGlow.cleanUpOnEnable();
        LagOuts.watchOnline();
        // Bets held by fights that never finished (crash/shutdown) go back; a bit later, as the economy
        // plugin may register after this one
        Threads.globalLater(() -> {
            if (Bukkit.getPluginManager().isPluginEnabled("Vault"))
                Bets.refundLeftovers();
        }, 100L);
        if (Bukkit.getPluginManager().isPluginEnabled("voicechat")) {
            try {
                ArenaVoicechat.register();
            } catch (final LinkageError | RuntimeException e) {
                getLogger().warning("Couldn't hook into Simple Voice Chat (" + e + "); separate fights may hear each other.");
            }
        }

        arenas = Arenas.fromList(ConfigHandler.getInstance().registerArenasFromConfig());

        if (!Arena.ArenaUtils.WORLD_EDIT_SUPPORT)
            for (final Arena arena : arenas)
                if (arena.isCrystalPvp())
                    getLogger().warning("Arena " + arena.getArenaName() + " has crystalPvp on, but WorldGuard isn't installed, so it can't be used.");

        // Undo anything a crash left in crystal arenas, then keep the crash-safety file up to date
        CrystalPvp.restoreLeftovers();
        CrystalPvp.startSaving();
    }

    private void registerEvents() {
        if (Arena.ArenaUtils.WORLD_EDIT_SUPPORT)
            Bukkit.getServer().getPluginManager().registerEvents(new ArenaWorldguardPvPEvent(), this);

        Bukkit.getServer().getPluginManager().registerEvents(new ArenaCommandEvent(), this);
        Bukkit.getServer().getPluginManager().registerEvents(new me.zeronull.spawnarena.events.EgapCooldown(), this);
        Bukkit.getServer().getPluginManager().registerEvents(new me.zeronull.spawnarena.events.FightStatsListener(), this);
        Bukkit.getServer().getPluginManager().registerEvents(new SlapFish(), this);
        Bukkit.getServer().getPluginManager().registerEvents(new SumoShrink(), this);
        Bukkit.getServer().getPluginManager().registerEvents(new SumoIce(), this);
        Threads.globalLater(SumoIce::restoreLeftovers, 40L);
        Bukkit.getServer().getPluginManager().registerEvents(new ArenaDropItemEvent(), this);
        Bukkit.getServer().getPluginManager().registerEvents(new me.zeronull.spawnarena.events.ArenaMobSpawnGuard(), this);
        Bukkit.getServer().getPluginManager().registerEvents(new ArenaPreventCraftingSlot(), this);
        Bukkit.getServer().getPluginManager().registerEvents(new ArenaPlayerLeave(), this);
        Bukkit.getServer().getPluginManager().registerEvents(new ArenaPlayerLeaveAreaEvent(), this);
        Bukkit.getServer().getPluginManager().registerEvents(new QueuePlayerLeave(), this);
        Bukkit.getServer().getPluginManager().registerEvents(new PlayerLeaveSpawn(), this);
        Bukkit.getServer().getPluginManager().registerEvents(new ArenaPlayerJoinEvent(), this);
        Bukkit.getServer().getPluginManager().registerEvents(new LiquidListener(), this);
        Bukkit.getServer().getPluginManager().registerEvents(new ArenaPlayerInteractEvent(), this);
        Bukkit.getServer().getPluginManager().registerEvents(new ArenaPlayerConsumeEvent(), this);
        Bukkit.getServer().getPluginManager().registerEvents(new ArenaProjectileDupeFix(), this);
        Bukkit.getServer().getPluginManager().registerEvents(new ArenaEntityHideEvent(), this);
        Bukkit.getServer().getPluginManager().registerEvents(new ArenaInventoryOpenEvent(), this);
        Bukkit.getServer().getPluginManager().registerEvents(new TeleportKickOut(), this);
        Bukkit.getServer().getPluginManager().registerEvents(new ArenaTeamPvPEvent(), this);
        Bukkit.getServer().getPluginManager().registerEvents(new ArenaPotionSplashEvent(), this);
        Bukkit.getServer().getPluginManager().registerEvents(new ArenaFireFix(), this);
        Bukkit.getServer().getPluginManager().registerEvents(new ArenaTeleportGrace(), this);
        Bukkit.getServer().getPluginManager().registerEvents(new DuelRequestListener(), this);
        Bukkit.getServer().getPluginManager().registerEvents(new QueueMenuListener(), this);
        Bukkit.getServer().getPluginManager().registerEvents(new me.zeronull.spawnarena.events.ThrownTridents(), this);
        Bukkit.getServer().getPluginManager().registerEvents(new LagOuts(), this);
        Bukkit.getServer().getPluginManager().registerEvents(new FightGlow(), this);

        if (Bukkit.getPluginManager().isPluginEnabled("FancyHolograms"))
            LeaderboardHolograms.startReadyEffects();
        if (Arena.ArenaUtils.WORLD_EDIT_SUPPORT) {
            Bukkit.getServer().getPluginManager().registerEvents(new ArenaSpectatorGuard(), this);
            Bukkit.getServer().getPluginManager().registerEvents(new ArenaBoundaryGuard(), this);
            Bukkit.getServer().getPluginManager().registerEvents(new me.zeronull.spawnarena.events.CrystalArenaListener(), this);
            // Uses WorldGuard's internal events; if a WorldGuard update changes them, crystal arenas still work
            // (fighters just see WorldGuard's "can't place" messages unless the region allows building)
            try {
                Bukkit.getServer().getPluginManager().registerEvents(new me.zeronull.spawnarena.events.CrystalWorldGuardBypass(), this);
            } catch (final LinkageError | RuntimeException e) {
                getLogger().warning("Couldn't hook into WorldGuard's events for crystal arenas (" + e
                        + "). Set the build flag to allow in crystal arena regions instead.");
            }
        }
        // Must stay registered after ArenaTeamPvPEvent/ArenaFireFix: same priority runs in registration order
        Bukkit.getServer().getPluginManager().registerEvents(new ArenaDeathEvent(), this);
    }

    private void registerCommands() {
        this.registerCommand("joinarenaqueue", new JoinArenaQueueCommand());
        this.registerCommand("setarenaspawn", new SetArenaSpawnCommand());
        this.registerCommand("createarena", new CreateArenaCommand());
        this.registerCommand("togglearenamode", new ToggleArenaModeCommand());
        this.registerCommand("setupfight", new SetUpFightCommand());
        this.registerCommand("setarenaschematic", new SetArenaSchematicCommand());
        this.registerCommand("setpastelocation", new SetPasteLocationCommand());
        this.registerCommand("restore", new RestoreCommand());
        this.registerCommand("arenastats", new ArenaStatsCommand());
        this.registerCommand("arenaleaderboard", new ArenaLeaderboardCommand());
        this.registerCommand("arenaannounce", new ArenaAnnounceCommand());
        this.registerCommand("arenalock", new ArenaLockCommand());
        this.registerCommand("arenareload", new ArenaReloadCommand());
        this.registerCommand("setarenaexit", new SetArenaExitCommand());
        this.registerCommand("arenaduel", new ArenaDuelCommand());
        this.registerCommand("arenawins", new ArenaWinsCommand());
        this.registerCommand("leavearena", new me.zeronull.spawnarena.commands.impl.LeaveArenaCommand());
        this.registerCommand("arenadrop", new me.zeronull.spawnarena.commands.impl.ArenaDropCommand());
        this.registerCommand("arenabet", new me.zeronull.spawnarena.commands.impl.ArenaBetCommand());
        this.registerCommand("arenasetup", new me.zeronull.spawnarena.commands.impl.ArenaSetupCommand());
        if (Arena.ArenaUtils.WORLD_EDIT_SUPPORT) {
            ArenaWizard.startTimeouts();
            // Undo pastes from arena setups that never finished (the server stopped mid-setup)
            Threads.globalLater(ArenaWizard::restoreLeftovers, 40L);
        }
    }

    private void registerCommand(final String cmdName, final CommandExecutor cmd) {
        final PluginCommand command = super.getCommand(cmdName);

        command.setExecutor(cmd);

        if (cmd instanceof TabCompleter)
            command.setTabCompleter((TabCompleter) cmd);
    }

    @Override
    public void onDisable() {
        this.shutdownArenas();
        me.zeronull.spawnarena.config.ConfigHandler.getInstance().flushPreFights();
        LagOuts.unwatchAll();
        if (statsManager != null) statsManager.close();
    }

    private void shutdownArenas() {
        if (arenas == null || arenas.isEmpty())
            return;

        for (final Arena arena : arenas) {
            new ArrayList<>(arena.getFights()).forEach(fight -> {
                // Including fights still sending players in: some may already have been snapshotted
                if (fight.getState() == FightState.IN_FIGHT || fight.getState() == FightState.PRE_FIGHT_START)
                    fight.endFight();
            });
        }

        CrystalPvp.cleanupAll();
        Arena.ArenaUtils.kickOutLingeringPlayers();
    }
}
