package com.crazysmpmods.coordinateannouncer.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Centralized color palette and message helpers for CoordinateAnnouncer.
 *
 * Color scheme (Quackingly-inspired):
 *   - Primary accent:  §d (light purple / magenta)
 *   - Secondary:       §b (aqua)
 *   - Warning:         §e (yellow) + §6 (gold) + §c (red) for escalation
 *   - Body text:       §f (white)
 *   - Muted/labels:    §7 / §8 (grays)
 *
 * The "dividers" use §d§l (bold magenta) to match the Quackingly GUI style.
 *
 * All §-prefixed legacy strings should be converted via {@link #c(String)}
 * before being passed to Adventure-aware APIs (Bukkit.broadcast, sendMessage).
 */
public final class ColorScheme {

    private ColorScheme() {}

    // Legacy color codes (for building strings)
    public static final String PRIMARY   = "§d";
    public static final String SECONDARY = "§b";
    public static final String BODY      = "§f";
    public static final String LABEL     = "§7";
    public static final String MUTED     = "§8";
    public static final String WARN_LOW  = "§e";
    public static final String WARN_MED  = "§6";
    public static final String WARN_HIGH = "§c";

    public static final String DIVIDER = PRIMARY + "§l════════════════════════════════════════";
    public static final String HEADER  = PRIMARY + "§l              COORDINATE ANNOUNCER";

    private static final LegacyComponentSerializer LEGACY_SECTION =
            LegacyComponentSerializer.legacySection();

    /**
     * Convert a §-prefixed legacy string to an Adventure Component.
     * This is what you should call before passing to Bukkit.broadcast / sendMessage.
     * Bug fix: null-safe — returns Component.empty() for null input instead of
     * throwing NPE from LEGACY_SECTION.deserialize(null).
     */
    public static Component c(String legacyText) {
        if (legacyText == null) return Component.empty();
        return LEGACY_SECTION.deserialize(legacyText);
    }

    /**
     * Translate &-prefixed color codes (Minecraft chat format) to §-prefixed
     * codes (Java string format). This is what users actually type in chat
     * since they can't enter § on most keyboards.
     *
     * E.g., "&c[ALERT] " → "§c[ALERT] "
     *
     * Also handles the special case of "&&" → "&" (literal ampersand).
     */
    public static String translateAmpersand(String text) {
        if (text == null) return "";
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', text);
    }

    /**
     * Build a warning Component (used by the countdown).
     */
    public static Component warn(String legacyText) {
        return c(legacyText);
    }

    /**
     * Build a success message string (for sendMessage(String) calls).
     */
    public static String success(String text) {
        return "§a✔ §f" + text;
    }

    /**
     * Build an error message string.
     */
    public static String error(String text) {
        return "§c✖ §f" + text;
    }

    /**
     * Build an info message string.
     */
    public static String info(String text) {
        return "§bℹ §f" + text;
    }
}
