package com.crazysmpmods.coordinateannouncer.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

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
     * Try to resolve a name → UUID using the offline-player lookup.
     * Returns null if the name doesn't match any known player.
     */
    @SuppressWarnings("deprecation")
    public static UUID lookupUuid(@NotNull String name) {
        // First check online players (case-insensitive)
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().equalsIgnoreCase(name)) return p.getUniqueId();
        }
        // Fallback: offline player lookup (hits Mojang API on first call, cached after)
        var op = Bukkit.getOfflinePlayer(name);
        if (op != null && op.hasPlayedBefore()) {
            return op.getUniqueId();
        }
        return null;
    }
}
