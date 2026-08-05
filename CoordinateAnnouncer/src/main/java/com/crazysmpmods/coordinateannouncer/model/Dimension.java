package com.crazysmpmods.coordinateannouncer.model;

import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The three vanilla Minecraft dimensions.
 */
public enum Dimension {
    OVERWORLD("Overworld"),
    NETHER("Nether"),
    THE_END("The End"),
    UNKNOWN("Unknown");

    private final String displayName;

    Dimension(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    @NotNull
    public static Dimension fromWorld(@Nullable World world) {
        if (world == null) return UNKNOWN;
        return switch (world.getEnvironment()) {
            case NORMAL -> OVERWORLD;
            case NETHER -> NETHER;
            case THE_END -> THE_END;
            default -> UNKNOWN;
        };
    }
}
