package me.zeronull.spawnarena.commands.impl;

import me.zeronull.spawnarena.WinMessagePrefs;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** /arenawins — toggle seeing other players' arena win announcements in chat. */
public final class ArenaWinsCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("You must be a player to run this command!");
            return true;
        }

        final boolean on = WinMessagePrefs.toggle(player);
        me.zeronull.spawnarena.ProxyBridge.sendPreference(player); // so it applies network-wide too
        player.sendMessage(on
                ? ChatColor.GREEN + "You'll now see arena win announcements in chat."
                : ChatColor.YELLOW + "You won't see other players' arena win announcements anymore, except near an arena. Your own results still show. Run /arenawins to turn them back on.");
        return true;
    }
}
