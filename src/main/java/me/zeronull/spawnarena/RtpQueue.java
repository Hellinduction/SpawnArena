package me.zeronull.spawnarena;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * The "1v1" plugin's random-teleport queue (/1v1, /rtpqueue: Ps1v1Queue): anyone in it is taken out of it as a SpawnArena
 * match takes them, so it can't pair them up and teleport them away mid-match. Through its own Main1v1#getFila() and
 * Fila1v1#isQueued / #leave, found by reflection: nothing happens without that plugin, or if it ever changes.
 */
public final class RtpQueue {
    private static volatile boolean broken;
    private static volatile Method getFila, isQueued, leave;

    private RtpQueue() {
    }

    /** Takes them out of its queue if they're in it (any thread), telling them so. */
    public static void takeOut(final Player player) {
        if (broken || player == null)
            return;
        final Plugin plugin = Bukkit.getPluginManager().getPlugin("1v1");
        if (plugin == null || !plugin.isEnabled())
            return;
        try {
            if (getFila == null) {
                getFila = plugin.getClass().getMethod("getFila");
                final Class<?> fila = getFila.getReturnType();
                isQueued = fila.getMethod("isQueued", java.util.UUID.class);
                leave = fila.getMethod("leave", Player.class);
            }
            final Object fila = getFila.invoke(plugin);
            if (fila == null || !(boolean) isQueued.invoke(fila, player.getUniqueId()))
                return;
            leave.invoke(fila, player);
            player.sendMessage("§7You were taken out of the /1v1 queue for your match.");
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            broken = true;
            SpawnArena.INSTANCE.getLogger().warning("Couldn't take players out of the 1v1 plugin's queue at match start: " + e);
        }
    }
}
