# SpawnArena

An arena plugin at the spawn, originally developed for the VIVSMP Minecraft server. Updated for LeafMC/Paper 1.21.11.

# How to build the jar

Requires **JDK 21** or newer (in IntelliJ: Settings → Build Tools → Maven → Runner → JRE).

Use the following maven command...
`mvn clean package`

Or use the **Build SpawnArena** run configuration in IntelliJ.

Put `target/SpawnArena.jar` on the server. Ignore `target/original-SpawnArena.jar` — that one has no libraries bundled.

Don't add `shade:shade` to the command: shading already runs during `package`, and running it again bundles every library twice.

# Server requirements

- Java 21+, Paper/LeafMC 1.21.11
- WorldGuard, plus WorldEdit **or** FastAsyncWorldEdit (detected automatically)
- Optional: PlaceholderAPI (stats placeholders), FancyHolograms (leaderboard holograms)
