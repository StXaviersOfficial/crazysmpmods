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
 *
 * Bedrock players (via Geyser+Floodgate) are NOT NPCs — they are real players
 * and pass through this filter. Use {@link BedrockDetector} to detect them
 * separately if needed.
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
     *
     * Handles Bedrock player prefix (Floodgate's ".") — strips it before lookup,
     * then re-checks with the prefix if the plain name doesn't match.
     */
    @Nullable
    public static UUID lookupUuid(@NotNull String name) {
        // Try the name as-is first (handles both Java players and Bedrock players
        // whose name already includes the Floodgate prefix)
        UUID direct = lookupUuidRaw(name);
        if (direct != null) return direct;

        // If the name has the Bedrock prefix, try without it
        if (BedrockDetector.hasBedrockPrefix(name)) {
            UUID stripped = lookupUuidRaw(BedrockDetector.stripBedrockPrefix(name));
            if (stripped != null) return stripped;
        }
        // If the name DOESN'T have the prefix, try WITH it (in case user typed
        // a Bedrock name without the prefix)
        if (!BedrockDetector.hasBedrockPrefix(name)) {
            String withPrefix = BedrockDetector.getBedrockPrefix() + name;
            UUID prefixed = lookupUuidRaw(withPrefix);
            if (prefixed != null) return prefixed;
        }
        return null;
    }

    @Nullable
    private static UUID lookupUuidRaw(@NotNull String name) {
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
        return null;
    }

    /**
     * Asynchronously resolve a name → UUID, including the case where the player
     * has never logged in before (requires a Mojang API call).
     *
     * The callback is invoked on the MAIN thread with the UUID (or null if not found).
     * Handles Bedrock prefix automatically.
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
        // Try both the plain name and the Bedrock-prefixed name.
        new Thread(() -> {
            try {
                Thread.currentThread().setName("CoordinateAnnouncer-UUIDLookup-" + name);
                UUID uuid = tryLookupWithBedrockVariants(name);
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

    @SuppressWarnings("deprecation")
    private static UUID tryLookupWithBedrockVariants(@NotNull String name) {
        // Try the name as-is
        UUID uuid = tryOfflinePlayer(name);
        if (uuid != null) return uuid;

        // Try with Bedrock prefix variants
        if (BedrockDetector.hasBedrockPrefix(name)) {
            // User typed ".QuackPlayzYT" — also try "QuackPlayzYT"
            uuid = tryOfflinePlayer(BedrockDetector.stripBedrockPrefix(name));
            if (uuid != null) return uuid;
        } else {
            // User typed "QuackPlayzYT" — also try ".QuackPlayzYT"
            uuid = tryOfflinePlayer(BedrockDetector.getBedrockPrefix() + name);
            if (uuid != null) return uuid;
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static UUID tryOfflinePlayer(@NotNull String name) {
        try {
            OfflinePlayer op = Bukkit.getOfflinePlayer(name);
            if (op != null && (op.hasPlayedBefore() || op.isOnline())) {
                return op.getUniqueId();
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
