package com.crazysmpmods.coordinateannouncer.model;

import java.util.UUID;

/**
 * A player tracked by the custom-players announcement list.
 * Immutable record: UUID for stability across name changes,
 * name for human-readable display.
 */
public record CustomPlayer(UUID uuid, String name) {
    @Override
    public String toString() {
        return name + " (" + uuid + ")";
    }
}
