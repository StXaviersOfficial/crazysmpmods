package com.crazysmpmods.coordinateannouncer.gui;

import com.crazysmpmods.coordinateannouncer.CoordinateAnnouncer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * Listens for the next chat message from a specific player.
 * Used by the DelayGUI's "click value to type" feature.
 *
 * Uses Paper's Adventure-native AsyncChatEvent (not the deprecated
 * AsyncPlayerChatEvent). This ensures compatibility with modern Paper
 * and works correctly with Geyser (Bedrock) players.
 *
 * Properly self-unregisters after the first message OR on player quit
 * (prevents the memory leak that the original version had).
 *
 * The callback runs on the MAIN thread (not async) so it can safely touch
 * Bukkit API (inventories, scheduler, etc.).
 *
 * Bedrock compatibility: Geyser translates Bedrock chat to Java chat packets,
 * so this works for Bedrock players too. The message is extracted as plain
 * text (Adventure Component → String) so color codes are stripped.
 */
public class ChatInputListener implements Listener {

    private final CoordinateAnnouncer plugin;
    private final Player player;
    private final Consumer<String> callback;
    private volatile boolean fired = false;

    public ChatInputListener(@NotNull Player player, @NotNull Consumer<String> callback) {
        this.plugin = CoordinateAnnouncer.getInstance();
        this.player = player;
        this.callback = callback;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncChatEvent e) {
        if (fired) return;
        if (!e.getPlayer().equals(player)) return;

        // Cancel the message so it doesn't broadcast to the whole server
        e.setCancelled(true);
        fired = true;

        // Extract plain text from the Adventure Component (strips colors/formatting)
        final String message = PlainTextComponentSerializer.plainText()
                .serialize(e.message());

        // Run callback on main thread (since it touches Bukkit API)
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                callback.accept(message);
            } catch (Throwable t) {
                plugin.getLogger().warning("ChatInputListener callback threw: " + t.getMessage());
            } finally {
                // Self-unregister (proper cleanup)
                try {
                    org.bukkit.event.HandlerList.unregisterAll(this);
                } catch (Throwable ignored) {}
                // Also clear from GUIManager's tracking map
                plugin.getGuiManager().clearActiveChatListener(player.getUniqueId());
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (!e.getPlayer().equals(player)) return;
        // Player quit before typing — unregister silently
        fired = true;
        try {
            org.bukkit.event.HandlerList.unregisterAll(this);
        } catch (Throwable ignored) {}
        plugin.getGuiManager().clearActiveChatListener(player.getUniqueId());
    }

    public boolean isFired() { return fired; }
}
