package com.crazysmpmods.coordinateannouncer.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Listens for the next chat message from a specific player.
 * Used by the DelayGUI's "click value to type" feature.
 *
 * Self-unregisters after the first message or player-quit.
 */
public class ChatInputListener implements Listener {

    private final Player player;
    private final Consumer<String> callback;
    private boolean fired = false;

    public ChatInputListener(@NotNull Player player, @NotNull Consumer<String> callback) {
        this.player = player;
        this.callback = callback;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncPlayerChatEvent e) {
        if (fired) return;
        if (!e.getPlayer().equals(player)) return;

        // Cancel the message so it doesn't broadcast to the whole server
        e.setCancelled(true);
        fired = true;

        String message = e.getMessage();
        // Run callback on main thread (since it may touch Bukkit API)
        org.bukkit.Bukkit.getScheduler().runTask(
                org.bukkit.Bukkit.getPluginManager().getPlugin("CoordinateAnnouncer"),
                () -> {
                    try {
                        callback.accept(message);
                    } finally {
                        // Auto-unregister
                        try {
                            org.bukkit.Bukkit.getPluginManager()
                                    .getPlugin("CoordinateAnnouncer")
                                    .getClass(); // sanity
                        } catch (Throwable ignored) {}
                    }
                }
        );
    }

    public boolean isFired() { return fired; }
}
