package me.zeronull.spawnarena;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * A match recording read back (see {@link MatchRecorder#write} for the file). Plain Java: nothing here needs the server.
 * {@link #header} reads just who fought, without the frames.
 */
public final class Recording {
    public final File file;
    public final int format;
    public final long started;
    public final String arena;
    /** The random sumo variant's name (e.g. "ICE"), or "" for none */
    public final String variant;
    public final int lengthMs;
    public final boolean duel;
    public final List<Fighter> fighters;
    /** Format 3: a spleef match; a free-for-all round (each fighter a team of their own) */
    public final boolean spleef, freeForAll;
    /** The centre positions are from (the middle of the two spawn points), as it was then */
    public final double centreX, centreY, centreZ;
    /** Format 3: blocks changed during the match, in order (empty unless frames were read) */
    public final List<BlockChange> blocks = new ArrayList<>();

    /**
     * A block changed: when (ms since the start), where (from the centre's block: its coordinates rounded down), by which
     * fighter (their place in the list, -1 none), how ({@link #BY_HAND} or {@link #BY_SNOWBALL}), what it became.
     */
    public record BlockChange(int ms, int x, int y, int z, int by, int how, String data) {}

    public static final int BY_HAND = 0, BY_SNOWBALL = 1;

    /** One fighter: who, and (unless only the header was read) their frames, a tick each. */
    public static final class Fighter {
        public UUID uuid;
        public String name;
        public int team;
        public boolean won;
        public int elo, wins, losses, winStreak, bestWinStreak, fightSeconds;
        public int pingAtStart, pingAverage;
        public String client;
        public int frames;
        public int[] time;
        public float[] x, y, z, yaw, pitch;
        public byte[] keys, state;
        /** Format 2 (format 1: the average ping, and nothing more) */
        public short[] ping;
        public byte[] more;
        /** Format 2: the velocity sent that tick (knockback), or null; which fighter they landed a hit on, or -1 */
        public float[][] velocity;
        public byte[] landedOn;

        /** A Bedrock player (Geyser): turns, clicks and moves differently, so some checks don't apply. */
        public boolean bedrock() {
            return (this.client != null && this.client.toLowerCase(java.util.Locale.ROOT).contains("geyser"))
                    || (this.uuid != null && this.uuid.getMostSignificantBits() == 0); // Floodgate's
        }

        public boolean has(final int frame, final int stateBit) {
            return (this.state[frame] & stateBit) != 0;
        }

        public boolean hasMore(final int frame, final int moreBit) {
            return this.more != null && (this.more[frame] & moreBit) != 0;
        }

        public boolean holding(final int frame, final int keyBit) {
            return (this.keys[frame] & keyBit) != 0;
        }
    }

    // State bits
    public static final int GROUND = 1, SPRINTING = 2, SNEAKING = 4, SWUNG = 8, LANDED = 16, HURT = 32;
    // Key bits
    public static final int FORWARD = 1, BACK = 2, LEFT = 4, RIGHT = 8, JUMP = 16, SNEAK = 32, SPRINT = 64;
    // Format 2's "more" bits
    public static final int VELOCITY = 1, DIGGING = 2, SHOT = 4, TELEPORTED = 8, LANDED_ON = 16;

    private Recording(final File file, final int format, final long started, final String arena, final String variant,
                      final int lengthMs, final boolean duel, final List<Fighter> fighters, final int flags, final double[] centre) {
        this.file = file;
        this.format = format;
        this.started = started;
        this.arena = arena;
        this.variant = variant;
        this.lengthMs = lengthMs;
        this.duel = duel;
        this.fighters = fighters;
        this.spleef = (flags & 1) != 0;
        this.freeForAll = (flags & 2) != 0;
        this.centreX = centre[0];
        this.centreY = centre[1];
        this.centreZ = centre[2];
    }

    public static Recording read(final File file) throws IOException {
        return read(file, true);
    }

    /** Just who fought and when: much quicker, for finding someone's matches. */
    public static Recording header(final File file) throws IOException {
        return read(file, false);
    }

    private static Recording read(final File file, final boolean frames) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(file), 8192), 16384))) {
            final byte[] magic = new byte[5];
            in.readFully(magic);
            if (!"SAREC".equals(new String(magic, java.nio.charset.StandardCharsets.US_ASCII)))
                throw new IOException("not a recording");
            final int format = in.readInt();
            if (format < 1 || format > 3)
                throw new IOException("unknown format " + format);
            final long started = in.readLong();
            final String arena = in.readUTF();
            final String variant = in.readUTF();
            final double[] centre = {in.readDouble(), in.readDouble(), in.readDouble()};
            final int length = in.readInt();
            final boolean duel = in.readByte() != 0;
            in.readByte(); // winning team (each fighter says whether they won)
            final int flags = format >= 3 ? in.readUnsignedByte() : 0;
            final int count = in.readUnsignedByte();
            final List<Fighter> fighters = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                final Fighter f = new Fighter();
                f.uuid = new UUID(in.readLong(), in.readLong());
                f.name = in.readUTF();
                f.team = in.readByte();
                f.won = in.readByte() != 0;
                f.elo = in.readInt();
                f.wins = in.readInt();
                f.losses = in.readInt();
                f.winStreak = in.readInt();
                f.bestWinStreak = in.readInt();
                f.fightSeconds = in.readInt();
                final int levels = in.readUnsignedByte();
                for (int l = 0; l < levels; l++) {
                    in.readUTF();
                    in.readInt();
                }
                f.pingAtStart = in.readInt();
                f.pingAverage = in.readInt();
                f.client = in.readUTF();
                fighters.add(f);
            }
            final Recording rec = new Recording(file, format, started, arena, variant, length, duel, fighters, flags, centre);
            if (!frames)
                return rec;
            for (final Fighter f : fighters)
                readFrames(in, f, format);
            if (format >= 3) {
                final int n = in.readInt();
                if (n < 0 || n > 1_000_000)
                    throw new IOException("bad block count " + n);
                for (int i = 0; i < n; i++)
                    rec.blocks.add(new BlockChange(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readByte(), in.readByte(), in.readUTF()));
            }
            return rec;
        } catch (final EOFException e) {
            throw new IOException("cut short", e);
        }
    }

    private static void readFrames(final DataInputStream in, final Fighter f, final int format) throws IOException {
        final int n = in.readInt();
        if (n < 0 || n > 20 * 60 * 60)
            throw new IOException("bad frame count " + n);
        f.frames = n;
        f.time = new int[n];
        f.x = new float[n];
        f.y = new float[n];
        f.z = new float[n];
        f.yaw = new float[n];
        f.pitch = new float[n];
        f.keys = new byte[n];
        f.state = new byte[n];
        f.ping = new short[n];
        if (format >= 2) {
            f.more = new byte[n];
            f.velocity = new float[n][];
            f.landedOn = new byte[n];
        }
        for (int i = 0; i < n; i++) {
            f.time[i] = in.readInt();
            f.x[i] = in.readFloat();
            f.y[i] = in.readFloat();
            f.z[i] = in.readFloat();
            f.yaw[i] = in.readFloat();
            f.pitch[i] = in.readFloat();
            f.keys[i] = in.readByte();
            f.state[i] = in.readByte();
            if (format >= 2) {
                f.ping[i] = in.readShort();
                final byte more = in.readByte();
                f.more[i] = more;
                if ((more & VELOCITY) != 0)
                    f.velocity[i] = new float[]{in.readFloat(), in.readFloat(), in.readFloat()};
                f.landedOn[i] = (more & LANDED_ON) != 0 ? in.readByte() : -1;
            } else {
                f.ping[i] = (short) Math.min(f.pingAverage, Short.MAX_VALUE);
            }
        }
    }

    public SumoVariant variant() {
        if (this.variant.isEmpty())
            return SumoVariant.CLASSIC;
        try {
            return SumoVariant.valueOf(this.variant);
        } catch (final IllegalArgumentException e) {
            return SumoVariant.CLASSIC;
        }
    }

    /** Lets go of every fighter's frames (once analysed), keeping who fought: thousands can then be held at once. */
    public void dropFrames() {
        this.blocks.clear();
        for (final Fighter f : this.fighters) {
            f.time = null;
            f.x = f.y = f.z = f.yaw = f.pitch = null;
            f.keys = f.state = f.more = f.landedOn = null;
            f.ping = null;
            f.velocity = null;
        }
    }

    /** Who fought whom: "A vs B", "A & B vs C & D", or a free-for-all's "A vs B vs C". */
    public String versus() {
        final java.util.Map<Integer, List<String>> teams = new java.util.TreeMap<>();
        for (final Fighter f : this.fighters)
            teams.computeIfAbsent(f.team, t -> new ArrayList<>()).add(f.name);
        final List<String> sides = new ArrayList<>();
        for (final List<String> team : teams.values())
            sides.add(String.join(" & ", team));
        return String.join(" vs ", sides);
    }

    /** Names of those someone fought against, together: "A & B" (a team), or a free-for-all's "A, B, C". */
    public static String opponents(final List<String> names, final boolean freeForAll) {
        return String.join(freeForAll ? ", " : " & ", names);
    }

    public Fighter fighter(final UUID uuid) {
        for (final Fighter f : this.fighters)
            if (f.uuid.equals(uuid))
                return f;
        return null;
    }
}
