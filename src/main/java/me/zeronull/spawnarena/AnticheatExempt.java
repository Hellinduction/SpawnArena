package me.zeronull.spawnarena;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ice Rink and Moon Gravity players kept from being flagged by the anticheat, whichever is installed (both if both are):
 * Vulcan's movement flags are simply cancelled for them (see {@link VulcanExempt}); bunnyAC, below.
 *
 * <p>MULTI Ice Rink: the ice is only shown to that match's players, so bunnyAC (which sees the real floor) flags them for
 * sliding; and it flags Moon Gravity's jumps too. Until bunnyAC can exempt players from single checks, they're taken out
 * of bunnyAC altogether for the match
 * ({@code acwhitelist}) and put back the moment they're out ({@code acunwhitelist}).
 *
 * <p>bunnyAC asks the console to confirm each of those with a one-time code ("Confirm within 30 seconds: exempt
 * &lt;uuid&gt; /bunnyac confirm &lt;code&gt;"), so the console's output is watched for that line and the code sent back.
 * Players staff had already exempted are left alone.
 *
 * <p>Nobody may stay exempt by mistake, so who SpawnArena exempted is written to disk (in one go, before the command is
 * even sent) and only taken off once their removal has been confirmed to bunnyAC. A removal that isn't confirmed is
 * retried; on shutdown everyone is put back there and then; and on the next start anyone still on that list who is
 * still in bunnyAC's own list is put back.
 */
public final class AnticheatExempt {
    private static final Pattern CONFIRM = Pattern.compile(
            "Confirm within \\d+ seconds: (exempt|remove exemption for) ([0-9a-fA-F-]{36}) /bunnyac confirm ([0-9a-zA-Z]+)");
    /** Gap between console commands, so bunnyAC's command rate limit isn't hit (a 2v2 starts four at once). */
    private static final long COMMAND_GAP_TICKS = 10L;
    /** A removal not confirmed in this long is sent again, up to MAX_TRIES times. */
    private static final long RETRY_MILLIS = 5_000;
    private static final int MAX_TRIES = 5;

    /** Players SpawnArena exempted, or is still putting back: on disk the whole time. */
    private static final Set<UUID> OURS = ConcurrentHashMap.newKeySet();
    /** Of those, the ones bunnyAC confirmed (or a crash left in its list): only they need putting back. */
    private static final Set<UUID> EXEMPTED = ConcurrentHashMap.newKeySet();
    /** Being put back: uuid -> {tries so far, when the last one was sent}. */
    private static final Map<UUID, long[]> REMOVING = new ConcurrentHashMap<>();
    /** Sent, waiting for bunnyAC's confirm line: uuid -> true for exempting, false for removing. */
    private static final Map<UUID, Boolean> AWAITING = new ConcurrentHashMap<>();
    /** Confirm lines read off the console, to send back: uuid -> code. */
    private static final Map<UUID, String> CODES = new ConcurrentHashMap<>();
    /** What to send next: {uuid, true = exempt / false = remove}. */
    private static final Deque<Object[]> COMMANDS = new ArrayDeque<>();
    private static volatile Appender appender;
    private static volatile boolean warnedRefused;

    private AnticheatExempt() {}

    private static boolean bunnyEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("bunnyAC");
    }

    private static File file() {
        return new File(SpawnArena.INSTANCE.getDataFolder(), "anticheat-exempt.txt");
    }

    /** On enable: starts watching the console and puts back anyone a crash or restart left exempt. Never throws. */
    public static void start() {
        try {
            begin();
        } catch (final Throwable e) {
            appender = null;
            SpawnArena.INSTANCE.getLogger().log(Level.WARNING, "bunnyAC exemptions for Ice Rink are off", e);
        }
    }

    private static void begin() {
        // Read first, whatever else happens: these must be put back
        try {
            if (file().exists())
                for (final String line : Files.readAllLines(file().toPath(), StandardCharsets.UTF_8))
                    if (!line.isBlank())
                        OURS.add(UUID.fromString(line.trim()));
        } catch (final Exception e) {
            SpawnArena.INSTANCE.getLogger().log(Level.SEVERE, "Couldn't read " + file() + ": players in it may still be "
                    + "exempt from bunnyAC; check them with /acunwhitelist", e);
        }
        if (!bunnyEnabled()) {
            if (!OURS.isEmpty())
                SpawnArena.INSTANCE.getLogger().warning("bunnyAC isn't running: " + OURS.size() + " player(s) SpawnArena exempted "
                        + "from it are kept in " + file().getName() + " and put back once it is.");
            return;
        }
        try {
            final Appender a = new Appender();
            a.start();
            ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).addAppender(a);
            appender = a;
        } catch (final Throwable e) {
            SpawnArena.INSTANCE.getLogger().log(Level.WARNING, "Couldn't watch the console for bunnyAC's confirmations; "
                    + "Ice Rink players in MULTI arenas won't be exempted from it", e);
            return;
        }
        Threads.globalTimer(task -> pump(), COMMAND_GAP_TICKS, COMMAND_GAP_TICKS);

        // Left over from a crash or restart mid-match. bunnyAC's own list is right at startup (it's what it just
        // loaded): anyone not on it isn't exempt any more; anyone on it is put back once bunnyAC has finished loading
        Threads.globalLater(() -> {
            for (final UUID uuid : new ArrayList<>(OURS)) {
                if (inBunnyList(uuid)) {
                    EXEMPTED.add(uuid);
                    remove(uuid);
                }
                else
                    OURS.remove(uuid);
            }
            save();
        }, 100L);
    }

    /** On disable: everyone still exempt is put back now; anyone that can't be finished stays on disk for the next start. Never throws. */
    public static void stop() {
        try {
            end();
        } catch (final Throwable e) {
            appender = null;
        }
    }

    private static void end() {
        final Appender a = appender;
        if (a == null)
            return;
        for (final UUID uuid : new ArrayList<>(OURS)) {
            if (!EXEMPTED.contains(uuid))
                continue; // never confirmed: nothing to undo (its code, if any, runs out by itself)
            try {
                send(uuid, false);
                confirmIfReady(uuid);
            } catch (final Throwable e) {
                // Folia may not allow commands from here: kept on disk, put back on the next start
            }
        }
        appender = null;
        ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).removeAppender(a);
        a.stop();
    }

    /** A player starting a MULTI Ice Rink or a Moon Gravity match: out of bunnyAC until {@link #remove}. */
    public static void add(final Player player) {
        final UUID uuid = player.getUniqueId();
        if (SpawnArena.settings != null && !SpawnArena.settings.iceRinkAnticheatExempt)
            return;
        // Whichever anticheat is installed (both if both are)
        VulcanExempt.add(player);
        if (appender == null || !bunnyEnabled() || (SpawnArena.settings != null && !SpawnArena.settings.iceRinkAnticheatExempt)
                || OURS.contains(uuid) || inBunnyList(uuid))
            return;
        OURS.add(uuid);
        if (!save()) {
            OURS.remove(uuid); // not on disk, so never exempted
            return;
        }
        queue(uuid, true);
    }

    /** They're out of the match (or never made it in): back under bunnyAC. Does nothing for anyone SpawnArena didn't exempt. */
    public static void remove(final UUID uuid) {
        VulcanExempt.remove(uuid);
        if (!OURS.contains(uuid) || REMOVING.containsKey(uuid))
            return;
        // An exemption still waiting to be sent, or sent but not confirmed yet, is simply dropped (its code runs out)
        synchronized (COMMANDS) {
            COMMANDS.removeIf(c -> c[0].equals(uuid) && Boolean.TRUE.equals(c[1]));
        }
        AWAITING.remove(uuid, true);
        if (CODES.containsKey(uuid) && !EXEMPTED.contains(uuid))
            CODES.remove(uuid);
        if (!EXEMPTED.contains(uuid)) {
            OURS.remove(uuid);
            save();
            return;
        }
        REMOVING.put(uuid, new long[]{0, 0});
        queue(uuid, false);
    }

    private static void queue(final UUID uuid, final boolean exempt) {
        synchronized (COMMANDS) {
            COMMANDS.add(new Object[]{uuid, exempt});
        }
    }

    /** Every COMMAND_GAP_TICKS: one console command, then any codes that came back, then retries of unconfirmed removals. */
    private static void pump() {
        if (appender == null || !bunnyEnabled())
            return;
        final Object[] next;
        synchronized (COMMANDS) {
            next = COMMANDS.poll();
        }
        if (next != null) {
            final UUID uuid = (UUID) next[0];
            final boolean exempt = (Boolean) next[1];
            if (exempt && REMOVING.containsKey(uuid))
                return; // out of the match before it was sent
            send(uuid, exempt);
            confirmIfReady(uuid);
        }
        for (final UUID uuid : new ArrayList<>(CODES.keySet()))
            confirmIfReady(uuid);

        final long now = System.currentTimeMillis();
        for (final Map.Entry<UUID, long[]> e : REMOVING.entrySet()) {
            final long[] state = e.getValue();
            if (state[1] == 0 || now - state[1] < RETRY_MILLIS)
                continue;
            if (state[0] >= MAX_TRIES) {
                REMOVING.remove(e.getKey()); // stays in OURS (and on disk): tried again on the next start
                final String name = Bukkit.getOfflinePlayer(e.getKey()).getName();
                SpawnArena.INSTANCE.getLogger().severe("Couldn't put " + (name == null ? e.getKey() : name) + " back under bunnyAC after "
                        + MAX_TRIES + " tries. Run: acunwhitelist " + e.getKey() + " (they stay in " + file().getName()
                        + " and are tried again on the next start)");
                continue;
            }
            state[1] = 0;
            queue(e.getKey(), false);
        }
    }

    private static void send(final UUID uuid, final boolean exempt) {
        AWAITING.put(uuid, exempt);
        if (!exempt) {
            final long[] state = REMOVING.computeIfAbsent(uuid, k -> new long[]{0, 0});
            state[0]++;
            state[1] = System.currentTimeMillis();
        }
        final Player online = Bukkit.getPlayer(uuid);
        if (exempt && online == null)
            return; // acwhitelist needs them online: nothing to undo
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                exempt ? "acwhitelist " + online.getName() : "acunwhitelist " + uuid);
    }

    /** Sends back the code bunnyAC asked for, if its line has come in; a confirmed removal comes off the list. */
    private static void confirmIfReady(final UUID uuid) {
        final String code = CODES.remove(uuid);
        if (code == null)
            return;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "bunnyac confirm " + code);
        if (REMOVING.remove(uuid) != null) {
            EXEMPTED.remove(uuid);
            OURS.remove(uuid);
            save();
        } else {
            EXEMPTED.add(uuid);
        }
    }

    /** In bunnyAC's own exemption list (exempted by staff, or by us earlier). */
    private static boolean inBunnyList(final UUID uuid) {
        final File list = new File(SpawnArena.INSTANCE.getDataFolder().getParentFile(), "bunnyAC/exemptions.list");
        try {
            if (list.exists())
                for (final String line : Files.readAllLines(list.toPath(), StandardCharsets.UTF_8))
                    if (line.trim().equalsIgnoreCase(uuid.toString()))
                        return true;
        } catch (final Exception ignored) {
            // can't tell: treat them like anyone else
        }
        return false;
    }

    /** Writes the list now, swapped in whole (a crash mid-write can't lose it). Returns false if it couldn't be written. */
    private static synchronized boolean save() {
        final List<String> lines = new ArrayList<>();
        for (final UUID uuid : OURS)
            lines.add(uuid.toString());
        try {
            Files.createDirectories(file().getParentFile().toPath());
            final File tmp = new File(file().getParentFile(), file().getName() + ".tmp");
            Files.write(tmp.toPath(), lines, StandardCharsets.UTF_8);
            try {
                Files.move(tmp.toPath(), file().toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), file().toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (final Exception e) {
            SpawnArena.INSTANCE.getLogger().log(Level.SEVERE, "Couldn't save " + file(), e);
            return false;
        }
    }

    /** Reads bunnyAC's confirm line off the console, for the players we asked about; the code is sent back by the pump. */
    private static final class Appender extends AbstractAppender {
        Appender() {
            super("SpawnArena-bunnyAC-confirm", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(final LogEvent event) {
            final String text = event.getMessage() == null ? null : event.getMessage().getFormattedMessage();
            if (text == null)
                return;
            // bunnyAC refusing the console (its require-authenticated-admin setting): say so, once, rather than nothing
            if (text.contains("Administrative access denied") && !AWAITING.isEmpty() && !warnedRefused) {
                warnedRefused = true;
                SpawnArena.INSTANCE.getLogger().warning("bunnyAC refused SpawnArena's exemption commands from the console, so Ice "
                        + "Rink and Moon Gravity players aren't exempted from it. Set its 'security: require-authenticated-admin' "
                        + "to false (in bunnyAC's panel if its config comes from there).");
            }
            if (!text.contains("/bunnyac confirm"))
                return;
            final Matcher m = CONFIRM.matcher(text.replaceAll("§.", ""));
            if (!m.find())
                return;
            final UUID uuid;
            try {
                uuid = UUID.fromString(m.group(2));
            } catch (final IllegalArgumentException e) {
                return;
            }
            // Only the ones we asked for, and only the action we asked for (never a confirmation meant for staff)
            if (AWAITING.remove(uuid, m.group(1).equals("exempt")))
                CODES.put(uuid, m.group(3));
        }
    }
}
