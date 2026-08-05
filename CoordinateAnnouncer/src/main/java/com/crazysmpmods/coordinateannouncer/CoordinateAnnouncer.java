package com.crazysmpmods.coordinateannouncer;

import com.crazysmpmods.coordinateannouncer.announcement.AnnouncementManager;
import com.crazysmpmods.coordinateannouncer.announcement.OfflinePositionCache;
import com.crazysmpmods.coordinateannouncer.command.CACommand;
import com.crazysmpmods.coordinateannouncer.config.PluginConfig;
import com.crazysmpmods.coordinateannouncer.gui.GUIManager;
import com.crazysmpmods.coordinateannouncer.listener.PlayerPositionListener;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;

/**
 * CoordinateAnnouncer — Paper 1.21.11 plugin for the CrazySMP server.
 *
 * Periodically broadcasts player coordinates in chat with a 10-second countdown.
 * Highly customizable via chest GUI; settings persist across restarts.
 *
 * @author QuackPlayzYT
 * @version 1.0.0
 */
public final class CoordinateAnnouncer extends JavaPlugin {

    private static CoordinateAnnouncer instance;

    private PluginConfig pluginConfig;
    private OfflinePositionCache offlinePositionCache;
    private AnnouncementManager announcementManager;
    private GUIManager guiManager;

    @Override
    public void onEnable() {
        instance = this;
        long t0 = System.currentTimeMillis();

        // 1) Save default config.yml + data.yml if they don't exist
        saveDefaultConfig();

        // 2) Load all settings (atomic — fails safe to defaults on parse error)
        this.pluginConfig = new PluginConfig(this);
        try {
            this.pluginConfig.load();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to load config.yml — using defaults. Reason: " + e.getMessage(), e);
            this.pluginConfig.resetToDefaults();
        }

        // 3) Initialize the offline-position cache (loads data.yml)
        this.offlinePositionCache = new OfflinePositionCache(this);
        this.offlinePositionCache.load();

        // 4) Initialize the GUI manager
        this.guiManager = new GUIManager(this);

        // 5) Initialize the announcement manager (schedules the repeating task)
        this.announcementManager = new AnnouncementManager(this);
        if (this.pluginConfig.isEnabled()) {
            this.announcementManager.start();
        }

        // 6) Register the /ca command
        CACommand cmd = new CACommand(this);
        if (getCommand("coordinateannouncer") != null) {
            getCommand("coordinateannouncer").setExecutor(cmd);
            getCommand("coordinateannouncer").setTabCompleter(cmd);
        } else {
            getLogger().severe("Command 'coordinateannouncer' not found in plugin.yml — plugin will not work!");
        }

        // 7) Register event listeners
        getServer().getPluginManager().registerEvents(
                new PlayerPositionListener(this), this);

        // 8) Register the GUI click listener
        getServer().getPluginManager().registerEvents(this.guiManager, this);

        getLogger().info("==================================================");
        getLogger().info(" CoordinateAnnouncer v" + getPluginMeta().getVersion() + " enabled.");
        getLogger().info(" Status: " + (pluginConfig.isEnabled() ? "ENABLED" : "DISABLED")
                + " | Mode: " + pluginConfig.getMode()
                + " | Delay: " + pluginConfig.getDelayValue() + " " + pluginConfig.getDelayUnit());
        getLogger().info(" Loaded " + pluginConfig.getCustomPlayers().size() + " custom players.");
        getLogger().info(" Loaded " + offlinePositionCache.size() + " cached last-known positions.");
        getLogger().info(" Startup took " + (System.currentTimeMillis() - t0) + "ms.");
        getLogger().info("==================================================");
    }

    @Override
    public void onDisable() {
        // Cancel any running countdown / announcement tasks
        if (announcementManager != null) {
            announcementManager.stop();
        }
        // Save any pending state
        if (pluginConfig != null) {
            pluginConfig.save();
        }
        if (offlinePositionCache != null) {
            offlinePositionCache.save();
        }
        getLogger().info("CoordinateAnnouncer disabled. Settings persisted.");
    }

    // ── Accessors ──────────────────────────────────────────────────────────

    @NotNull
    public static CoordinateAnnouncer getInstance() {
        return instance;
    }

    @NotNull
    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    @NotNull
    public OfflinePositionCache getOfflinePositionCache() {
        return offlinePositionCache;
    }

    @NotNull
    public AnnouncementManager getAnnouncementManager() {
        return announcementManager;
    }

    @NotNull
    public GUIManager getGuiManager() {
        return guiManager;
    }

    /**
     * Convenience helper: send a color-coded message to a sender.
     * Accepts a §-prefixed legacy string and converts to Adventure Component.
     */
    public void send(@NotNull CommandSender target, @NotNull String message) {
        target.sendMessage(com.crazysmpmods.coordinateannouncer.util.ColorScheme.c(message));
    }
}
