package me.zeronull.spawnarena;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;

/** Kept for existing callers; see {@link Threads}. */
public final class FoliaUtils {
    private FoliaUtils() {}

    public static boolean isFolia() {
        return Threads.FOLIA;
    }

    /** Teleports from any thread (instantly on Paper, on the player's own thread on Folia). */
    public static CompletableFuture<Boolean> teleport(final Player player, final Location location) {
        return Threads.teleport(player, location);
    }
}
