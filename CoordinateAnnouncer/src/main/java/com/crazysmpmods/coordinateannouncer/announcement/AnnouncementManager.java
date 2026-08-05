package com.crazysmpmods.coordinateannouncer.announcement;

import com.crazysmpmods.coordinateannouncer.CoordinateAnnouncer;
import com.crazysmpmods.coordinateannouncer.config.PluginConfig;
import com.crazysmpmods.coordinateannouncer.model.CustomPlayer;
import com.crazysmpmods.coordinateannouncer.model.PlayerCoordinate;
import com.crazysmpmods.coordinateannouncer.util.ColorScheme;
import com.crazysmpmods.coordinateannouncer.util.NpcFilter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Manages the periodic announcement task and the 10-second countdown.
 *
 * Lifecycle:
 *   - start()  → schedules a repeating sync task at the configured delay
 *   - stop()   → cancels all running tasks (countdown + main)
 *   - restart()→ stop() then start() with new delay
 *
 * When the main task fires, it:
 *   1. Schedules a 10s countdown (warnings at 10/5/4/3/2/1 seconds remaining)
 *   2. At T-0, snapshots the current player positions and broadcasts the
 *      full announcement. Snapshotting at T-0 (not at countdown start)
 *      ensures coords are accurate AS OF the announcement moment.
 *
 * Bug-prevention:
 *   - Only one countdown runs at a time (tracked via countdownTaskId)
 *   - Empty custom list + CUSTOM mode → cancel with warning, no spam
 *   - NPC filtering (skip fake players)
 *   - Offline handling: SHOW vs SKIP per config
 */
public class AnnouncementManager {

    private final CoordinateAnnouncer plugin;
    private Integer mainTaskId = null;
    private Integer countdownTaskId = null;

    public AnnouncementManager(@NotNull CoordinateAnnouncer plugin) {
        this.plugin = plugin;
    }

    /**
     * Start the periodic announcement task with the current delay.
     */
    public void start() {
        stop(); // always cancel any existing task first

        long delayTicks = plugin.getPluginConfig().getDelayUnit().toTicks(
                plugin.getPluginConfig().getDelayValue());

        plugin.getLogger().info("Starting announcement task: delay=" + delayTicks + " ticks ("
                + (delayTicks / 20) + "s)");

        mainTaskId = Bukkit.getScheduler().runTaskTimer(plugin, this::onMainFire,
                delayTicks, delayTicks).getTaskId();
    }

    /**
     * Stop everything (main task + any running countdown).
     */
    public void stop() {
        if (mainTaskId != null) {
            Bukkit.getScheduler().cancelTask(mainTaskId);
            mainTaskId = null;
        }
        if (countdownTaskId != null) {
            Bukkit.getScheduler().cancelTask(countdownTaskId);
            countdownTaskId = null;
        }
    }

    /**
     * Restart with new delay (called when /ca delay or /ca toggle is used).
     */
    public void restart() {
        if (plugin.getPluginConfig().isEnabled()) {
            start();
        }
    }

    /**
     * Manually trigger a 10-second countdown + announcement (for /ca test).
     * Bypasses the periodic scheduler — fires immediately.
     */
    public void triggerTestCountdown() {
        // Cancel any running countdown first
        if (countdownTaskId != null) {
            Bukkit.getScheduler().cancelTask(countdownTaskId);
            countdownTaskId = null;
        }
        onMainFire();
    }

    /**
     * Fire an announcement immediately, skipping the countdown (for /ca now).
     */
    public void triggerImmediate() {
        broadcastAnnouncement();
    }

    /**
     * Main task body: triggers the 10-second countdown, which then fires
     * the actual announcement at T-0.
     */
    private void onMainFire() {
        PluginConfig cfg = plugin.getPluginConfig();

        // Edge case: if countdown is already running (shouldn't be, since the
        // main task fires every full delay and countdown is only 11s), cancel it.
        if (countdownTaskId != null) {
            Bukkit.getScheduler().cancelTask(countdownTaskId);
            countdownTaskId = null;
        }

        // Edge case: empty custom list + CUSTOM mode → no point in countdown
        if (cfg.getMode() == PluginConfig.AnnouncementMode.CUSTOM
                && cfg.getCustomPlayers().isEmpty()) {
            Bukkit.broadcast(ColorScheme.warn(
                    "§e⚠ §cAnnouncement skipped: CUSTOM mode is selected but no players are added."));
            Bukkit.broadcast(ColorScheme.warn(
                    "§7Use §e/ca player add <name> §7to add players, or §e/ca mode all §7to switch."));
            return;
        }

        // Schedule countdown: warnings at 10, 5, 4, 3, 2, 1 seconds before announce.
        // Total countdown = 10 seconds. Then announcement fires.
        countdownTaskId = Bukkit.getScheduler().runTaskTimer(plugin, new CountdownRunnable(),
                0L, 20L).getTaskId(); // fires every 20 ticks (1s)
    }

    /**
     * The actual announcement — fired at T-0 after the countdown completes.
     */
    private void broadcastAnnouncement() {
        PluginConfig cfg = plugin.getPluginConfig();
        List<PlayerCoordinate> coords = collectCoordinates();

        if (coords.isEmpty()) {
            Bukkit.broadcast(ColorScheme.warn(
                    "§e⚠ §7No players to announce this cycle."));
            return;
        }

        // ── Build the announcement message ───────────────────────────────
        List<String> lines = new ArrayList<>();
        lines.add(ColorScheme.DIVIDER);
        lines.add(ColorScheme.HEADER);
        lines.add(ColorScheme.DIVIDER);
        for (PlayerCoordinate pc : coords) {
            lines.add(formatLine(pc));
        }
        lines.add(ColorScheme.DIVIDER);

        for (String line : lines) {
            Bukkit.broadcast(ColorScheme.c(line));
        }
    }

