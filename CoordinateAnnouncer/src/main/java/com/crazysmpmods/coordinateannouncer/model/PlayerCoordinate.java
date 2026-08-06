package com.crazysmpmods.coordinateannouncer.model;

import org.bukkit.Location;

/**
 * A snapshot of a player's position at a moment in time.
 * Used both for live announcements and cached offline-position lookups.
 *
 * Note: formatting logic lives in AnnouncementManager.formatLine() — this
 * record is intentionally data-only (no formatting methods) to keep a
 * single source of truth for display format.
 */
public record PlayerCoordinate(
        String username,
        int x, int y, int z,
        Dimension dimension,
        boolean online
) {

    public static PlayerCoordinate live(String username, Location loc) {
        return new PlayerCoordinate(
                username,
                loc.getBlockX(),
                loc.getBlockY(),
                loc.getBlockZ(),
                Dimension.fromWorld(loc.getWorld()),
                true
        );
    }
}
