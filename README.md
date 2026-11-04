# SpawnArena

An arena plugin at the spawn, made for the AquaMC Minecraft server, but not tied to it: any server can build it and use it. Built for LeafMC/Paper 1.21.11, and runs on Folia (see below for what differs there).

# How to build the jar

Requires **JDK 21** or newer (in IntelliJ: Settings → Build Tools → Maven → Runner → JRE).

Use the following maven command...
`mvn clean package`

Or use the **Build SpawnArena** run configuration in IntelliJ.

Put `target/SpawnArena.jar` on the server. Ignore `target/original-SpawnArena.jar` — that one has no libraries bundled.

Don't add `shade:shade` to the command: shading already runs during `package`, and running it again bundles every library twice.

# Server requirements

- Java 21+, Paper/LeafMC 1.21.11 (or Folia 1.21.11)
- WorldGuard, plus WorldEdit **or** FastAsyncWorldEdit (detected automatically)
- Optional, each used only if it's there:
  - Vault + an economy plugin: win rewards and `/arenabet`
  - PlaceholderAPI: stats placeholders (`%spawnarena_...%`)
  - FancyHolograms: leaderboard, reward and stats holograms (`/arenaleaderboard`)
  - FancyNpcs: NPCs standing in a MULTI crystal arena are copied into its copies
  - TAB: fight outlines keep their colour with TAB's nametags
  - LuckPerms: the top-Elo suffix (`topEloSuffix`)
  - Simple Voice Chat: separate fights in one arena can't hear each other; spectators of MULTI crystal matches hear the fighters
  - Vulcan or bunnyAC: Ice Rink and Moon Gravity players aren't flagged for sliding or jumping
  - SKFriends: friends are kept together in 2v2 teams
  - A combat logger: MULTI crystal copy worlds can be added to its list of worlds it leaves alone (`combatLoggerWorlds...` in `settings.yml`; off unless that file and list exist)

# Getting started

1. Put the jar in `plugins/` with WorldGuard and WorldEdit (or FAWE), start the server, and look through `plugins/SpawnArena/settings.yml`. Every option is explained in it.
2. `spawnWorlds` in `settings.yml`: the worlds players must be in to queue, duel and set up arenas. Empty (the default) means every world.
3. Build the arena, stand in it and run `/arenasetup`: the wizard asks for the type (normal, sumo, crystal PvP...), the spawn points, the region and the options, and saves a copy of the build to paste back after fights.
4. `/setarenaexit <arena>` where people should be sent if they're in the arena during a fight.
5. Players join with `/joinarenaqueue <arena>` (an NPC can run it for them). `/togglearenamode <arena>` switches between one fight at a time (SINGLE) and several at once (MULTI; for crystal PvP each match is fought in its own copy of the arena, on Paper/Leaf only), and `/togglearenamode <arena> ffa` makes it a free-for-all.
6. Leaderboards: `/arenaleaderboard spawn <arena> <stat>` where you're standing. Stats: `wins`, `streak` (current win streak), `best_streak`, `elo`, `wlr` (W/L), `playtime`, and for free-for-alls `ffa_kills`, `ffa_deaths`, `ffa_kdr`, `ffa_streak` (the kill streaks people are on right now, live) and `ffa_best_streak`. Also `reward` (each player's countdown to their next win reward) and `stats` (the arena's totals).

Stats are kept in an H2 file in the plugin folder by default; `database.yml` switches to MySQL/MariaDB (several servers can share one database).

# Velocity bridge

`velocity/` is a separate plugin for a Velocity proxy: with it, arena win announcements go to every server on the network (`proxyBroadcast` in `settings.yml`). Build it with `mvn clean package` in that folder.

# Running on Folia

Everything works on Folia, with these differences:

- **No teleport events.** Folia never fires `PlayerTeleportEvent`, so these do nothing there:
  - teleporting while queued doesn't take you out of the queue;
  - a command or plugin teleport out of the arena mid-fight doesn't count as a loss (leaving the arena on foot or by pearl is still stopped);
  - an anticheat setback into or over lava (or a sumo arena's water) can't be redirected as it happens. Instead it's spotted from the player's steps, and touching the lava or water right after still puts them back where they were.
- **No FastAsyncWorldEdit.** FAWE doesn't support Folia, so arenas are pasted with plain WorldEdit on the arena's own region thread. A crystal arena's reset after each fight then takes a few seconds, during which that part of the world is busy.
- **No `/reload`.** Restart the server to update the plugin.

LeafMC isn't Folia: it fires teleport events and runs FAWE, so none of the above applies there.
