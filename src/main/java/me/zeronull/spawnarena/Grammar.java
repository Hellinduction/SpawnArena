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

    /** "st", "nd", "rd" or "th" for this number: 1st, 2nd, 3rd, 11th, 100th, 1,001st. */
    public static String ordinalSuffix(final long n) {
        final long tens = Math.abs(n) % 100;
        if (tens >= 11 && tens <= 13)
            return "th";
        return switch ((int) (Math.abs(n) % 10)) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }
}
