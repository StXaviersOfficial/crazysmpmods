package com.crazysmpmods.coordinateannouncer.announcement;

import com.crazysmpmods.coordinateannouncer.CoordinateAnnouncer;
import com.crazysmpmods.coordinateannouncer.config.PluginConfig;
import com.crazysmpmods.coordinateannouncer.model.CustomPlayer;
import com.crazysmpmods.coordinateannouncer.model.Dimension;
import com.crazysmpmods.coordinateannouncer.model.PlayerCoordinate;
import com.crazysmpmods.coordinateannouncer.util.ColorScheme;
import com.crazysmpmods.coordinateannouncer.util.NpcFilter;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

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
 * Bedrock/Geyser compatibility: All messages use Adventure Components which
 * Geyser translates to Bedrock format codes. Works for both Java and Bedrock.
 *
 * Bug-prevention:
 *   - Only one countdown runs at a time (tracked via countdownTaskId)
 *   - Empty custom list + CUSTOM mode → cancel with warning, no spam
 *   - NPC filtering (skip fake players)
 *   - Offline handling: SHOW vs SKIP per config
 *   - Hard max-iteration guard on countdown (prevents infinite loop)
 *   - Console logging is configurable (announce-to-console option)
 */
public class AnnouncementManager {

    private final CoordinateAnnouncer plugin;
    private Integer mainTaskId = null;
    private Integer countdownTaskId = null;
    // Generation counter — bumped on every stop() so any in-flight start()
    // callback can detect it was superseded and bail out before scheduling
    // a new repeating timer that nobody tracks.
    private volatile long startGeneration = 0L;

    public AnnouncementManager(@NotNull CoordinateAnnouncer plugin) {
        this.plugin = plugin;
    }

