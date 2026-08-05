package com.crazysmpmods.coordinateannouncer.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Detects Bedrock players joining via GeyserMC + Floodgate.
 *
 * Floodgate is the companion plugin to Geyser that allows Bedrock players to
 * join Java servers without a Java account. It prefixes Bedrock player names
 * with a configurable character (default: '.') and exposes an API to detect them.
 *
 * This class uses reflection to avoid a hard dependency on Floodgate — if
 * Floodgate is not installed, all players are treated as Java players.
 *
 * ViaVersion and ViaBackwards do NOT need special handling — they translate
 * protocol between Java versions, so players joining via those plugins are
 * still regular Bukkit Players from the plugin's perspective.
 */
public final class BedrockDetector {

    private BedrockDetector() {}

    private static volatile Boolean floodgateAvailable = null;
    private static volatile Method isFloodgatePlayerMethod = null;
    private static volatile Object floodgateApiInstance = null;

    /**
     * Check if Floodgate is installed on the server.
     */
    public static boolean isFloodgateAvailable() {
        if (floodgateAvailable != null) return floodgateAvailable;
        synchronized (BedrockDetector.class) {
            if (floodgateAvailable != null) return floodgateAvailable;
            try {
                Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                Method getInstance = apiClass.getMethod("getInstance");
                floodgateApiInstance = getInstance.invoke(null);
                isFloodgatePlayerMethod = apiClass.getMethod("isFloodgatePlayer", UUID.class);
                floodgateAvailable = true;
            } catch (Throwable t) {
                floodgateAvailable = false;
            }
            return floodgateAvailable;
        }
    }

    /**
     * Check if a player is a Bedrock player (joined via Geyser+Floodgate).
     * Returns false if Floodgate is not installed or the player is a Java player.
     */
    public static boolean isBedrockPlayer(@NotNull Player p) {
        if (!isFloodgateAvailable()) return false;
        try {
            Object result = isFloodgatePlayerMethod.invoke(floodgateApiInstance, p.getUniqueId());
            return Boolean.TRUE.equals(result);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Check if a UUID belongs to a Bedrock player.
     */
    public static boolean isBedrockPlayer(@NotNull UUID uuid) {
        if (!isFloodgateAvailable()) return false;
        try {
            Object result = isFloodgatePlayerMethod.invoke(floodgateApiInstance, uuid);
            return Boolean.TRUE.equals(result);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Get the Bedrock username prefix configured in Floodgate (default: ".").
     * Returns "." if Floodgate is not installed (safe default — Bedrock players
     * won't be on the server anyway in that case).
     */
    public static String getBedrockPrefix() {
        if (!isFloodgateAvailable()) return ".";
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Method getInstance = apiClass.getMethod("getInstance");
            Object api = getInstance.invoke(null);
            Method getPrefix = apiClass.getMethod("getPlayerPrefix");
            Object prefix = getPrefix.invoke(api);
            return prefix != null ? prefix.toString() : ".";
        } catch (Throwable t) {
            return ".";
        }
    }

    /**
     * Strip the Bedrock prefix from a name if present.
     * E.g., ".QuackPlayzYT" → "QuackPlayzYT"
     * Useful for normalizing user input in /ca player add <name>.
     */
    @NotNull
    public static String stripBedrockPrefix(@NotNull String name) {
        String prefix = getBedrockPrefix();
        if (name.startsWith(prefix) && name.length() > prefix.length()) {
            return name.substring(prefix.length());
        }
        return name;
    }

    /**
     * Check if a name has the Bedrock prefix.
     */
    public static boolean hasBedrockPrefix(@NotNull String name) {
        String prefix = getBedrockPrefix();
        return name.startsWith(prefix) && name.length() > prefix.length();
    }
}
