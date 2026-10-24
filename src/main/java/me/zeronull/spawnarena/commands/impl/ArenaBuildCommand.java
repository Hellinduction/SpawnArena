package me.zeronull.spawnarena.commands.impl;

import me.zeronull.spawnarena.SpawnArena;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

/**
 * /arenabuild — staff with spawnarena.arenabuild switch off, for themselves, what SpawnArena stops onlookers doing in
 * arenas: building in a crystal arena (even during someone else's fight), dropping items into an arena, opening
 * containers fighters placed, and pearling into a crystal arena. Off until they turn it on.
 */
public final class ArenaBuildCommand implements CommandExecutor {
    public static final String PERMISSION = "spawnarena.arenabuild";
    private static final NamespacedKey KEY = new NamespacedKey(SpawnArena.INSTANCE, "arena_build");

    /** Whether this player has the permission and has switched /arenabuild on. */
    public static boolean isOn(final Player player) {
        return player.hasPermission(PERMISSION) && player.getPersistentDataContainer().has(KEY, PersistentDataType.BYTE);
    }

    @Override
    public boolean onCommand(@NotNull final CommandSender sender, @NotNull final Command command, @NotNull final String label, final String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("You must be a player to run this command!");
            return true;
        }
        if (player.getPersistentDataContainer().has(KEY, PersistentDataType.BYTE)) {
            player.getPersistentDataContainer().remove(KEY);
            player.sendMessage(ChatColor.YELLOW + "Arena build mode off: arenas' onlooker rules apply to you again.");
        } else {
            player.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
            player.sendMessage(ChatColor.GREEN + "Arena build mode on: you can build in crystal arenas (even during fights), drop items"
                    + " into arenas, open containers fighters placed and pearl into crystal arenas. Run /arenabuild again to turn it off.");
        }
        return true;
    }
}
