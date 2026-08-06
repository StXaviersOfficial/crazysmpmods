package com.crazysmpmods.coordinateannouncer.listener;

import com.crazysmpmods.coordinateannouncer.CoordinateAnnouncer;
import com.crazysmpmods.coordinateannouncer.util.NpcFilter;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Listens for player movement / join / quit to maintain the offline-position cache.
 *
 * PlayerMoveEvent is throttled: only updates the cache at most once per
 * `position-cache-throttle-ms` (default 5s) per player, to avoid excessive
 * disk I/O on busy servers.
 */
public class PlayerPositionListener implements Listener {

    private final CoordinateAnnouncer plugin;
    private final Map<UUID, Long> lastUpdate = new HashMap<>();

    public PlayerPositionListener(@NotNull CoordinateAnnouncer plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();

        // Skip NPCs
        if (NpcFilter.isNpc(p, plugin.getPluginConfig().isFilterNpcs())) return;

        // Only update if the player actually moved to a new block
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return; // moved within same block — ignore
        }

        // Throttle
        UUID uuid = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastUpdate.get(uuid);
        long throttle = plugin.getPluginConfig().getPositionCacheThrottleMs();
        if (last != null && (now - last) < throttle) return;
        lastUpdate.put(uuid, now);

        // Update cache (this triggers an async save)
        plugin.getOfflinePositionCache().update(uuid, p.getName(), to);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (NpcFilter.isNpc(p, plugin.getPluginConfig().isFilterNpcs())) return;

        // Final position update on quit
        plugin.getOfflinePositionCache().update(p.getUniqueId(), p.getName(), p.getLocation());
        lastUpdate.remove(p.getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (NpcFilter.isNpc(p, plugin.getPluginConfig().isFilterNpcs())) return;

        // Cache the join position immediately
        plugin.getOfflinePositionCache().update(p.getUniqueId(), p.getName(), p.getLocation());
        // Bug fix: seed lastUpdate so the first onMove doesn't trigger a
        // redundant cache write (and a second async save) within milliseconds
        // of join. Previously lastUpdate was null after join, so onMove's
        // `if (last != null && ...)` would pass and call update() again.
        lastUpdate.put(p.getUniqueId(), System.currentTimeMillis());
    }
}
