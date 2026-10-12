package me.zeronull.spawnarena;

public enum FightMode {
    ONE_V_ONE("1v1", 1),
    TWO_V_TWO("2v2", 2);

    private final String label;
    private final int teamSize;

    FightMode(final String label, final int teamSize) {
        this.label = label;
        this.teamSize = teamSize;
    }

    public String label() {
        return this.label;
    }

    public int teamSize() {
        return this.teamSize;
    }

    /** Players needed to start a match. */
    public int players() {
        return this.teamSize * 2;
    }

    public static FightMode fromLabel(final String label) {
        for (final FightMode mode : values())
            if (mode.label.equalsIgnoreCase(label))
                return mode;
        return null;
    }
}
