# SpawnArena

An arena plugin at the spawn, made for the AquaMC Minecraft server. Built for LeafMC/Paper 1.21.11, and runs on Folia (see below for what differs there).

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
- Optional: Vault + an economy plugin (win rewards and `/arenabet`), PlaceholderAPI (stats placeholders), FancyHolograms (leaderboard and reward holograms)

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
