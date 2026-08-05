package com.crazysmpmods.coordinateannouncer.model;

import org.bukkit.Location;

/**
 * A snapshot of a player's position at a moment in time.
 * Used both for live announcements and cached offline-position lookups.
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

    public static PlayerCoordinate offline(String username, Location loc) {
        return new PlayerCoordinate(
                username,
                loc.getBlockX(),
                loc.getBlockY(),
                loc.getBlockZ(),
                Dimension.fromWorld(loc.getWorld()),
                false
        );
    }

    /**
     * Format: "20 28 -483 (Overworld)" — block coords + dimension name.
     */
    public String formatCoordinates() {
        return x + " " + y + " " + z + " " + dimension.displayName();
    }

    /**
     * Full display line for the announcement:
     *   Live:    "QuackPlayzYT → 20 28 -483 Overworld"
     *   Offline: "[OFFLINE] QuackPlayzYT → Last known: 20 28 -483 Overworld"
     */
    public String formatAnnouncementLine() {
        if (online) {
            return username + " → " + formatCoordinates();
        } else {
            return "[OFFLINE] " + username + " → Last known: " + formatCoordinates();
        }
    }
}
