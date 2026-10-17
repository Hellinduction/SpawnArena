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
 * /arenadrop — staff with spawnarena.allowdropinfight switch dropping items mid-fight on or off for themselves.
 * Off until they turn it on, so having the permission (ops do by default) never drops things by accident.
 */
public final class ArenaDropCommand implements CommandExecutor {
    private static final NamespacedKey KEY = new NamespacedKey(SpawnArena.INSTANCE, "drop_in_fight");

    /** Whether this player may drop items while fighting: the permission, and switched on. */
    public static boolean mayDropInFight(final Player player) {
        return player.hasPermission("spawnarena.allowdropinfight")
                && player.getPersistentDataContainer().has(KEY, PersistentDataType.BYTE);
    }

    @Override
    public boolean onCommand(@NotNull final CommandSender sender, @NotNull final Command command, @NotNull final String label, final String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("You must be a player to run this command!");
            return true;
        }
        if (player.getPersistentDataContainer().has(KEY, PersistentDataType.BYTE)) {
            player.getPersistentDataContainer().remove(KEY);
            player.sendMessage(ChatColor.YELLOW + "You can't drop items in arena fights now (like everyone else). Run /arenadrop to turn it back on.");
        } else {
            player.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
            player.sendMessage(ChatColor.GREEN + "You can drop items in arena fights now. Run /arenadrop again to turn it off.");
        }
        return true;
    }
}
