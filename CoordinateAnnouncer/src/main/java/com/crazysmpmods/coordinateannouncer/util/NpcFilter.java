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
        // Bug fix (v1.3.0): on long-running servers, Bukkit.getOfflinePlayers()
        // can return tens of thousands of entries. Iterating it on the main thread
        // (this method is called from lookupUuid which is called sync from
        // lookupUuidAsync's fast path) can cause a noticeable lag spike.
        // Cap the iteration at 1000 entries; if the player isn't in the first
        // 1000, the async slow path (which runs off-main-thread) will handle it.
        OfflinePlayer[] offline = Bukkit.getOfflinePlayers();
        int limit = Math.min(offline.length, 1000);
        for (int i = 0; i < limit; i++) {
            OfflinePlayer op = offline[i];
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
        // Bug fix: the thread is now a DAEMON thread so it doesn't block
        // JVM shutdown if the operator stops the server mid-lookup.
        Thread t = new Thread(() -> {
            try {
                Thread.currentThread().setName("CoordinateAnnouncer-UUIDLookup-" + name);
                UUID uuid = tryLookupWithBedrockVariants(name);
                // Schedule callback on main thread
                // Bug fix: getInstance() can return null during the disable→enable
                // window. Bail out gracefully instead of passing null to runTask
                // (which logs a severe error).
                com.crazysmpmods.coordinateannouncer.CoordinateAnnouncer inst =
                        com.crazysmpmods.coordinateannouncer.CoordinateAnnouncer.getInstance();
                if (inst == null) {
                    // Bug fix (v1.3.0): previously if the plugin was disabled
                    // mid-lookup, the callback NEVER fired and the sender saw
                    // "Looking up..." forever with no resolution. Now we invoke
                    // the callback with null directly (on the async thread) so
                    // the caller can display a proper "not found / aborted"
                    // message. Callers must be prepared for the callback to
                    // fire on either the main thread OR an async thread when
                    // the result is null — all current callers only call
                    // sender.sendMessage() which is thread-safe.
                    try { callback.accept(null); } catch (Throwable ignored) {}
                    return;
                }
                org.bukkit.Bukkit.getScheduler().runTask(inst, () -> callback.accept(uuid));
            } catch (Throwable tx) {
                com.crazysmpmods.coordinateannouncer.CoordinateAnnouncer inst =
                        com.crazysmpmods.coordinateannouncer.CoordinateAnnouncer.getInstance();
                if (inst == null) {
                    try { callback.accept(null); } catch (Throwable ignored) {}
                    return;
                }
                org.bukkit.Bukkit.getScheduler().runTask(inst, () -> callback.accept(null));
            }
        }, "CA-UUIDLookup-" + name);
        t.setDaemon(true);
        t.start();
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

    private static UUID tryOfflinePlayer(@NotNull String name) {
        // IMPORTANT: this method is only reached from the async slow-path
        // AFTER the fast path (lookupUuid) has already established that the
        // name matches nobody in getOnlinePlayers() or getOfflinePlayers().
        // That means hasPlayedBefore() and isOnline() are GUARANTEED false here.
        //
        // The original code checked those and always returned null — making the
        // entire async Mojang-lookup path dead code. /ca player add <name>
        // reported "not found" for any real player who hadn't joined yet.
        //
        // Fixed approach: use Paper's PlayerProfile#complete() to actually
        // resolve name → UUID via Mojang API. complete() returns true only
        // if the player exists in Mojang's database, regardless of whether
        // they have ever joined this server.

        // Paper API path: create a profile from the name, then complete() it.
        try {
            var profile = org.bukkit.Bukkit.createProfile(name);
            boolean completed = profile.complete();
            if (completed && profile.getId() != null) {
                return profile.getId();
            }
        } catch (NoSuchMethodError | NoClassDefFoundError ignored) {
            // Non-Paper server (Spigot/Purpur without Paper API) — fall through
        } catch (Throwable t) {
            // Log and fall through to legacy path
            // (don't let a profile-resolution bug crash the lookup)
        }

        // Legacy Spigot fallback: Bukkit.getOfflinePlayer(name) returns a
        // non-null OfflinePlayer for ANY name (even non-existent). Its UUID
        // is only meaningful if the player has actually joined.
        // Bug fix: only return the UUID if the player has actually played
        // before or is currently online. Previously this returned a UUID for
        // any name — including non-existent players — which would add bogus
        // entries to the custom list that display "0 0 0 Unknown" forever.
        try {
            @SuppressWarnings("deprecation")
            OfflinePlayer op = Bukkit.getOfflinePlayer(name);
            if (op != null && (op.hasPlayedBefore() || op.isOnline())) {
                return op.getUniqueId();
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
