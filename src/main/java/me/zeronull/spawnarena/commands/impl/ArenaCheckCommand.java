package me.zeronull.spawnarena.commands.impl;

import me.zeronull.spawnarena.CheatChecks;
import me.zeronull.spawnarena.Recording;
import me.zeronull.spawnarena.SpawnArena;
import me.zeronull.spawnarena.Threads;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * /arenacheck top [days]: every player recorded (in the last few days, or ever), the most suspicious first.
 * /arenacheck &lt;player&gt; [days] — looks back over their recorded sumo matches (all of them, or the last few days')
 * for signs of cheating, against everyone else's ({@link CheatChecks}). Off the main thread; the result in chat (hover
 * a line for what it measures), and in full, every match, in plugins/SpawnArena/checks/.
 */
public final class ArenaCheckCommand implements CommandExecutor, TabCompleter {
    /** What's usual is worked out from this many of the newest recordings, and kept for a while */
    private static final int BASELINE_RECORDINGS = 5000;
    private static final long BASELINE_KEPT_MS = 6 * 60 * 60 * 1000L;
    private static final int SCANNED_RECORDINGS = 50000;

    /** What's usual, for each kind of arena (kept for a few hours) */
    private static volatile CheatChecks.Baselines baseline;

    /** These matches by kind of arena (see CheatChecks#kind), the kind with the most first. */
    private static Map<String, List<CheatChecks.Match>> byKind(final List<CheatChecks.Match> matches) {
        final Map<String, List<CheatChecks.Match>> byKind = new java.util.HashMap<>();
        for (final CheatChecks.Match m : matches)
            byKind.computeIfAbsent(m.kind, k -> new ArrayList<>()).add(m);
        final Map<String, List<CheatChecks.Match>> sorted = new java.util.LinkedHashMap<>();
        byKind.entrySet().stream().sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    /** One summary per kind of arena they've played, each against that kind's baseline. */
    private static List<CheatChecks.Summary> summaries(final List<CheatChecks.Match> matches, final CheatChecks.Baselines usual) {
        final List<CheatChecks.Summary> out = new ArrayList<>();
        for (final Map.Entry<String, List<CheatChecks.Match>> e : byKind(matches).entrySet())
            out.add(new CheatChecks.Summary(e.getValue(), usual.of(e.getKey())));
        return out;
    }

    private static CheatChecks.Verdict worst(final List<CheatChecks.Summary> summaries) {
        CheatChecks.Verdict worst = CheatChecks.Verdict.FINE;
        for (final CheatChecks.Summary s : summaries)
            if (s.overall() == CheatChecks.Verdict.SUSPICIOUS)
                return CheatChecks.Verdict.SUSPICIOUS;
            else if (s.overall() == CheatChecks.Verdict.LOOK)
                worst = CheatChecks.Verdict.LOOK;
        return worst;
    }
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§cUsage: /arenacheck <player> [days]  §7or  §c/arenacheck top [days]  §7(the most suspicious players)");
            return true;
        }
        int days = 0;
        if (args.length >= 2) {
            try {
                days = Integer.parseInt(args[1]);
            } catch (final NumberFormatException e) {
                sender.sendMessage("§c'" + args[1] + "' isn't a number of days.");
                return true;
            }
            if (days < 1) {
                sender.sendMessage("§cAt least 1 day (or leave it out for all of their matches).");
                return true;
            }
        }
        if (!RUNNING.compareAndSet(false, true)) {
            sender.sendMessage("§eA check is already running: try again once it's done.");
            return true;
        }
        final String name = args[0];
        if (name.equalsIgnoreCase("top")) {
            final int forDays = days;
            sender.sendMessage("§7Checking every recorded player" + (days > 0 ? " from the last " + days + " day" + (days == 1 ? "" : "s") : "") + "...");
            Threads.async(() -> {
                try {
                    this.top(sender, forDays);
                } catch (final Throwable t) {
                    SpawnArena.INSTANCE.getLogger().log(Level.WARNING, "/arenacheck top failed", t);
                    tell(sender, MiniMessage.miniMessage().deserialize("<red>The check failed: <msg>", Placeholder.unparsed("msg", String.valueOf(t))));
                } finally {
                    RUNNING.set(false);
                }
            });
            return true;
        }
        final Player online = Bukkit.getPlayerExact(name);
        final UUID known = online == null ? null : online.getUniqueId();
        final int forDays = days;
        sender.sendMessage("§7Checking " + name + "'s recorded matches" + (days > 0 ? " from the last " + days + " day" + (days == 1 ? "" : "s") : "") + "...");
        Threads.async(() -> {
            try {
                this.check(sender, name, known, forDays);
            } catch (final Throwable t) {
                SpawnArena.INSTANCE.getLogger().log(Level.WARNING, "/arenacheck failed", t);
                tell(sender, MiniMessage.miniMessage().deserialize("<red>The check failed: <msg>", Placeholder.unparsed("msg", String.valueOf(t))));
            } finally {
                RUNNING.set(false);
            }
        });
        return true;
    }

    private void check(final CommandSender sender, final String name, UUID uuid, final int days) {
        final long started = System.currentTimeMillis();
        final File root = new File(SpawnArena.INSTANCE.getDataFolder(), "recordings");
        final List<File> files = CheatChecks.recordings(root, SCANNED_RECORDINGS);
        if (files.isEmpty()) {
            tell(sender, MiniMessage.miniMessage().deserialize("<red>No recorded matches yet (sumo, spleef and crystal matches between players are recorded)."));
            return;
        }

        // What's usual in each kind of arena, from the newest recordings (kept for a few hours)
        CheatChecks.Baselines usual = baseline;
        if (usual == null || System.currentTimeMillis() - usual.builtAt > BASELINE_KEPT_MS) {
            final List<CheatChecks.Match> everyone = new ArrayList<>();
            for (final File f : files.subList(0, Math.min(BASELINE_RECORDINGS, files.size())))
                everyone.addAll(analyseAll(f, null));
            usual = CheatChecks.baselines(everyone);
            baseline = usual;
        }

        // Theirs: by UUID (they may have changed name), found from the name if they're not online
        final long since = days > 0 ? System.currentTimeMillis() - days * 86_400_000L : 0;
        final List<CheatChecks.Match> theirs = new ArrayList<>();
        String shownName = name;
        for (final File f : files) {
            final Recording header;
            try {
                header = Recording.header(f);
            } catch (final IOException e) {
                continue;
            }
            if (header.started < since)
                break; // newest first: the rest are older
            Recording.Fighter them = null;
            for (final Recording.Fighter x : header.fighters)
                if (uuid != null ? x.uuid.equals(uuid) : x.name.equalsIgnoreCase(name))
                    them = x;
            if (them == null)
                continue;
            if (uuid == null) {
                uuid = them.uuid;
                shownName = them.name;
            } else if (theirs.isEmpty())
                shownName = them.name;
            theirs.addAll(analyseAll(f, uuid));
        }
        if (theirs.isEmpty()) {
            tell(sender, MiniMessage.miniMessage().deserialize("<red>No recorded matches for <name>" + (days > 0 ? " in that time" : "")
                    + ". <gray>(Only sumo, spleef and crystal matches between players are recorded.)", Placeholder.unparsed("name", name)));
            return;
        }

        // Each kind of arena they've played on its own, against others playing it
        final List<CheatChecks.Summary> summaries = summaries(theirs, usual);
        final CheatChecks.Verdict overall = worst(summaries);
        final SimpleDateFormat day = new SimpleDateFormat("d MMM", Locale.ENGLISH);
        long first = Long.MAX_VALUE, last = 0;
        for (final CheatChecks.Match m : theirs) {
            first = Math.min(first, m.rec.started);
            last = Math.max(last, m.rec.started);
        }
        final File report = this.writeReport(shownName, theirs, summaries, usual);

        final MiniMessage mm = MiniMessage.miniMessage();
        final List<Component> out = new ArrayList<>();
        out.add(mm.deserialize("<gold><bold>Checks for <name></bold> <gray>(<n> match<s>, <from> to <to>)",
                Placeholder.unparsed("name", shownName), Placeholder.unparsed("n", String.format("%,d", theirs.size())),
                Placeholder.unparsed("s", theirs.size() == 1 ? "" : "es"), Placeholder.unparsed("from", day.format(new Date(first))),
                Placeholder.unparsed("to", day.format(new Date(last)))));
        for (final CheatChecks.Summary s : summaries) {
            final CheatChecks.Baseline theirKind = usual.of(s.kind);
            out.add(mm.deserialize("<aqua><kind></aqua> <gray>(<n> match<s>; against <players> players' <all>)",
                    Placeholder.unparsed("kind", CheatChecks.kindTitle(s.kind)), Placeholder.unparsed("n", String.format("%,d", s.matches.size())),
                    Placeholder.unparsed("s", s.matches.size() == 1 ? "" : "es"), Placeholder.unparsed("players", String.valueOf(theirKind.players)),
                    Placeholder.unparsed("all", String.format("%,d", theirKind.matches) + (theirKind.matches == 1 ? " match" : " matches"))));
            for (final CheatChecks.Line l : s.lines())
                out.add(mm.deserialize("<colour><mark> <white><check>:</white> <gray><text>",
                                Placeholder.parsed("colour", colour(l.verdict())), Placeholder.unparsed("mark", mark(l.verdict())),
                                Placeholder.unparsed("check", l.check()), Placeholder.unparsed("text", l.text()))
                        .hoverEvent(l.explain().isEmpty() ? null : net.kyori.adventure.text.event.HoverEvent.showText(Component.text(l.explain()))));
        }
        // The oddest of all their matches, each against its own kind's baseline
        final List<Map.Entry<CheatChecks.Match, String>> oddest = oddest(summaries, usual, 3);
        if (!oddest.isEmpty()) {
            out.add(mm.deserialize("<gray>Oddest matches:"));
            final SimpleDateFormat when = new SimpleDateFormat("d MMM HH:mm", Locale.ENGLISH);
            for (final Map.Entry<CheatChecks.Match, String> e : oddest) {
                final CheatChecks.Match m = e.getKey();
                out.add(mm.deserialize(" <dark_gray>-</dark_gray> <white><when></white> <gray>vs <opp><variant>: <yellow><why>",
                                Placeholder.unparsed("when", when.format(new Date(m.rec.started))),
                                Placeholder.unparsed("opp", String.join(" & ", m.opponents())),
                                Placeholder.unparsed("variant", m.variant == me.zeronull.spawnarena.SumoVariant.CLASSIC ? "" : " (" + m.variant.title + ")"),
                                Placeholder.unparsed("why", e.getValue()))
                        .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("Click to watch it\nrecordings/" + ArenaReplayCommand.path(m.rec.file))))
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/arenareplay file " + ArenaReplayCommand.path(m.rec.file))));
            }
        }
        out.add(mm.deserialize(switch (overall) {
            case SUSPICIOUS -> "<red><bold>Suspicious.</bold> <gray>Worth watching them, or a closer look at the matches above.";
            case LOOK -> "<yellow><bold>Worth a closer look,</bold> <gray>though one-offs happen to real players too.";
            default -> "<green><bold>Nothing suspicious.</bold> <gray>(Some cheats can't be seen from a recording: this clears no one for good.)";
        }));
        out.add(mm.deserialize("<dark_gray>Every match: plugins/SpawnArena/checks/<file> <gray>(took <secs>s)",
                Placeholder.unparsed("file", report == null ? "(couldn't write it)" : report.getName()),
                Placeholder.unparsed("secs", String.format("%.1f", (System.currentTimeMillis() - started) / 1000.0))));
        for (final Component c : out)
            tell(sender, c);
    }

    /** Their oddest matches of every kind, oddest first, each against its kind's baseline. */
    private static List<Map.Entry<CheatChecks.Match, String>> oddest(final List<CheatChecks.Summary> summaries, final CheatChecks.Baselines usual, final int count) {
        final List<Object[]> scored = new ArrayList<>(); // {match, why, score}
        for (final CheatChecks.Summary s : summaries)
            for (final CheatChecks.Match m : s.matches) {
                final Map.Entry<Integer, String> o = CheatChecks.Summary.oddness(m, usual.of(s.kind));
                if (o.getKey() > 0)
                    scored.add(new Object[]{m, o.getValue(), o.getKey()});
            }
        scored.sort((a, b) -> (int) b[2] != (int) a[2] ? Integer.compare((int) b[2], (int) a[2])
                : Long.compare(((CheatChecks.Match) b[0]).rec.started, ((CheatChecks.Match) a[0]).rec.started));
        final List<Map.Entry<CheatChecks.Match, String>> out = new ArrayList<>();
        for (int i = 0; i < Math.min(count, scored.size()); i++)
            out.add(Map.entry((CheatChecks.Match) scored.get(i)[0], (String) scored.get(i)[1]));
        return out;
    }

    /** How many players /arenacheck top lists, and how many matches they need to be ranked */
    private static final int TOP_SHOWN = 10, TOP_MIN_MATCHES = 3;

    /** Every player recorded (in the last few days, or ever), the most suspicious first: name, why, click for theirs. */
    private void top(final CommandSender sender, final int days) {
        final long started = System.currentTimeMillis();
        final File root = new File(SpawnArena.INSTANCE.getDataFolder(), "recordings");
        final List<File> files = CheatChecks.recordings(root, SCANNED_RECORDINGS);
        final long since = days > 0 ? System.currentTimeMillis() - days * 86_400_000L : 0;
        final List<CheatChecks.Match> all = new ArrayList<>();
        final Map<UUID, List<CheatChecks.Match>> byPlayer = new java.util.HashMap<>();
        final Map<UUID, String> names = new java.util.HashMap<>();
        for (final File f : files) {
            final List<CheatChecks.Match> in = analyseAll(f, null);
            if (!in.isEmpty() && in.get(0).rec.started < since)
                break; // newest first: the rest are older
            all.addAll(in);
            for (final CheatChecks.Match m : in) {
                byPlayer.computeIfAbsent(m.f.uuid, x -> new ArrayList<>()).add(m);
                names.putIfAbsent(m.f.uuid, m.f.name); // (their newest name)
            }
        }
        if (all.isEmpty()) {
            tell(sender, MiniMessage.miniMessage().deserialize("<red>No recorded matches" + (days > 0 ? " in that time" : " yet") + "."));
            return;
        }
        // What's usual: from everyone's (as for one player), kept for later checks too if it's from everything
        CheatChecks.Baselines usual = baseline;
        if (usual == null || System.currentTimeMillis() - usual.builtAt > BASELINE_KEPT_MS) {
            usual = CheatChecks.baselines(all);
            if (days == 0)
                baseline = usual;
        }
        final List<Object[]> ranked = new ArrayList<>(); // {name, matches, summaries, score}
        for (final Map.Entry<UUID, List<CheatChecks.Match>> e : byPlayer.entrySet()) {
            if (e.getValue().size() < TOP_MIN_MATCHES)
                continue;
            // Each kind of arena they've played against its own baseline, added up
            final List<CheatChecks.Summary> theirs = summaries(e.getValue(), usual);
            int score = 0;
            for (final CheatChecks.Summary s : theirs)
                score += s.suspicion();
            if (score >= 5) // (one long hit or so is nothing)
                ranked.add(new Object[]{names.get(e.getKey()), e.getValue().size(), theirs, score});
        }
        ranked.sort((a, b) -> Integer.compare((int) b[3], (int) a[3]));
        final MiniMessage mm = MiniMessage.miniMessage();
        tell(sender, mm.deserialize("<gold><bold>Most suspicious</bold> <gray>(<players> players with " + TOP_MIN_MATCHES + "+ of <matches> matches<when>)",
                Placeholder.unparsed("players", String.valueOf(byPlayer.values().stream().filter(v -> v.size() >= TOP_MIN_MATCHES).count())),
                Placeholder.unparsed("matches", String.format("%,d", usual.matches)),
                Placeholder.unparsed("when", days > 0 ? ", last " + days + " day" + (days == 1 ? "" : "s") : "")));
        if (ranked.isEmpty()) {
            tell(sender, mm.deserialize("<green>Nobody stands out. <gray>(Some cheats can't be seen from a recording.)"));
            return;
        }
        for (int i = 0; i < Math.min(TOP_SHOWN, ranked.size()); i++) {
            final Object[] r = ranked.get(i);
            @SuppressWarnings("unchecked") final List<CheatChecks.Summary> theirs = (List<CheatChecks.Summary>) r[2];
            final String who = (String) r[0];
            final List<String> why = new ArrayList<>();
            for (final CheatChecks.Summary s : theirs)
                if (!s.reasons().isEmpty())
                    why.add(theirs.size() > 1 ? CheatChecks.kindTitle(s.kind) + ": " + s.reasons() : s.reasons());
            final String reasons = String.join(" | ", why);
            tell(sender, mm.deserialize("<gray><n>.</gray> " + colour(worst(theirs)) + "<who><reset> <dark_gray>(<count> matches)</dark_gray> <gray><why>",
                            Placeholder.unparsed("n", String.valueOf(i + 1)),
                            Placeholder.unparsed("who", who),
                            Placeholder.unparsed("count", String.format("%,d", (int) r[1])),
                            Placeholder.unparsed("why", reasons.isEmpty() ? "a few odd matches, nothing more" : reasons))
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("Click for " + who + "'s checks")))
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/arenacheck " + who + (days > 0 ? " " + days : ""))));
        }
        tell(sender, mm.deserialize("<dark_gray>Click a name for their checks. <gray>(took <secs>s)",
                Placeholder.unparsed("secs", String.format("%.1f", (System.currentTimeMillis() - started) / 1000.0))));
    }

    /** Analyses one recording's fighters (just this one, if given), letting go of the frames after. */
    private static List<CheatChecks.Match> analyseAll(final File file, final UUID only) {
        final List<CheatChecks.Match> out = new ArrayList<>();
        try {
            final Recording rec = Recording.read(file);
            for (final Recording.Fighter f : rec.fighters)
                if (only == null || f.uuid.equals(only))
                    out.add(CheatChecks.analyse(rec, f));
            rec.dropFrames();
        } catch (final IOException | RuntimeException e) {
            // A recording cut short (the server stopping as it was saved): skipped
        }
        return out;
    }

    private File writeReport(final String name, final List<CheatChecks.Match> matches, final List<CheatChecks.Summary> summaries,
                             final CheatChecks.Baselines usual) {
        final File dir = new File(SpawnArena.INSTANCE.getDataFolder(), "checks");
        if (!dir.isDirectory() && !dir.mkdirs())
            return null;
        final File file = new File(dir, name.replaceAll("[^A-Za-z0-9_.-]", "") + "-" + new SimpleDateFormat("yyyy-MM-dd-HHmm").format(new Date()) + ".txt");
        final SimpleDateFormat when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);
        try (PrintWriter w = new PrintWriter(file, StandardCharsets.UTF_8)) {
            w.println("Checks for " + name + ": " + matches.size() + " recorded matches");
            w.println("Overall: " + worst(summaries));
            for (final CheatChecks.Summary s : summaries) {
                final CheatChecks.Baseline theirKind = usual.of(s.kind);
                w.println();
                w.println(CheatChecks.kindTitle(s.kind) + ": " + s.matches.size() + " matches, against " + theirKind.players + " players' "
                        + theirKind.matches + " matches there");
                for (final CheatChecks.Line l : s.lines()) {
                    w.println(String.format("%-11s %-14s %s", l.verdict(), l.check(), l.text()));
                    if (!l.explain().isEmpty())
                        w.println(String.format("%26s%s", "", l.explain()));
                }
            }
            w.println();
            w.println("Every match (newest first). reach: hits, longest, at " + 3.1 + "+ | kb: knockback taken of what was sent (typical) |"
                    + " steps: view turns that fit a mouse | back: ticks sprinting backwards | top: fastest b/s | odd: why it stands out");
            final Set<String> seen = new LinkedHashSet<>();
            for (final CheatChecks.Match m : matches) {
                if (!seen.add(m.rec.file.getPath()))
                    continue;
                float maxReach = 0;
                for (final float r : m.reach)
                    maxReach = Math.max(maxReach, r);
                final Map.Entry<Integer, String> odd = CheatChecks.Summary.oddness(m, usual.of(m.kind));
                w.println(String.format("%s  %-11s %-24s %-9s %s  reach %3d %.2f %d  kb %s  steps %s  back %d  top %.1f  %s%s",
                        when.format(new Date(m.rec.started)), CheatChecks.kindTitle(m.kind), "vs " + String.join(" & ", m.opponents()), m.variant.name(),
                        m.f.won ? "won " : "lost", m.reach.size(), maxReach, m.longHits,
                        m.kbRatio.isEmpty() ? "-" : Math.round(100 * median(m.kbRatio)) + "%",
                        m.rotationFit < 0 ? "-" : Math.round(100 * m.rotationFit) + "%", m.sprintBackTicks, m.topSpeed,
                        odd.getKey() > 0 ? "odd: " + odd.getValue() + "  " : "", m.rec.file.getParentFile().getName() + "/" + m.rec.file.getName()));
            }
        } catch (final IOException e) {
            SpawnArena.INSTANCE.getLogger().log(Level.WARNING, "Couldn't write the check report", e);
            return null;
        }
        return file;
    }

    private static float median(final List<Float> values) {
        final List<Float> sorted = new ArrayList<>(values);
        sorted.sort(null);
        return sorted.get(sorted.size() / 2);
    }

    private static String colour(final CheatChecks.Verdict v) {
        return switch (v) {
            case FINE -> "<green>";
            case LOOK -> "<yellow>";
            case SUSPICIOUS -> "<red>";
            case INFO -> "<aqua>";
            case SKIPPED -> "<dark_gray>";
        };
    }

    private static String mark(final CheatChecks.Verdict v) {
        return switch (v) {
            case FINE -> "✔";
            case LOOK -> "?";
            case SUSPICIOUS -> "✖";
            case INFO -> "•";
            case SKIPPED -> "-";
        };
    }

    /** Sends from any thread: on the player's own (or the global one for the console). */
    private static void tell(final CommandSender sender, final Component message) {
        if (sender instanceof Player p)
            Threads.entity(p, () -> p.sendMessage(message));
        else
            Threads.global(() -> sender.sendMessage(message));
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (args.length == 1) {
            final List<String> names = new ArrayList<>();
            if ("top".startsWith(args[0].toLowerCase(Locale.ROOT)))
                names.add("top");
            for (final Player p : Bukkit.getOnlinePlayers())
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)))
                    names.add(p.getName());
            return names;
        }
        if (args.length == 2)
            return List.of("1", "7", "30");
        return List.of();
    }
}
