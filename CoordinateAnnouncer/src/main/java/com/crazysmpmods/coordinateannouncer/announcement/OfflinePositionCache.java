package com.crazysmpmods.coordinateannouncer.announcement;

import com.crazysmpmods.coordinateannouncer.CoordinateAnnouncer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Caches the last-known position of every player who has been online.
 * Used for "Show offline" mode — when an announcement fires and a custom
 * player on the list is offline, we use their cached last-known location.
 *
 * Position is updated:
 *   - On PlayerQuitEvent (final position before disconnect)
 *   - Throttled via PlayerMoveEvent (every N ms, configurable)
 *
 * Persistence: written to data.yml on every update (atomic temp-file+rename).
 * On startup: loaded back into memory.
 */
public class OfflinePositionCache {

    private final CoordinateAnnouncer plugin;
    private final Path dataFile;
    private final ReentrantLock lock = new ReentrantLock();

    // uuid -> [worldName, x, y, z, username]
    private final Map<UUID, CachedPosition> cache = new HashMap<>();

    public OfflinePositionCache(@NotNull CoordinateAnnouncer plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml").toPath();
    }

    public void load() {
        lock.lock();
        try {
            cache.clear();
            File f = dataFile.toFile();
            if (!f.exists()) return;

            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(f);
            ConfigurationSection root = yaml.getConfigurationSection("players");
            if (root == null) return;

            for (String uuidStr : root.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    String name = root.getString(uuidStr + ".name", "?");
                    String worldName = root.getString(uuidStr + ".world", "world");
                    int x = root.getInt(uuidStr + ".x", 0);
                    int y = root.getInt(uuidStr + ".y", 64);
                    int z = root.getInt(uuidStr + ".z", 0);
                    long ts = root.getLong(uuidStr + ".timestamp", 0L);
                    cache.put(uuid, new CachedPosition(name, worldName, x, y, z, ts));
                } catch (IllegalArgumentException ignored) {
                    // skip invalid UUIDs
                }
            }
            plugin.getLogger().info("Loaded " + cache.size() + " cached positions from data.yml");
        } finally {
            lock.unlock();
        }
    }

    public void save() {
        lock.lock();
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            for (Map.Entry<UUID, CachedPosition> e : cache.entrySet()) {
                String key = "players." + e.getKey();
                CachedPosition p = e.getValue();
                yaml.set(key + ".name", p.username);
                yaml.set(key + ".world", p.worldName);
                yaml.set(key + ".x", p.x);
                yaml.set(key + ".y", p.y);
                yaml.set(key + ".z", p.z);
                yaml.set(key + ".timestamp", p.timestamp);
            }

            Files.createDirectories(dataFile.getParent());
            Path tmp = dataFile.resolveSibling("data.yml.tmp");
            String data = yaml.saveToString();
            Files.write(tmp, data.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save data.yml: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Update the cached position for a player (called from PlayerMoveEvent/QuitEvent).
     */
    public void update(@NotNull UUID uuid, @NotNull String username, @NotNull Location loc) {
        lock.lock();
        try {
            World w = loc.getWorld();
            String worldName = (w != null) ? w.getName() : "world";
            cache.put(uuid, new CachedPosition(
                    username,
                    worldName,
                    loc.getBlockX(),
                    loc.getBlockY(),
                    loc.getBlockZ(),
                    System.currentTimeMillis()
            ));
        } finally {
            lock.unlock();
        }
        // Persist asynchronously to avoid blocking the main thread on every move
        // (Bukkit scheduler runs on main thread, but file I/O is fast enough to
        // stay sync for our use case — typically <5ms)
        save();
    }

    /**
     * Get the cached position for an offline player, or null if no cache exists.
     */
    @Nullable
    public Location getCachedLocation(@NotNull UUID uuid) {
        lock.lock();
        try {
            CachedPosition cp = cache.get(uuid);
            if (cp == null) return null;
            World w = Bukkit.getWorld(cp.worldName);
            if (w == null) return null;
            return new Location(w, cp.x + 0.5, cp.y, cp.z + 0.5);
        } finally {
            lock.unlock();
        }
    }

    @Nullable
    public String getCachedUsername(@NotNull UUID uuid) {
        lock.lock();
        try {
            CachedPosition cp = cache.get(uuid);
            return cp != null ? cp.username : null;
        } finally {
            lock.unlock(); }
    }

    public boolean has(@NotNull UUID uuid) {
        lock.lock();
        try { return cache.containsKey(uuid); } finally { lock.unlock(); }
    }

    public int size() {
        lock.lock();
        try { return cache.size(); } finally { lock.unlock(); }
    }

    public void clear() {
        lock.lock();
        try { cache.clear(); } finally { lock.unlock(); }
        save();
    }

    // ── Inner record ──────────────────────────────────────────────────────

    private record CachedPosition(String username, String worldName, int x, int y, int z, long timestamp) {}
}
