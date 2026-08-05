package com.crazysmpmods.coordinateannouncer.config;

import com.crazysmpmods.coordinateannouncer.CoordinateAnnouncer;
import com.crazysmpmods.coordinateannouncer.model.CustomPlayer;
import com.crazysmpmods.coordinateannouncer.model.DelayUnit;
import org.bukkit.configuration.ConfigurationSection;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Atomic, thread-safe wrapper around config.yml.
 *
 * All mutations go through {@link #save()} which writes to a temp file
 * then atomically renames — no corruption possible from a mid-write crash.
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
                    if (o instanceof java.util.Map<?, ?> m) {
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

    public void save() {
        lock.lock();
        try {
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.set("enabled", enabled);
            cfg.set("delay.value", delayValue);
            cfg.set("delay.unit", delayUnit.name());
            cfg.set("mode", mode.name());
            cfg.set("offline-handling", offlineHandling.name());

            // Custom players (serialized as list of maps)
            List<java.util.Map<String, Object>> playerMaps = new ArrayList<>();
            for (CustomPlayer cp : customPlayers) {
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("uuid", cp.uuid().toString());
                m.put("name", cp.name());
                playerMaps.add(m);
            }
            cfg.set("custom-players", playerMaps);

            cfg.set("position-cache-throttle-ms", positionCacheThrottleMs);
            cfg.set("filter-npcs", filterNpcs);
            cfg.set("countdown-global", countdownGlobal);

            // Atomic save: write to temp file then rename
            Files.createDirectories(configFile.getParent());
            Path tmp = configFile.resolveSibling("config.yml.tmp");
            String data = cfg.saveToString();
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
        } finally {
            lock.unlock();
        }
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
