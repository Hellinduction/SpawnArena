package me.zeronull.spawnarena;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

/** Per-player opt-out of arena win announcements in chat (saved on the player). */
public final class WinMessagePrefs {
    private static final NamespacedKey HIDE_KEY = new NamespacedKey(SpawnArena.INSTANCE, "hide_win_messages");

    private WinMessagePrefs() {}

    public static boolean wantsWinMessages(final Player player) {
        return !player.getPersistentDataContainer().has(HIDE_KEY, PersistentDataType.BYTE);
    }

    /** Flips the setting; returns the new state (true = seeing win messages). */
    public static boolean toggle(final Player player) {
        if (wantsWinMessages(player)) {
            player.getPersistentDataContainer().set(HIDE_KEY, PersistentDataType.BYTE, (byte) 1);
            return false;
        }
        player.getPersistentDataContainer().remove(HIDE_KEY);
        return true;
    }
}
