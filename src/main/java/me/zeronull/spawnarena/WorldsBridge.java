package me.zeronull.spawnarena;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Makes (or loads) a world through the Worlds plugin (TheNextLvl's "Worlds"), which can do that while plain Folia runs,
 * where the server's own world creation is refused. Through its public API, by reflection, so SpawnArena doesn't need it.
 * The world gets SpawnArena's own generator and biomes, and is marked for Worlds not to load by itself on starting:
 * SpawnArena loads it again each time with them (Worlds wouldn't know how to make its empty chunks).
 */
final class WorldsBridge {
    private static final String PROVIDER = "net.thenextlvl.worlds.api.WorldsProvider";

    private WorldsBridge() {}

    /** The Worlds plugin, enabled and with the API this uses, or null. */
    private static Plugin worlds() {
        final Plugin plugin = Bukkit.getPluginManager().getPlugin("Worlds");
        if (plugin == null || !plugin.isEnabled())
            return null;
        try {
            return Class.forName(PROVIDER, false, plugin.getClass().getClassLoader()).isInstance(plugin) ? plugin : null;
        } catch (final ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    static boolean available() {
        return worlds() != null;
    }

    /**
     * Makes the world {@code name} in the server's world folder (loads it if its folder is already there) with this
     * generator and biomes. On the global thread on Folia. Fails if Worlds isn't there or refuses.
     */
    static CompletableFuture<World> create(final String name, final ChunkGenerator generator, final BiomeProvider biomes) {
        final Plugin plugin = worlds();
        if (plugin == null)
            return CompletableFuture.failedFuture(new IllegalStateException("the Worlds plugin isn't installed"));
        try {
            final ClassLoader loader = plugin.getClass().getClassLoader();
            final Class<?> provider = Class.forName(PROVIDER, true, loader);
            final Class<?> builderType = Class.forName("net.thenextlvl.worlds.api.level.Level$Builder", true, loader);
            final Class<?> levelType = Class.forName("net.thenextlvl.worlds.api.level.Level", true, loader);
            final Class<?> viewType = Class.forName("net.thenextlvl.worlds.api.view.LevelView", true, loader);
            final Path directory = Bukkit.getWorldContainer().toPath().resolve(name);

            // A world that's there already (e.g. made on Paper before) is read as it is; otherwise a new one
            Object builder = null;
            if (Files.isRegularFile(directory.resolve("level.dat"))) {
                final Object view = method(provider, "levelView", viewType).invoke(plugin);
                builder = ((Optional<?>) method(viewType, "read", Optional.class, Path.class).invoke(view, directory)).orElse(null);
            }
            if (builder == null)
                builder = method(provider, "levelBuilder", builderType, Path.class).invoke(plugin, directory);

            method(builderType, "name", builderType, String.class).invoke(builder, name);
            // Known as minecraft:<name>, as Paper names a world it makes (Worlds would say worlds:<name>), so commands
            // like /execute in, and anything else naming it, work the same on either
            if (net.kyori.adventure.key.Key.parseable("minecraft:" + name))
                method(builderType, "key", builderType, net.kyori.adventure.key.Key.class)
                        .invoke(builder, net.kyori.adventure.key.Key.key("minecraft", name));
            method(builderType, "chunkGenerator", builderType, ChunkGenerator.class).invoke(builder, generator);
            method(builderType, "biomeProvider", builderType, BiomeProvider.class).invoke(builder, biomes);
            method(builderType, "structures", builderType, Boolean.class).invoke(builder, Boolean.FALSE);
            method(builderType, "enabled", builderType, net.kyori.adventure.util.TriState.class).invoke(builder, net.kyori.adventure.util.TriState.FALSE);
            final Object level = method(builderType, "build", levelType).invoke(builder);
            @SuppressWarnings("unchecked")
            final CompletableFuture<World> made = (CompletableFuture<World>) method(levelType, "createAsync", CompletableFuture.class).invoke(level);
            return made;
        } catch (final Throwable e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * One of the API's methods, looked up on its own: plain reflection (getMethod) would load the types of all of the
     * interface's methods, and WorldsProvider has one returning a PerWorlds type, missing where PerWorlds isn't installed.
     */
    private static MethodHandle method(final Class<?> owner, final String name, final Class<?> returns, final Class<?>... parameters)
            throws ReflectiveOperationException {
        return MethodHandles.publicLookup().findVirtual(owner, name, MethodType.methodType(returns, parameters));
    }
}
