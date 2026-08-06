package com.crazysmpmods.coordinateannouncer.config;

import com.crazysmpmods.coordinateannouncer.CoordinateAnnouncer;
import com.crazysmpmods.coordinateannouncer.model.CustomPlayer;
import com.crazysmpmods.coordinateannouncer.model.DelayUnit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Atomic, thread-safe wrapper around config.yml.
 *
 * All mutations go through {@link #save()} which writes to a temp file
 * then atomically renames — no corruption possible from a mid-write crash.
 *
 * COMMENT PRESERVATION: Unlike the default YamlConfiguration.save() which
 * strips all comments, this class writes a hand-crafted YAML string with
 * inline comments matching the default config.yml. This means user-edited
 * comments ARE replaced with our canonical comments on save, but at least
 * the file remains readable and documented.
 *
 * Mutators also bump an in-memory dirty flag; if the server crashes BEFORE
 * the next save() call, those changes are lost (acceptable for non-critical
 * settings like delay / mode — operator can re-set them).
 */
public class PluginConfig {

    private final CoordinateAnnouncer plugin;
    private final ReentrantLock lock = new ReentrantLock();
    private final Path configFile;

    // ── Config state ──────────────────────────────────────────────────────
    private boolean enabled = false;
    private long delayValue = 60L;
    private DelayUnit delayUnit = DelayUnit.MINUTES;
    private AnnouncementMode mode = AnnouncementMode.ALL;
    private OfflineHandling offlineHandling = OfflineHandling.SHOW;
    private final List<CustomPlayer> customPlayers = new ArrayList<>();

    // Advanced
    private long positionCacheThrottleMs = 5000L;
    private boolean filterNpcs = true;
    private boolean countdownGlobal = true;

    // NEW (v1.1.0)
    private long firstFireDelaySeconds = -1L; // -1 = use regular delay
    private boolean announceToConsole = true;
    private String messagePrefix = ""; // prepended to each announcement line

    public PluginConfig(@NotNull CoordinateAnnouncer plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "config.yml").toPath();
    }

    // ── Load / Save ───────────────────────────────────────────────────────

    public void load() {
        lock.lock();
        try {
            FileConfiguration cfg = plugin.getConfig();
            // Reload from disk in case of manual edits
            cfg.load(configFile.toFile());

            this.enabled       = cfg.getBoolean("enabled", false);
            this.delayValue    = cfg.getLong("delay.value", 60L);
            this.delayUnit     = DelayUnit.fromString(cfg.getString("delay.unit", "MINUTES"));
            this.mode          = AnnouncementMode.fromString(cfg.getString("mode", "ALL"));
            this.offlineHandling = OfflineHandling.fromString(cfg.getString("offline-handling", "SHOW"));

            // Custom players
            this.customPlayers.clear();
            List<?> rawList = cfg.getList("custom-players");
            if (rawList != null) {
                for (Object o : rawList) {
                    if (o instanceof Map<?, ?> m) {
                        try {
                            String uuidStr = String.valueOf(m.get("uuid"));
                            String name = String.valueOf(m.get("name"));
                            UUID uuid = UUID.fromString(uuidStr);
                            this.customPlayers.add(new CustomPlayer(uuid, name));
                        } catch (IllegalArgumentException ignored) {
                            // skip invalid entries
                        }
                    }
                }
            }

            // Advanced
            this.positionCacheThrottleMs = cfg.getLong("position-cache-throttle-ms", 5000L);
            this.filterNpcs              = cfg.getBoolean("filter-npcs", true);
            this.countdownGlobal         = cfg.getBoolean("countdown-global", true);
            this.firstFireDelaySeconds   = cfg.getLong("first-fire-delay-seconds", -1L);
            this.announceToConsole       = cfg.getBoolean("announce-to-console", true);
            this.messagePrefix           = cfg.getString("message-prefix", "");

            // Validate
            if (delayValue <= 0) {
                plugin.getLogger().warning("Invalid delay value " + delayValue + " — resetting to 60.");
                delayValue = 60L;
                delayUnit = DelayUnit.MINUTES;
            }
            long delaySeconds = delayUnit.toSeconds(delayValue);
            if (delaySeconds < 15) {
                plugin.getLogger().warning("Delay " + delaySeconds + "s is below minimum 15s — clamping to 15s.");
                delayValue = 15L;
                delayUnit = DelayUnit.SECONDS;
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load config.yml: " + e.getMessage());
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Save config to disk with inline comments (preserves readability).
     * Atomic: writes to temp file then renames.
     */
    public void save() {
        lock.lock();
        try {
            // Build YAML manually with comments
            StringBuilder sb = new StringBuilder();
            sb.append("# =============================================================\n");
            sb.append("#                  COORDINATE ANNOUNCER\n");
            sb.append("#         CrazySMP Mods — by QuackPlayzYT\n");
            sb.append("# =============================================================\n");
            sb.append("#\n");
            sb.append("#  All settings below can also be changed in-game via /ca gui\n");
            sb.append("#  (chest GUI). Changes made in GUI are saved here automatically.\n");
            sb.append("#  Manual edits to this file require /ca reload.\n");
            sb.append("# =============================================================\n\n");

            sb.append("# Whether announcements are currently enabled.\n");
            sb.append("# Set to false on first install — operator must explicitly /ca toggle.\n");
            sb.append("enabled: ").append(enabled).append("\n\n");

            sb.append("# Delay between announcements.\n");
            sb.append("# Format: <integer> <unit>  where unit ∈ {seconds, minutes, hours, days}\n");
            sb.append("# Minimum: 15 seconds (the 10s + 5s countdown needs at least 11s, +4s buffer).\n");
            sb.append("delay:\n");
            sb.append("  value: ").append(delayValue).append("\n");
            sb.append("  unit: ").append(delayUnit.name()).append("\n\n");

            sb.append("# Which players to announce.\n");
            sb.append("#   ALL    = every currently-online player\n");
            sb.append("#   CUSTOM = only players added via /ca player add <name>\n");
            sb.append("mode: ").append(mode.name()).append("\n\n");

            sb.append("# How to handle players on the custom list who are offline at announcement time.\n");
            sb.append("#   SHOW = display \"[OFFLINE] <name> → Last known: X Y Z (dim)\" using cached position\n");
            sb.append("#   SKIP = silently omit them from the announcement\n");
            sb.append("offline-handling: ").append(offlineHandling.name()).append("\n\n");

            sb.append("# The list of custom players (used when mode=CUSTOM).\n");
            sb.append("# Edits via /ca player add|remove — DO NOT edit manually unless you know the UUIDs.\n");
            sb.append("custom-players:\n");
            if (customPlayers.isEmpty()) {
                sb.append("  []\n\n");
            } else {
                for (CustomPlayer cp : customPlayers) {
                    sb.append("  - uuid: \"").append(cp.uuid()).append("\"\n");
                    sb.append("    name: \"").append(escapeYamlString(cp.name())).append("\"\n");
                }
                sb.append("\n");
            }

            sb.append("# =============================================================\n");
            sb.append("#  ADVANCED (do not change unless you know what you are doing)\n");
            sb.append("# =============================================================\n\n");

            sb.append("# Throttle for PlayerMoveEvent position cache updates (in milliseconds).\n");
            sb.append("# Lower = more accurate last-known coords but more CPU. 5000ms = 5s.\n");
            sb.append("position-cache-throttle-ms: ").append(positionCacheThrottleMs).append("\n\n");

            sb.append("# Whether to filter out non-player \"fake players\" (Citizens NPCs, Carpet mod\n");
            sb.append("# fake players, etc.) from announcements.\n");
            sb.append("filter-npcs: ").append(filterNpcs).append("\n\n");

            sb.append("# Whether the countdown messages should be sent globally (true) or only to\n");
            sb.append("# players who will receive the announcement (false — useful for custom mode\n");
            sb.append("# where the list is small).\n");
            sb.append("countdown-global: ").append(countdownGlobal).append("\n\n");

            sb.append("# First-fire delay: time (in seconds) before the FIRST announcement after\n");
            sb.append("# plugin enable. Set to -1 to use the regular delay. Set to e.g. 30 for\n");
            sb.append("# a quick first announcement 30s after enable, then regular intervals after.\n");
            sb.append("# Minimum: 15 (countdown needs 11s + 4s buffer).\n");
            sb.append("first-fire-delay-seconds: ").append(firstFireDelaySeconds).append("\n\n");

            sb.append("# Whether to also print announcements + countdowns to the server console.\n");
            sb.append("# Set to false to silence console spam (players still see them in chat).\n");
            sb.append("announce-to-console: ").append(announceToConsole).append("\n\n");

            sb.append("# Custom prefix prepended to each announcement line (after the header).\n");
            sb.append("# E.g., set to \"[Tracker] \" to get \"[Tracker] QuackPlayzYT → 20 28 -483 (Overworld)\".\n");
            sb.append("# Leave empty for no prefix. Supports §-color codes.\n");
            sb.append("message-prefix: \"").append(escapeYamlString(messagePrefix)).append("\"\n");

            String data = sb.toString();

            // Atomic save: write to temp file then rename
            Files.createDirectories(configFile.getParent());
            Path tmp = configFile.resolveSibling("config.yml.tmp");
            Files.write(tmp, data.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save config.yml: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    public void resetToDefaults() {
        lock.lock();
        try {
            this.enabled = false;
            this.delayValue = 60L;
            this.delayUnit = DelayUnit.MINUTES;
            this.mode = AnnouncementMode.ALL;
            this.offlineHandling = OfflineHandling.SHOW;
            this.customPlayers.clear();
            this.positionCacheThrottleMs = 5000L;
            this.filterNpcs = true;
            this.countdownGlobal = true;
            this.firstFireDelaySeconds = -1L;
            this.announceToConsole = true;
            this.messagePrefix = "";
        } finally {
            lock.unlock();
        }
        // BUG FIX (v1.2.0): previously resetToDefaults() did NOT save, which
        // meant that if load() failed and fell back to defaults, the corrupted
        // file on disk was never healed. Now we save the defaults so the file
        // is rewritten cleanly.
        save();
    }

    /**
     * Escape a string for use inside a double-quoted YAML scalar.
     *
     * Order matters: backslashes MUST be escaped FIRST, otherwise the
     * backslash we add when escaping quotes would itself get escaped.
     *
     * Handles: \ → \\, " → \", and standard YAML control chars (\n, \r, \t).
     */
    private static String escapeYamlString(@NotNull String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");  // MUST be first
                case '"'  -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> sb.append(c);
            }
        }
        return sb.toString();
    }

    // ── Getters / Setters (each setter auto-saves) ────────────────────────

    public boolean isEnabled() {
        lock.lock();
        try { return enabled; } finally { lock.unlock(); }
    }

    public void setEnabled(boolean enabled) {
        lock.lock();
        try {
            this.enabled = enabled;
        } finally { lock.unlock(); }
        save();
    }

    public long getDelayValue() {
        lock.lock();
        try { return delayValue; } finally { lock.unlock(); }
    }

    public DelayUnit getDelayUnit() {
        lock.lock();
        try { return delayUnit; } finally { lock.unlock(); }
    }

    public void setDelay(long value, @NotNull DelayUnit unit) {
        // BUG FIX (v1.2.0): centralize the 15s minimum clamp HERE so all callers
        // (CACommand, GUIManager, ChatInputListener callback) get consistent
        // validation. Previously each call site validated independently, which
        // was a landmine for any future caller that forgets to validate.
        // If the delay were ever set below ~11s, onMainFire()'s "cancel if
        // countdown already running" guard would mean every cycle interrupts
        // the last and the announcement never actually fires.
        if (value < 1) {
            plugin.getLogger().warning("setDelay(" + value + ", " + unit + "): value < 1, clamping to 1");
            value = 1;
        }
        long seconds = unit.toSeconds(value);
        if (seconds < 15) {
            plugin.getLogger().warning("setDelay(" + value + ", " + unit + "): " + seconds
                    + "s is below minimum 15s, clamping to 15s");
            value = 15;
            unit = DelayUnit.SECONDS;
        }
        lock.lock();
        try {
            this.delayValue = value;
            this.delayUnit = unit;
        } finally { lock.unlock(); }
        save();
    }

    public long getDelaySeconds() {
        lock.lock();
        try { return delayUnit.toSeconds(delayValue); } finally { lock.unlock(); }
    }

    public AnnouncementMode getMode() {
        lock.lock();
        try { return mode; } finally { lock.unlock(); }
    }

    public void setMode(@NotNull AnnouncementMode mode) {
        lock.lock();
        try { this.mode = mode; } finally { lock.unlock(); }
        save();
    }

    public OfflineHandling getOfflineHandling() {
        lock.lock();
        try { return offlineHandling; } finally { lock.unlock(); }
    }

    public void setOfflineHandling(@NotNull OfflineHandling offlineHandling) {
        lock.lock();
        try { this.offlineHandling = offlineHandling; } finally { lock.unlock(); }
        save();
    }

    public List<CustomPlayer> getCustomPlayers() {
        lock.lock();
        try { return new ArrayList<>(customPlayers); } finally { lock.unlock(); }
    }

    public boolean addCustomPlayer(@NotNull CustomPlayer cp) {
        lock.lock();
        try {
            // Avoid duplicates (match by UUID)
            for (CustomPlayer existing : customPlayers) {
                if (existing.uuid().equals(cp.uuid())) return false;
            }
            customPlayers.add(cp);
        } finally { lock.unlock(); }
        save();
        return true;
    }

    public boolean removeCustomPlayer(@NotNull UUID uuid) {
        lock.lock();
        boolean removed;
        try {
            removed = customPlayers.removeIf(cp -> cp.uuid().equals(uuid));
        } finally { lock.unlock(); }
        if (removed) save();
        return removed;
    }

    public boolean removeCustomPlayerByName(@NotNull String name) {
        lock.lock();
        boolean removed;
        try {
            removed = customPlayers.removeIf(cp -> cp.name().equalsIgnoreCase(name));
        } finally { lock.unlock(); }
        if (removed) save();
        return removed;
    }

    public void clearCustomPlayers() {
        lock.lock();
        try { customPlayers.clear(); } finally { lock.unlock(); }
        save();
    }

    public long getPositionCacheThrottleMs() {
        lock.lock();
        try { return positionCacheThrottleMs; } finally { lock.unlock(); }
    }

    public boolean isFilterNpcs() {
        lock.lock();
        try { return filterNpcs; } finally { lock.unlock(); }
    }

    public boolean isCountdownGlobal() {
        lock.lock();
        try { return countdownGlobal; } finally { lock.unlock(); }
    }

    public long getFirstFireDelaySeconds() {
        lock.lock();
        try { return firstFireDelaySeconds; } finally { lock.unlock(); }
    }

    public void setFirstFireDelaySeconds(long seconds) {
        lock.lock();
        try { this.firstFireDelaySeconds = seconds; } finally { lock.unlock(); }
        save();
    }

    public boolean isAnnounceToConsole() {
        lock.lock();
        try { return announceToConsole; } finally { lock.unlock(); }
    }

    public void setAnnounceToConsole(boolean b) {
        lock.lock();
        try { this.announceToConsole = b; } finally { lock.unlock(); }
        save();
    }

    @NotNull
    public String getMessagePrefix() {
        lock.lock();
        try { return messagePrefix; } finally { lock.unlock(); }
    }

    public void setMessagePrefix(@NotNull String prefix) {
        lock.lock();
        try { this.messagePrefix = prefix; } finally { lock.unlock(); }
        save();
    }

    // ── Enums ─────────────────────────────────────────────────────────────

    public enum AnnouncementMode {
        ALL,
        CUSTOM;

        public static AnnouncementMode fromString(String s) {
            if (s == null) return ALL;
            try { return AnnouncementMode.valueOf(s.toUpperCase()); }
            catch (IllegalArgumentException e) { return ALL; }
        }
    }

    public enum OfflineHandling {
        SHOW,
        SKIP;

        public static OfflineHandling fromString(String s) {
            if (s == null) return SHOW;
            try { return OfflineHandling.valueOf(s.toUpperCase()); }
            catch (IllegalArgumentException e) { return SHOW; }
        }
    }
}
