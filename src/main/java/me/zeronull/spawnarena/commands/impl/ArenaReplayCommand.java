package me.zeronull.spawnarena.commands.impl;

import me.zeronull.spawnarena.ArenaReplay;
import me.zeronull.spawnarena.CheatChecks;
import me.zeronull.spawnarena.Recording;
import me.zeronull.spawnarena.SpawnArena;
import me.zeronull.spawnarena.SumoVariant;
import me.zeronull.spawnarena.Threads;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * /arenareplay — watch a recorded sumo match back ({@link ArenaReplay}), shown to you alone:
 * <pre>
 * /arenareplay &lt;player&gt; [n]        their latest match (or the n-th latest)
 * /arenareplay list &lt;player&gt; [page]  their recent matches, each to click and watch
 * /arenareplay file &lt;day/file&gt;      a recording by its file (as /arenacheck lists them)
 * /arenareplay pause | play | restart | skip &lt;seconds&gt; | speed &lt;0.25-4&gt; | stop | back | controls
 * </pre>
 */
public final class ArenaReplayCommand implements CommandExecutor, TabCompleter {
    private static final int PER_PAGE = 10;
    private static final List<String> CONTROLS = List.of("pause", "play", "restart", "skip", "speed", "stop", "back", "controls");

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can watch replays.");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage("§cUsage: /arenareplay <player> [n], /arenareplay list <player> [page], or /arenareplay file <day/file>");
            return true;
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        if (CONTROLS.contains(sub))
            return this.control(player, sub, args);
        // Anyone can work the controls of a replay they're watching (/matches); watching others' matches is for staff,
        // and a player may only open a recording by file if they fought in it (the "Watch again" button)
        final boolean staff = player.hasPermission("spawnarena.arenareplay.others");
        if (!staff && !sub.equals("file")) {
            player.sendMessage("§cUse /matches to watch your own matches back.");
            return true;
        }
        if (sub.equals("list")) {
            if (args.length < 2) {
                player.sendMessage("§cUsage: /arenareplay list <player> [page]");
                return true;
            }
            final int page = args.length >= 3 ? number(args[2], 1) : 1;
            Threads.async(() -> this.list(player, args[1], Math.max(1, page)));
            return true;
        }
        if (sub.equals("file")) {
            if (args.length < 2) {
                player.sendMessage("§cUsage: /arenareplay file <day/file>");
                return true;
            }
            final File root = root();
            final File file = new File(root, args[1]);
            try {
                if (!file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator) || !file.isFile()) {
                    player.sendMessage("§cNo recording " + args[1] + ".");
                    return true;
                }
            } catch (final IOException e) {
                player.sendMessage("§cNo recording " + args[1] + ".");
                return true;
            }
            Threads.async(() -> {
                if (!staff) {
                    final Recording header;
                    try {
                        header = Recording.header(file);
                    } catch (final IOException e) {
                        tell(player, "<red>No recording " + esc(args[1]) + ".");
                        return;
                    }
                    if (header.fighter(player.getUniqueId()) == null) {
                        tell(player, "<red>You can only watch your own matches. <gray>(/matches)");
                        return;
                    }
                }
                watch(player, file);
            });
            return true;
        }
        final int n = args.length >= 2 ? number(args[1], 1) : 1;
        Threads.async(() -> {
            final List<Recording> theirs = matchesOf(args[0], n);
            if (theirs.size() < n) {
                tell(player, theirs.isEmpty() ? "<red>No recorded matches for " + esc(args[0]) + ". <gray>(Only sumo matches between players are recorded.)"
                        : "<red>" + esc(args[0]) + " only has " + theirs.size() + " recorded match" + (theirs.size() == 1 ? "" : "es") + ".");
                return;
            }
            watch(player, theirs.get(n - 1).file);
        });
        return true;
    }

    private boolean control(final Player player, final String sub, final String[] args) {
        final ArenaReplay replay = ArenaReplay.of(player);
        if (replay == null && !sub.equals("back")) {
            player.sendMessage("§cYou're not watching a replay. §7(/arenareplay <player> to watch one)");
            return true;
        }
        switch (sub) {
            case "pause" -> replay.setPaused(true);
            case "play" -> replay.setPaused(false);
            case "restart" -> replay.restart();
            case "skip" -> replay.skip(args.length >= 2 ? number(args[1], 5) : 5);
            case "speed" -> {
                double speed = 1;
                try {
                    speed = Double.parseDouble(args.length >= 2 ? args[1] : "1");
                } catch (final NumberFormatException ignored) {
                    // 1
                }
                replay.setSpeed(Math.max(0.1, Math.min(8, speed)));
                replay.controls();
            }
            case "stop" -> {
                replay.stop(true);
                player.sendMessage("§7Replay closed.");
            }
            case "back" -> {
                if (replay != null)
                    replay.stop(true);
                else
                    player.sendMessage("§cYou're not watching a replay.");
            }
            case "controls" -> replay.controls();
            default -> {
            }
        }
        if (sub.equals("pause") || sub.equals("play"))
            replay.controls();
        return true;
    }

    private static int number(final String s, final int otherwise) {
        try {
            return Integer.parseInt(s);
        } catch (final NumberFormatException e) {
            return otherwise;
        }
    }

    private static File root() {
        return new File(SpawnArena.INSTANCE.getDataFolder(), "recordings");
    }

    /** Their recorded matches, newest first (up to {@code want} of them). By who they are: names can change. */
    private static List<Recording> matchesOf(final String name, final int want) {
        final Player online = Bukkit.getPlayerExact(name);
        UUID uuid = online == null ? null : online.getUniqueId();
        final List<Recording> out = new ArrayList<>();
        for (final File f : CheatChecks.recordings(root(), 50000)) {
            final Recording header;
            try {
                header = Recording.header(f);
            } catch (final IOException e) {
                continue;
            }
            for (final Recording.Fighter x : header.fighters)
                if (uuid != null ? x.uuid.equals(uuid) : x.name.equalsIgnoreCase(name)) {
                    uuid = x.uuid;
                    out.add(header);
                    break;
                }
            if (out.size() >= want)
                break;
        }
        return out;
    }

    private void list(final Player player, final String name, final int page) {
        final List<Recording> theirs = matchesOf(name, page * PER_PAGE + 1);
        final int from = (page - 1) * PER_PAGE;
        if (theirs.size() <= from) {
            tell(player, theirs.isEmpty() ? "<red>No recorded matches for " + esc(name) + "." : "<red>No page " + page + ".");
            return;
        }
        // Who they are (found by name in their newest, if they're not online): their name may differ in older ones
        final Player online = Bukkit.getPlayerExact(name);
        UUID id = online == null ? null : online.getUniqueId();
        if (id == null)
            for (final Recording.Fighter x : theirs.get(0).fighters)
                if (x.name.equalsIgnoreCase(name))
                    id = x.uuid;
        final SimpleDateFormat when = new SimpleDateFormat("d MMM HH:mm", Locale.ENGLISH);
        // Which page this is, of how many (all their matches, from the match index once it's loaded)
        final int total = id != null && me.zeronull.spawnarena.MatchIndex.ready() ? me.zeronull.spawnarena.MatchIndex.of(id, null).size() : -1;
        final int pages = total < 0 ? -1 : Math.max(page, (total + PER_PAGE - 1) / PER_PAGE);
        final String where = pages < 0 ? "page " + page : "page " + page + " of " + pages + ", " + total + " match" + (total == 1 ? "" : "es");
        tell(player, "<gold>" + esc(name) + "'s recorded matches</gold> <gray>(" + where + "; newest first; click one to watch it)");
        for (int i = from; i < Math.min(theirs.size(), from + PER_PAGE); i++) {
            final Recording r = theirs.get(i);
            Recording.Fighter them = id == null ? null : r.fighter(id);
            if (them == null)
                them = r.fighters.get(0);
            final List<String> against = new ArrayList<>();
            for (final Recording.Fighter x : r.fighters)
                if (x.team != them.team)
                    against.add(x.name);
            final SumoVariant v = r.variant();
            final int s = r.lengthMs / 1000;
            tell(player, "<click:run_command:/arenareplay file " + path(r.file) + "><hover:show_text:'<gray>Watch it'>"
                    + "<dark_gray>#" + (i + 1) + "</dark_gray> <white>" + when.format(new Date(r.started)) + "</white> <gray>vs " + esc(String.join(" & ", against))
                    + (v == SumoVariant.CLASSIC ? "" : " (" + v.title + ")") + ", " + (them.won ? "<green>won</green>" : "<red>lost</red>")
                    + " <gray>(" + s / 60 + ":" + String.format("%02d", s % 60) + ") <aqua>[Watch]</aqua></hover></click>");
        }
        // Back and on, with where they are between
        final boolean more = theirs.size() > from + PER_PAGE;
        if (page > 1 || more)
            tell(player, (page > 1 ? "<click:run_command:/arenareplay list " + esc(name) + " " + (page - 1) + "><aqua>[« Back]</aqua></click> " : "")
                    + "<gray>Page " + page + (pages < 0 ? "" : " of " + pages) + "</gray>"
                    + (more ? " <click:run_command:/arenareplay list " + esc(name) + " " + (page + 1) + "><aqua>[More »]</aqua></click>" : ""));
    }

    /** day/file, as /arenareplay file takes it. */
    public static String path(final File recording) {
        return recording.getParentFile().getName() + "/" + recording.getName();
    }

    private static void watch(final Player player, final File file) {
        final Recording rec;
        try {
            rec = Recording.read(file);
        } catch (final IOException e) {
            tell(player, "<red>Couldn't read that recording (" + esc(e.getMessage()) + ").");
            return;
        }
        Threads.entity(player, () -> {
            // A player watching their own: not mid-fight, in a queue or away from spawn
            final String problem = player.hasPermission("spawnarena.arenareplay.others") ? null
                    : me.zeronull.spawnarena.MatchHistoryMenu.cantWatch(player, rec.arena);
            if (problem != null)
                player.sendMessage("§c" + problem);
            else
                ArenaReplay.start(player, rec);
        });
    }

    private static String esc(final String s) {
        return MiniMessage.miniMessage().escapeTags(s == null ? "" : s);
    }

    private static void tell(final Player p, final String miniMessage) {
        Threads.entity(p, () -> p.sendMessage(MiniMessage.miniMessage().deserialize(miniMessage)));
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command, final String label, final String[] args) {
        final List<String> out = new ArrayList<>();
        if (args.length == 1) {
            final List<String> options = new ArrayList<>(List.of("list", "file"));
            if (sender instanceof Player p && ArenaReplay.of(p) != null)
                options.addAll(CONTROLS);
            for (final Player p : Bukkit.getOnlinePlayers())
                options.add(p.getName());
            for (final String o : options)
                if (o.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)))
                    out.add(o);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("list")) {
            for (final Player p : Bukkit.getOnlinePlayers())
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                    out.add(p.getName());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("speed")) {
            for (final double s : ArenaReplay.SPEEDS)
                out.add(s == Math.floor(s) ? String.valueOf((int) s) : String.valueOf(s));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("skip"))
            out.addAll(List.of("-10", "-5", "5", "10"));
        return out;
    }
}
