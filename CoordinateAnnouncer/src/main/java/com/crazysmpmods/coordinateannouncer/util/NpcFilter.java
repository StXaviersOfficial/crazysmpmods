package com.crazysmpmods.coordinateannouncer.util;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Filters out non-player "fake players" from announcements:
 *   - Citizens NPCs (have metadata "NPC")
 *   - Carpet mod fake players (have metadata "FakePlayer")
 *   - Players with names starting with "[NPC]" (common convention)
 *   - Players whose name equals a known NPC pattern
 *
 * If filterNpcs is false, this filter is bypassed.
 */
public final class NpcFilter {

    private NpcFilter() {}

    public static boolean isNpc(@NotNull Player p, boolean filterNpcs) {
        if (!filterNpcs) return false;

        // Citizens / Sentinel NPCs
        if (p.hasMetadata("NPC")) return true;

        // Carpet mod fake players
        if (p.hasMetadata("FakePlayer")) return true;

        // MyMobs / Shopkeepers: typically have metadata "shopkeeper"
        if (p.hasMetadata("shopkeeper")) return true;

        // Name-based heuristics
        String name = p.getName();
        if (name == null) return true;
        if (name.startsWith("[NPC]")) return true;
        if (name.startsWith("[FakePlayer]")) return true;

        // Players with empty or whitespace-only names (shouldn't happen but defensive)
        if (name.trim().isEmpty()) return true;

        return false;
    }

    /**
     * Lookup a UUID safely (returns null instead of throwing on malformed input).
     */
    public static UUID safeParseUuid(@NotNull String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Try to resolve a name → UUID.
     * Checks online players first (synchronous, fast).
     * For offline players, returns null — the caller should use
     * {@link #lookupUuidAsync(String, java.util.function.Consumer)} to avoid
     * blocking the main thread with a Mojang API call.
     */
    @Nullable
    public static UUID lookupUuid(@NotNull String name) {
        // Check online players (case-insensitive, fast)
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName() != null && p.getName().equalsIgnoreCase(name)) {
                return p.getUniqueId();
            }
        }
        // Check offline players who have logged in before (cached, fast on Paper)
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getName() != null && op.getName().equalsIgnoreCase(name)) {
                return op.getUniqueId();
            }
        }
        // Don't call Bukkit.getOfflinePlayer(String) here — that's a blocking
        // Mojang API web request. The async version below handles that case.
        return null;
    }

    /**
     * Asynchronously resolve a name → UUID, including the case where the player
     * has never logged in before (requires a Mojang API call).
     *
     * The callback is invoked on the MAIN thread with the UUID (or null if not found).
     */
    @SuppressWarnings("deprecation")
    public static void lookupUuidAsync(@NotNull String name,
                                        @NotNull java.util.function.Consumer<UUID> callback) {
        // Fast path: check online + cached offline first (sync)
        UUID fast = lookupUuid(name);
        if (fast != null) {
            callback.accept(fast);
            return;
        }
        // Slow path: run Bukkit.getOfflinePlayer(name) on a separate thread
        // (this hits Mojang API and can take 1-5 seconds)
        new Thread(() -> {
            try {
                Thread.currentThread().setName("CoordinateAnnouncer-UUIDLookup-" + name);
                OfflinePlayer op = Bukkit.getOfflinePlayer(name);
                UUID uuid = (op != null && (op.hasPlayedBefore() || op.isOnline()))
                        ? op.getUniqueId() : null;
                // If still null, the player doesn't exist
                // Schedule callback on main thread
                org.bukkit.Bukkit.getScheduler().runTask(
                        com.crazysmpmods.coordinateannouncer.CoordinateAnnouncer.getInstance(),
                        () -> callback.accept(uuid));
            } catch (Throwable t) {
                org.bukkit.Bukkit.getScheduler().runTask(
                        com.crazysmpmods.coordinateannouncer.CoordinateAnnouncer.getInstance(),
                        () -> callback.accept(null));
            }
        }, "CA-UUIDLookup").start();
    }
}

