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
5. Players join with `/joinarenaqueue <arena>` (an NPC can run it for them). `/togglearenamode <arena>` switches between one fight at a time (SINGLE) and several at once (MULTI; for crystal PvP each match is fought in its own copy of the arena: on Folia see below), and `/togglearenamode <arena> ffa` makes it a free-for-all. A free-for-all has no wins: kill streaks of 5, 15, 25... and 10, 20, 30... pay instead (`ffaStreakReward` and `ffaStreakRewardTens` in the arena's file), each streak once in a while per player, with each player killed counting once per streak.
6. Sumo arenas' menu (`/joinarenaqueue <arena> menu`) also has **Practice vs Bot**: a match against a bot only that player sees (Easy, Medium, Hard, Cheater or Combo), with real player knockback and movement. It counts for nothing but a "bots beaten" count. `/arenatotems <arena> <n>` limits how many totems can save each fighter per fight (0 = no limit).
7. Leaderboards: `/arenaleaderboard spawn <arena> <stat>` where you're standing. Stats: `wins`, `streak` (current win streak), `best_streak`, `daily_streak` (the best win streak each player reached today: it starts again at midnight), `elo`, `wlr` (W/L), `playtime`, and for free-for-alls `ffa_kills`, `ffa_deaths`, `ffa_kdr`, `ffa_streak` (the kill streaks people are on right now, live) `ffa_best_streak` and `ffa_daily_streak` (today's best kill streaks). Also `reward` (each player's countdown to their next win reward, or in a free-for-all the kill streak that pays next) and `stats` (the arena's totals).

Stats are kept in an H2 file in the plugin folder by default; `database.yml` switches to MySQL/MariaDB (several servers can share one database).

# Velocity bridge

`velocity/` is a separate plugin for a Velocity proxy: with it, arena win announcements go to every server on the network (`proxyBroadcast` in `settings.yml`). Build it with `mvn clean package` in that folder.

# Running on Folia

Everything works on Folia, MULTI crystal arenas and their viewing arena included. **Canvas** (a Folia fork) is the
recommended Folia server: according to its project it fires the teleport and respawn events and can create worlds while
running, both of which plain Folia can't. SpawnArena finds out by itself what the server can do and only works round
what's missing, so nothing needs setting either way (`foliaPatchedTeleportEvent: true` in `settings.yml` just stops it
watching for teleports, on a server you know fires the event for every one). What differs on plain Folia:

- **No teleport events.** Plain Folia never fires `PlayerTeleportEvent`. SpawnArena notices teleports itself, just after
  they happen (from the teleport the server sends the player), and handles them as it would the event: teleporting while
  queued takes you out of the queue, a teleport out of a fight counts, someone teleported into a running fight is put back.
  Where it would have stopped a teleport, the player is moved back straight away instead (it can't be stopped beforehand).
  If the server does fire the event for a teleport, that one isn't handled twice.
- **No new worlds while running.** A MULTI crystal arena's copies are made through the
  Worlds plugin (by TheNextLvl) if it's installed (it can make worlds on Folia), or must be made
  beforehand (`spawnarena_<arena>_<n>`, any terrain); missing ones are waited for for a minute after starting.
  `/crystalcopies` shows each copy (ready, a match on, being put back, or still waited for), looks for their worlds again,
  or copies the arena into one again.
- **No FastAsyncWorldEdit.** FAWE doesn't support Folia, so arenas are pasted and copied with plain WorldEdit, a chunk at a
  time on each chunk's own region thread (an arena can lie across regions). A crystal arena's reset after each fight then
  takes a few seconds, during which it isn't used.
- **The viewing arena** (MULTI crystal matches merged into the real arena) updates every other tick instead of every tick:
  what it shows is read on the threads that own it and handed over.
- **No `/reload`.** Restart the server to update the plugin.

LeafMC isn't Folia: it fires teleport events and runs FAWE, so none of the above applies there.