    /**
     * Start the periodic announcement task with the current delay.
     * The first fire uses firstFireDelaySeconds (if set) or the regular delay.
     *
     * BUG FIX (v1.3.0): Previously, if stop() was called between the runTaskLater
     * scheduling and the first-fire callback executing, the callback would run
     * anyway and create a NEW runTaskTimer — assigned to mainTaskId — that
     * nobody could cancel (the stop() already returned). The new timer would
     * run forever, firing announcements even after the plugin was "disabled".
     *
     * Fix: use a generation counter. stop() bumps the generation; the callback
     * checks if its generation is still current before scheduling the repeating
     * timer. If stop() ran first, the callback is a no-op.
     */
    public void start() {
        stop(); // always cancel any existing task first

        PluginConfig cfg = plugin.getPluginConfig();
        long regularDelayTicks = cfg.getDelayUnit().toTicks(cfg.getDelayValue());

        // Determine first-fire delay
        long firstFireSeconds = cfg.getFirstFireDelaySeconds();
        long firstFireTicks;
        if (firstFireSeconds < 0) {
            firstFireTicks = regularDelayTicks; // use regular delay
        } else {
            // Clamp to minimum 15s and maximum 1 year (prevents overflow)
            long clamped = Math.max(15, Math.min(31_536_000L, firstFireSeconds));
            firstFireTicks = clamped * 20L;
        }

        plugin.getLogger().info("Starting announcement task: first-fire in "
                + (firstFireTicks / 20) + "s, then every "
                + (regularDelayTicks / 20) + "s");

        // Capture the generation so the callback can detect if stop() ran first
        final long myGen = ++startGeneration;

        mainTaskId = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // If stop() was called between scheduling and this callback firing,
            // bail out — don't create a new repeating timer that nobody tracks.
            if (myGen != startGeneration) return;
            onMainFire();
            // Re-check after onMainFire() in case it called stop() internally
            if (myGen != startGeneration) return;
            // Now schedule the repeating task at regular delay
            mainTaskId = Bukkit.getScheduler().runTaskTimer(plugin, this::onMainFire,
                    regularDelayTicks, regularDelayTicks).getTaskId();
        }, firstFireTicks).getTaskId();
    }

    /**
     * Stop everything (main task + any running countdown).
     * Bumps the generation counter so any in-flight start() callback
     * detects it was superseded and doesn't schedule a new timer.
     */
    public void stop() {
        startGeneration++; // invalidate any in-flight start() callback
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
     *
     * BUG FIX (v1.2.0): Previously this didn't cancel any in-flight countdown,
     * so running `/ca now` while a countdown was ticking would cause a DOUBLE
     * announcement (immediate one + the countdown's announcement a few
     * seconds later). Now we cancel any running countdown first.
     */
    public void triggerImmediate() {
        // Cancel any running countdown first — prevents double-announce
        if (countdownTaskId != null) {
            Bukkit.getScheduler().cancelTask(countdownTaskId);
            countdownTaskId = null;
        }
        broadcastAnnouncement();
    }

    /**
     * Main task body: triggers the 10-second countdown, which then fires
     * the actual announcement at T-0.
     */
    private void onMainFire() {
        PluginConfig cfg = plugin.getPluginConfig();

        // Edge case: if countdown is already running, cancel it (shouldn't happen
        // since the main task fires every full delay and countdown is only 11s)
        if (countdownTaskId != null) {
            Bukkit.getScheduler().cancelTask(countdownTaskId);
            countdownTaskId = null;
        }

        // Edge case: empty custom list + CUSTOM mode → log to console only
        // (previously broadcast to all players every cycle = spam)
        if (cfg.getMode() == PluginConfig.AnnouncementMode.CUSTOM
                && cfg.getCustomPlayers().isEmpty()) {
            plugin.getLogger().warning("CUSTOM mode active but custom list is empty — skipping announcement. Use /ca player add <name> or /ca mode all.");
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
            broadcast(ColorScheme.warn(
                    "§e⚠ §7No players to announce this cycle."));
            return;
        }

        // ── Build the announcement message ───────────────────────────────
        String prefix = cfg.getMessagePrefix();
        List<String> lines = new ArrayList<>();
        lines.add(ColorScheme.DIVIDER);
        lines.add(ColorScheme.HEADER);
        lines.add(ColorScheme.DIVIDER);
        for (PlayerCoordinate pc : coords) {
            lines.add(prefix + formatLine(pc));
        }
        lines.add(ColorScheme.DIVIDER);

        for (String line : lines) {
            broadcast(ColorScheme.c(line));
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
            int onlineCount = Bukkit.getOnlinePlayers().size();
            result = new ArrayList<>(onlineCount);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (NpcFilter.isNpc(p, cfg.isFilterNpcs())) continue;
                // Bug fix (v1.4.0): skip vanished players (Essentials /vanish)
                if (p.hasMetadata("vanished") || p.hasMetadata("essentials_vanish")) continue;
                // Bug fix (v1.4.0): skip players in spectator mode (likely staff)
                if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
                result.add(PlayerCoordinate.live(p.getName(), p.getLocation()));
            }
        } else {
            // CUSTOM mode
            result = new ArrayList<>(cfg.getCustomPlayers().size());
            for (CustomPlayer cp : cfg.getCustomPlayers()) {
                Player online = Bukkit.getPlayer(cp.uuid());
                if (online != null && online.isOnline()) {
                    if (NpcFilter.isNpc(online, cfg.isFilterNpcs())) continue;
                    // Bug fix (v1.4.0): skip vanished players
                    if (online.hasMetadata("vanished") || online.hasMetadata("essentials_vanish")) continue;
                    if (online.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
                    result.add(PlayerCoordinate.live(online.getName(), online.getLocation()));
                } else {
                    // Offline
                    if (cfg.getOfflineHandling() == PluginConfig.OfflineHandling.SHOW) {
                        // Bug fix: use getCachedRaw() so we get the real x/y/z even
                        // when the cached world is currently unloaded. Previously,
                        // getCachedLocation() returned null for unloaded worlds,
                        // causing offline players to display "0 0 0 Unknown"
                        // despite the cache holding valid coordinates.
                        OfflinePositionCache.RawPos raw = plugin.getOfflinePositionCache().getCachedRaw(cp.uuid());
                        if (raw != null) {
                            result.add(new PlayerCoordinate(
                                    cp.name(),
                                    raw.x(), raw.y(), raw.z(),
                                    raw.dim(),
                                    false));
                        } else {
                            // No cached position — show "unknown" line
                            result.add(new PlayerCoordinate(
                                    cp.name(), 0, 0, 0,
                                    Dimension.UNKNOWN,
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

    // ── Broadcast helper (respects announce-to-console + countdown-global) ─

    /**
     * Broadcast a Component to all online players + optionally console.
     * Used for the announcement itself — always goes to everyone.
     */
    private void broadcast(Component msg) {
        // Always send to online players
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(msg);
        }
        // Conditionally log to console
        if (plugin.getPluginConfig().isAnnounceToConsole()) {
            Bukkit.getConsoleSender().sendMessage(msg);
        }
    }

    /**
     * Broadcast a countdown warning.
     *
     * BUG FIX (v1.2.0): The `countdown-global` config setting was previously
     * a "ghost setting" — fully modeled but never actually consulted. Now:
     *   - countdown-global=true  → send to every online player (default)
     *   - countdown-global=false → in CUSTOM mode, send ONLY to the custom-listed
     *                              online players; in ALL mode, send to everyone
     *                              (since everyone is a recipient anyway)
     */
    private void broadcastCountdown(Component msg) {
        PluginConfig cfg = plugin.getPluginConfig();

        if (cfg.isCountdownGlobal()
                || cfg.getMode() == PluginConfig.AnnouncementMode.ALL) {
            // Global: send to every online player
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendMessage(msg);
            }
        } else {
            // CUSTOM mode + non-global: send only to online custom-listed players
            for (CustomPlayer cp : cfg.getCustomPlayers()) {
                Player online = Bukkit.getPlayer(cp.uuid());
                if (online != null && online.isOnline()) {
                    online.sendMessage(msg);
                }
            }
        }

        // Conditionally log to console (always — console sees everything)
        if (plugin.getPluginConfig().isAnnounceToConsole()) {
            Bukkit.getConsoleSender().sendMessage(msg);
        }
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

            // Use if-else chain (primitive int comparison — no autoboxing)
            // Note: countdown warnings use broadcastCountdown() which respects
            // the countdown-global setting (sends only to recipients if false)
            if (remaining == 10) {
                broadcastCountdown(ColorScheme.c("§e⚠ §6WARNING: §f10 seconds before the coordinates get announced!"));
            } else if (remaining == 5) {
                broadcastCountdown(ColorScheme.c("§e⚠ §65 §fseconds before the coordinates get announced"));
            } else if (remaining == 4) {
                broadcastCountdown(ColorScheme.c("§e⚠ §e4"));
            } else if (remaining == 3) {
                broadcastCountdown(ColorScheme.c("§e⚠ §63"));
            } else if (remaining == 2) {
                broadcastCountdown(ColorScheme.c("§e⚠ §62"));
            } else if (remaining == 1) {
                broadcastCountdown(ColorScheme.c("§e⚠ §c1"));
            } else if (remaining <= 0) {
                // Fire announcement and cancel self
                broadcastAnnouncement();
                if (countdownTaskId != null) {
                    Bukkit.getScheduler().cancelTask(countdownTaskId);
                    countdownTaskId = null;
                }
                // Bug fix: increment elapsed here too, so the MAX_ITERATIONS
                // guard at the top can actually engage if cancelTask() fails
                // to remove the task (otherwise elapsed stays pinned at TOTAL
                // and the guard never fires, causing an infinite announce loop).
                elapsed++;
                return;
            }

            elapsed++;
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
