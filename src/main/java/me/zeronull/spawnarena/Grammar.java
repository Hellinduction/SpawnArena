package me.zeronull.spawnarena;

/** Small English helpers for player-facing messages. */
public final class Grammar {
    private Grammar() {}

    /** "Steve's", "Chris'" */
    public static String possessive(final String name) {
        return name + (name.toLowerCase().endsWith("s") ? "'" : "'s");
    }

    /** Just the possessive ending for {@code name}: "'s" or "'". */
    public static String possessiveSuffix(final String name) {
        return name.toLowerCase().endsWith("s") ? "'" : "'s";
    }

    /** "1 hologram", "3 holograms" */
    public static String count(final long n, final String singular) {
        return n + " " + (n == 1 ? singular : singular + "s");
    }
}
