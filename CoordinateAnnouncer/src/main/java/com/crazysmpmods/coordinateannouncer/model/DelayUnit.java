package com.crazysmpmods.coordinateannouncer.model;

/**
 * Time units supported by the delay setting.
 * Each unit converts to seconds for scheduler use.
 */
public enum DelayUnit {
    SECONDS,
    MINUTES,
    HOURS,
    DAYS;

    public long toSeconds(long value) {
        return switch (this) {
            case SECONDS -> value;
            case MINUTES -> value * 60L;
            case HOURS   -> value * 60L * 60L;
            case DAYS    -> value * 60L * 60L * 24L;
        };
    }

    public long toTicks(long value) {
        // 20 ticks per second in Bukkit
        return toSeconds(value) * 20L;
    }

    public String displayName() {
        return switch (this) {
            case SECONDS -> "Seconds";
            case MINUTES -> "Minutes";
            case HOURS   -> "Hours";
            case DAYS    -> "Days";
        };
    }

    public String shortName() {
        return switch (this) {
            case SECONDS -> "s";
            case MINUTES -> "m";
            case HOURS   -> "h";
            case DAYS    -> "d";
        };
    }

    public static DelayUnit fromString(String s) {
        if (s == null) return MINUTES;
        try {
            return DelayUnit.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return MINUTES;
        }
    }

    /**
     * Cycle to the next unit in the order: SECONDS → MINUTES → HOURS → DAYS → SECONDS.
     */
    public DelayUnit next() {
        DelayUnit[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }
}
