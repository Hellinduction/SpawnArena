package me.zeronull.spawnarena.commands.impl;

import me.zeronull.spawnarena.MatchFavourites;
import me.zeronull.spawnarena.MatchHistoryMenu;
import me.zeronull.spawnarena.MatchIndex;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /matches: your own recorded matches, to watch back (see {@link MatchHistoryMenu}).
 * /matches favourite &lt;day/file&gt;: favourites one of your own (or takes it off your favourites), as the button under
 * a replay does.
 */
public final class MatchesCommand implements CommandExecutor {
    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players have matches to watch.");
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("favourite")) {
            MatchIndex.Match match = null;
            for (final MatchIndex.Match m : MatchIndex.of(player.getUniqueId(), null))
                if (MatchFavourites.key(m.file()).equals(args[1]))
                    match = m;
            if (match == null) {
                player.sendMessage(ChatColor.RED + "That isn't one of your recorded matches.");
                return true;
            }
            final Boolean now = MatchFavourites.toggle(player.getUniqueId(), match.file());
            player.sendMessage(now == null ? ChatColor.RED + "You can have " + MatchFavourites.MAX + " favourites at most: unfavourite one first."
                    : now ? ChatColor.YELLOW + "★ Added to your favourites. " + ChatColor.GRAY + "(/matches to see them)"
                    : ChatColor.GRAY + "Taken off your favourites.");
            return true;
        }
        final String problem = MatchHistoryMenu.open(player);
        if (problem != null)
            player.sendMessage(ChatColor.RED + problem);
        return true;
    }
}
