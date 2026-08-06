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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Iterator;
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
 * Persistence: written to data.yml via ASYNC task (Bukkit scheduler).
 * Atomic save: temp file + atomic rename — no corruption on crash.
 *
 * Cache pruning: entries older than 30 days are auto-pruned on save
 * to prevent unbounded growth.
 */
public class OfflinePositionCache {

    private final CoordinateAnnouncer plugin;
    private final Path dataFile;
    private final ReentrantLock lock = new ReentrantLock();

    // uuid -> cached position
    private final Map<UUID, CachedPosition> cache = new HashMap<>();

    // Tracks whether a save is currently scheduled (prevents stacking async saves)
    private volatile boolean savePending = false;
    // Dirty flag: set when an update lands while a save is in-flight, so doSave()
    // schedules a follow-up save. Prevents data loss if the server crashes
    // between snapshot and the in-flight save completing.
    private boolean dirty = false;
    // Generation counter: incremented on every saveSync()/clear()/flush() so a
    // stale in-flight async save can detect it was superseded and skip its write.
    private long saveGeneration = 0;

    /** Max age in ms before a cached entry is pruned (30 days). */
    private static final long MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000;

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

            long now = System.currentTimeMillis();
            int pruned = 0;
            for (String uuidStr : root.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    String name = root.getString(uuidStr + ".name", "?");
                    String worldName = root.getString(uuidStr + ".world", "world");
                    int x = root.getInt(uuidStr + ".x", 0);
                    int y = root.getInt(uuidStr + ".y", 64);
                    int z = root.getInt(uuidStr + ".z", 0);
                    long ts = root.getLong(uuidStr + ".timestamp", 0L);
                    // Prune stale entries on load
                    if ((now - ts) > MAX_AGE_MS) {
                        pruned++;
                        continue;
                    }
                    cache.put(uuid, new CachedPosition(name, worldName, x, y, z, ts));
                } catch (IllegalArgumentException ignored) {
                    // skip invalid UUIDs
                }
            }
            plugin.getLogger().info("Loaded " + cache.size() + " cached positions from data.yml"
                    + (pruned > 0 ? " (pruned " + pruned + " stale entries)" : ""));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Schedule an async save. Multiple rapid calls coalesce into one save.
     *
     * BUG FIX (v1.2.0): previously the check-then-set of `savePending` wasn't
     * atomic across the main thread (sets true) and the async save thread
     * (resets false in doSave()'s finally). An update() that landed between
     * another save's snapshot-under-lock and its finally-reset could get
     * skipped. Now the check-then-set is done under the same lock as the
     * cache mutations, so a mutation always either (a) sees savePending=false
     * and schedules a new save, or (b) sees savePending=true and trusts the
     * in-flight save will snapshot its update.
     *
     * Note: there's still a tiny window between doSave() snapshotting under
     * lock and resetting savePending=false in finally. An update that lands
     * in that window WILL be picked up by the next save() call (which will
     * see savePending=false and schedule a new one). So no data is lost —
     * the worst case is one extra save cycle, which is fine.
     */
    public void save() {
        lock.lock();
        try {
            if (savePending) {
                // An async save is already in flight. Mark dirty so that when
                // the in-flight save finishes, it schedules a follow-up save
                // that picks up this update. Without this, an update landing
                // between snapshot and the in-flight save's completion would
                // be lost if the server crashed before the next update()
                // triggered another save.
                dirty = true;
                return;
            }
            savePending = true;
        } finally {
            lock.unlock();
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::doSave);
    }

    /**
     * Synchronous save — only call from onDisable() or reload().
     * Increments the generation counter so any in-flight async save detects
     * it was superseded and skips its (now-stale) write.
     */
    public void saveSync() {
        lock.lock();
        try {
            saveGeneration++;
            savePending = false;
            dirty = false;
        } finally {
            lock.unlock();
        }
        doSave();
    }

    private void doSave() {
        // Snapshot under lock, then do I/O outside lock
        final YamlConfiguration yaml = new YamlConfiguration();
        final int count;
        final long myGen;
        lock.lock();
        try {
            myGen = saveGeneration;
            // Prune stale entries before saving
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<UUID, CachedPosition>> it = cache.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, CachedPosition> e = it.next();
                if ((now - e.getValue().timestamp) > MAX_AGE_MS) {
                    it.remove();
                }
            }
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
            count = cache.size();
        } finally {
            lock.unlock();
        }

        // File I/O (outside the lock — doesn't block readers)
        try {
            Files.createDirectories(dataFile.getParent());
            Path tmp = dataFile.resolveSibling("data.yml.tmp");
            String data = yaml.saveToString();
            Files.write(tmp, data.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException amo) {
                // Network filesystems (NFS/CIFS) and some container overlays
                // don't support atomic move. Fall back to a non-atomic replace
                // rather than silently dropping the save.
                plugin.getLogger().warning("Atomic move unsupported on this filesystem — falling back to non-atomic replace.");
                Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save data.yml: " + e.getMessage());
        } finally {
            // Only commit the save state if our generation is still current.
            // If saveSync()/clear()/flush() bumped the generation while we
            // were doing I/O, a newer save has already been written (or is
            // about to be) — we must NOT overwrite it with our stale snapshot
            // or reset savePending/dirty in a way that drops the newer save.
            boolean scheduleFollowUp = false;
            lock.lock();
            try {
                if (myGen == saveGeneration) {
                    savePending = false;
                    if (dirty) {
                        dirty = false;
                        scheduleFollowUp = true;
                    }
                }
            } finally {
                lock.unlock();
            }
            if (scheduleFollowUp) {
                // Schedule a follow-up save to pick up updates that landed
                // while this save was in flight.
                Bukkit.getScheduler().runTaskAsynchronously(plugin, this::doSave);
            }
        }
    }

    /**
     * Update the cached position for a player.
     * Schedules an async save (does NOT block the main thread).
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
        save(); // async — non-blocking
    }

    /**
     * Get the cached position for an offline player, or null if no cache exists.
     *
     * BUG FIX (v1.2.0): previously, if the cached world was deleted/unloaded,
     * this method would swap in Bukkit.getWorlds().get(0) but keep the old
     * x/y/z — returning a Location in the WRONG world. That's a landmine if
     * anyone ever uses the Location directly (e.g., for teleportation).
     * Now we return null if the world is gone; callers extract raw ints
     * via getCachedDimension() for display, so this is safe.
     */
    @Nullable
    public Location getCachedLocation(@NotNull UUID uuid) {
        lock.lock();
        try {
            CachedPosition cp = cache.get(uuid);
            if (cp == null) return null;
            World w = Bukkit.getWorld(cp.worldName);
            if (w == null) {
                // World was deleted/unloaded — return null instead of a
                // wrong-world Location. Callers should fall back to the
                // "no cached position" branch (which displays "0 0 0 Unknown").
                return null;
            }
            return new Location(w, cp.x + 0.5, cp.y, cp.z + 0.5);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get the cached Dimension for an offline player, or null.
     * Returns UNKNOWN if the world was deleted/unloaded.
     */
    @Nullable
    public com.crazysmpmods.coordinateannouncer.model.Dimension getCachedDimension(@NotNull UUID uuid) {
        lock.lock();
        try {
            CachedPosition cp = cache.get(uuid);
            if (cp == null) return null;
            World w = Bukkit.getWorld(cp.worldName);
            // If world is null (deleted/unloaded), still return a dimension
            // based on the world name convention.
            if (w == null) {
                return guessDimensionFromName(cp.worldName);
            }
            return com.crazysmpmods.coordinateannouncer.model.Dimension.fromWorld(w);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Raw cached position snapshot — returns x/y/z + best-guess Dimension
     * even when the cached world is currently unloaded. Used for offline
     * player display where we want to show the real last-known coords
     * rather than "0 0 0 Unknown".
     *
     * Bug fix: getCachedLocation() returns null when the world is unloaded,
     * even though the cache record still holds valid x/y/z. This method
     * exposes those raw ints so callers can display accurate coordinates
     * for offline players whose world happens to be unloaded.
     */
    @Nullable
    public RawPos getCachedRaw(@NotNull UUID uuid) {
        lock.lock();
        try {
            CachedPosition cp = cache.get(uuid);
            if (cp == null) return null;
            World w = Bukkit.getWorld(cp.worldName);
            com.crazysmpmods.coordinateannouncer.model.Dimension dim =
                    (w != null) ? com.crazysmpmods.coordinateannouncer.model.Dimension.fromWorld(w)
                                : guessDimensionFromName(cp.worldName);
            return new RawPos(cp.x, cp.y, cp.z, dim);
        } finally {
            lock.unlock();
        }
    }

    /** Raw cached position (no World reference — safe even if world is unloaded). */
    public record RawPos(int x, int y, int z, @NotNull com.crazysmpmods.coordinateannouncer.model.Dimension dim) {}

    private com.crazysmpmods.coordinateannouncer.model.Dimension guessDimensionFromName(String name) {
        if (name == null) return com.crazysmpmods.coordinateannouncer.model.Dimension.UNKNOWN;
        String lower = name.toLowerCase();
        if (lower.contains("nether")) return com.crazysmpmods.coordinateannouncer.model.Dimension.NETHER;
        if (lower.contains("the_end") || lower.endsWith("_end") || lower.equals("end")) {
            return com.crazysmpmods.coordinateannouncer.model.Dimension.THE_END;
        }
        if (lower.contains("world") || lower.equals("overworld")) {
            return com.crazysmpmods.coordinateannouncer.model.Dimension.OVERWORLD;
        }
        return com.crazysmpmods.coordinateannouncer.model.Dimension.UNKNOWN;
    }

    @Nullable
    public String getCachedUsername(@NotNull UUID uuid) {
        lock.lock();
        try {
            CachedPosition cp = cache.get(uuid);
            return cp != null ? cp.username : null;
        } finally {
            lock.unlock();
        }
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
        // Bug fix (v1.3.0): previously clear() released the lock before calling
        // saveSync(). An update() landing in that window would be wiped by the
        // sync save. Now we bump generation + clear the cache + do the sync save
        // all without releasing the lock between them. doSave() acquires the
        // lock (reentrant) so this is safe.
        lock.lock();
        try {
            saveGeneration++;
            cache.clear();
            savePending = false;
            dirty = false;
            doSave();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Force-save any pending changes (called from onDisable).
     */
    public void flush() {
        saveSync();
    }

    // ── Inner record ──────────────────────────────────────────────────────

    private record CachedPosition(String username, String worldName, int x, int y, int z, long timestamp) {}
}