    @NotNull
    private String formatLine(@NotNull PlayerCoordinate pc) {
        if (pc.online()) {
            return "§e" + pc.username() + " §7→ §f" + pc.x() + " " + pc.y() + " " + pc.z()
                    + " §7(§b" + pc.dimension().displayName() + "§7)";
        } else {
            return "§7[§8OFFLINE§7] §7" + pc.username() + " §7→ §8Last known: §f"
                    + pc.x() + " " + pc.y() + " " + pc.z()
                    + " §7(§b" + pc.dimension().displayName() + "§7)";
        }
    }

    /**
     * Snapshot the coordinates of all relevant players at this moment.
     * - For ALL mode: every online player (filtered for NPCs)
     * - For CUSTOM mode: each custom-listed player (online OR offline per config)
     */
    @NotNull
    private List<PlayerCoordinate> collectCoordinates() {
        PluginConfig cfg = plugin.getPluginConfig();
        List<PlayerCoordinate> result = new ArrayList<>();

        if (cfg.getMode() == PluginConfig.AnnouncementMode.ALL) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (NpcFilter.isNpc(p, cfg.isFilterNpcs())) continue;
                result.add(PlayerCoordinate.live(p.getName(), p.getLocation()));
            }
        } else {
            // CUSTOM mode
            for (CustomPlayer cp : cfg.getCustomPlayers()) {
                Player online = Bukkit.getPlayer(cp.uuid());
                if (online != null && online.isOnline()) {
                    if (NpcFilter.isNpc(online, cfg.isFilterNpcs())) continue;
                    result.add(PlayerCoordinate.live(online.getName(), online.getLocation()));
                } else {
                    // Offline
                    if (cfg.getOfflineHandling() == PluginConfig.OfflineHandling.SHOW) {
                        Location last = plugin.getOfflinePositionCache().getCachedLocation(cp.uuid());
                        com.crazysmpmods.coordinateannouncer.model.Dimension cachedDim =
                                plugin.getOfflinePositionCache().getCachedDimension(cp.uuid());
                        if (last != null && cachedDim != null) {
                            // Use the cached dimension (handles unloaded worlds correctly)
                            result.add(new PlayerCoordinate(
                                    cp.name(),
                                    last.getBlockX(), last.getBlockY(), last.getBlockZ(),
                                    cachedDim,
                                    false));
                        } else {
                            // No cached position — show "unknown" line
                            result.add(new PlayerCoordinate(
                                    cp.name(), 0, 0, 0,
                                    com.crazysmpmods.coordinateannouncer.model.Dimension.UNKNOWN,
                                    false));
                        }
                    }
                    // SKIP mode: silently omit
                }
            }
        }

        // Sort alphabetically by username for consistent output
        result.sort((a, b) -> a.username().compareToIgnoreCase(b.username()));
        return result;
    }

    // ── Countdown logic ───────────────────────────────────────────────────

    /**
     * Runnable that fires every 1 second during the countdown.
     * Tracks elapsed seconds; sends warnings at 10, 5, 4, 3, 2, 1 seconds
     * remaining; at 0s, fires the announcement and self-cancels.
     *
     * Defensive: includes a hard max-iteration guard (TOTAL + 5) so that even
     * if the cancelTask call somehow fails, the countdown still self-terminates
     * instead of looping forever.
     */
    private class CountdownRunnable implements Runnable {
        private int elapsed = 0;
        private static final int TOTAL = 10; // 10-second countdown
        private static final int MAX_ITERATIONS = TOTAL + 5; // hard guard

        @Override
        public void run() {
            // Hard guard: if we've been running too long, force-cancel
            if (elapsed > MAX_ITERATIONS) {
                plugin.getLogger().warning("CountdownRunnable exceeded max iterations — force-cancelling.");
                if (countdownTaskId != null) {
                    Bukkit.getScheduler().cancelTask(countdownTaskId);
                    countdownTaskId = null;
                }
                return;
            }

            int remaining = TOTAL - elapsed;

            if (remaining == 10) {
                broadcast("§e⚠ §6WARNING: §f10 seconds before the coordinates get announced!");
            } else if (remaining == 5) {
                broadcast("§e⚠ §65 §fseconds before the coordinates get announced");
            } else if (remaining == 4) {
                broadcast("§e⚠ §e4");
            } else if (remaining == 3) {
                broadcast("§e⚠ §63");
            } else if (remaining == 2) {
                broadcast("§e⚠ §62");
            } else if (remaining == 1) {
                broadcast("§e⚠ §c1");
            } else if (remaining <= 0) {
                // Fire announcement and cancel self
                broadcastAnnouncement();
                if (countdownTaskId != null) {
                    Bukkit.getScheduler().cancelTask(countdownTaskId);
                    countdownTaskId = null;
                }
                return;
            }

            elapsed++;
        }

        private void broadcast(String msg) {
            PluginConfig cfg = plugin.getPluginConfig();
            if (cfg.isCountdownGlobal()) {
                Bukkit.broadcast(ColorScheme.c(msg));
            } else {
                // Only send to players who will receive the announcement
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendMessage(ColorScheme.c(msg));
                }
            }
        }
    }

    // ── Status query ──────────────────────────────────────────────────────

    public boolean isRunning() {
        return mainTaskId != null;
    }

    public boolean isCountdownActive() {
        return countdownTaskId != null;
    }
}
