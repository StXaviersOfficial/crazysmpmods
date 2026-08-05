package com.crazysmpmods.coordinateannouncer.gui;

import com.crazysmpmods.coordinateannouncer.CoordinateAnnouncer;
import com.crazysmpmods.coordinateannouncer.config.PluginConfig;
import com.crazysmpmods.coordinateannouncer.model.CustomPlayer;
import com.crazysmpmods.coordinateannouncer.model.DelayUnit;
import com.crazysmpmods.coordinateannouncer.util.ColorScheme;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Manages all chest GUIs for CoordinateAnnouncer.
 *
 * Three GUIs:
 *   1. MainGUI     — status, delay, mode, players, offline toggle, master toggle, close
 *   2. DelayGUI    — value display + -10/-1/+1/+10 arrows + 4 format buttons (S/M/H/D) + back
 *   3. PlayerListGUI — paginated list of custom players with remove buttons + add hint + back
 *
 * Style: Quackingly-inspired clean layout, color-coded item names, hoverable lore.
 */
public class GUIManager implements Listener {

    private final CoordinateAnnouncer plugin;
    private final Map<UUID, GUISession> sessions = new HashMap<>();
    // Tracks the active chat-input listener per player so we never stack duplicates.
    private final Map<UUID, ChatInputListener> activeChatListeners = new HashMap<>();

    public GUIManager(@NotNull CoordinateAnnouncer plugin) {
        this.plugin = plugin;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  GUI SESSION TRACKING
    // ═══════════════════════════════════════════════════════════════════════

    private static class GUISession {
        GUIType currentType;
        int playerListPage = 0;

        GUISession(GUIType t) { this.currentType = t; }
    }

    private enum GUIType { MAIN, DELAY, PLAYER_LIST }

    // ═══════════════════════════════════════════════════════════════════════
    //  SLOT LAYOUT — MAIN GUI (27 slots, 9×3)
    // ═══════════════════════════════════════════════════════════════════════
    //
    //  Row 0:  [ ] [ ] [ ] [ ] [STATUS] [ ] [ ] [ ] [ ]
    //  Row 1:  [ ] [DELAY] [ ] [MODE] [ ] [PLAYERS] [ ] [OFFLINE] [ ]
    //  Row 2:  [ ] [ ] [ ] [ ] [TOGGLE] [ ] [ ] [ ] [CLOSE]
    //
    private static final int SLOT_STATUS   = 4;
    private static final int SLOT_DELAY    = 10;
    private static final int SLOT_MODE     = 12;
    private static final int SLOT_PLAYERS  = 14;
    private static final int SLOT_OFFLINE  = 16;
    private static final int SLOT_TOGGLE   = 22;
    private static final int SLOT_CLOSE    = 26;

    // ═══════════════════════════════════════════════════════════════════════
    //  SLOT LAYOUT — DELAY GUI (27 slots, 9×3)
    // ═══════════════════════════════════════════════════════════════════════
    //
    //  Row 0:  [ ] [ ] [ ] [ ] [TITLE]  [ ] [ ] [ ] [ ]
    //  Row 1:  [ ] [-10] [-1] [VALUE] [+1] [+10] [ ] [FORMAT] [ ]
    //  Row 2:  [ ] [ ] [ ] [ ] [BACK]  [ ] [ ] [ ] [ ]
    //
    private static final int SLOT_D_TITLE   = 4;
    private static final int SLOT_D_MINUS10 = 10;
    private static final int SLOT_D_MINUS1  = 11;
    private static final int SLOT_D_VALUE   = 12;
    private static final int SLOT_D_PLUS1   = 13;
    private static final int SLOT_D_PLUS10  = 14;
    private static final int SLOT_D_FORMAT  = 16;
    private static final int SLOT_D_BACK    = 22;

    // ═══════════════════════════════════════════════════════════════════════
    //  SLOT LAYOUT — PLAYER LIST GUI (54 slots, 9×6)
    // ═══════════════════════════════════════════════════════════════════════
    //
    //  Row 0:  [TITLE in slot 4]
    //  Rows 1-4: player head slots 9..44 (36 slots = 36 players per page)
    //  Row 5:  [PREV in slot 45] [ADD-HINT in slot 49] [NEXT in slot 53] [CLOSE in slot 53]
    //
    private static final int SLOT_P_TITLE = 4;
    private static final int SLOT_P_PREV  = 45;
    private static final int SLOT_P_NEXT  = 53;
    private static final int SLOT_P_CLOSE = 49;
    private static final int PLAYER_SLOTS_START = 9;
    private static final int PLAYER_SLOTS_END   = 44;
    private static final int PLAYERS_PER_PAGE   = PLAYER_SLOTS_END - PLAYER_SLOTS_START + 1; // 36

    // ═══════════════════════════════════════════════════════════════════════
    //  OPEN HANDLERS
    // ═══════════════════════════════════════════════════════════════════════

    public void openMain(@NotNull Player p) {
        GUISession session = sessions.computeIfAbsent(p.getUniqueId(), k -> new GUISession(GUIType.MAIN));
        session.currentType = GUIType.MAIN;

        Inventory inv = Bukkit.createInventory(new GUIHolder(GUIType.MAIN), 27,
                Component.text(ColorScheme.PRIMARY + "§lCoordinate Announcer"));

        // Status indicator
        boolean on = plugin.getPluginConfig().isEnabled();
        inv.setItem(SLOT_STATUS, buildItem(
                on ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                (on ? "§a§lENABLED" : "§c§lDISABLED"),
                "§7Current status",
                "§7Click §eToggle §7below to change"));

        // Delay selector
        PluginConfig cfg = plugin.getPluginConfig();
        inv.setItem(SLOT_DELAY, buildItem(Material.CLOCK,
                "§b§lDelay: §f" + cfg.getDelayValue() + " " + cfg.getDelayUnit().displayName(),
                "§7Time between announcements",
                "§7Currently: §e" + cfg.getDelaySeconds() + " seconds",
                "",
                "§e➤ Click to customize"));

        // Mode selector
        inv.setItem(SLOT_MODE, buildItem(
                cfg.getMode() == PluginConfig.AnnouncementMode.ALL ? Material.PLAYER_HEAD : Material.WRITABLE_BOOK,
                "§b§lMode: §f" + cfg.getMode().name(),
                "§7ALL §8→ §7announce every online player",
                "§7CUSTOM §8→ §7only players on the custom list",
                "",
                "§e➤ Click to switch"));

        // Custom player list (always visible, even in ALL mode — so user can pre-stage)
        int n = cfg.getCustomPlayers().size();
        inv.setItem(SLOT_PLAYERS, buildItem(Material.BOOK,
                "§b§lCustom Players §7(§e" + n + "§7)",
                "§7Manage the custom player list",
                "§7Used when §eMode = CUSTOM",
                "",
                "§e➤ Click to open list"));

        // Offline handling
        inv.setItem(SLOT_OFFLINE, buildItem(
                cfg.getOfflineHandling() == PluginConfig.OfflineHandling.SHOW ? Material.ENDER_EYE : Material.ENDER_PEARL,
                "§b§lOffline Handling: §f" + cfg.getOfflineHandling().name(),
                "§7SHOW §8→ §7display offline players with last-known coords",
                "§7SKIP §8→ §7silently omit offline players",
                "",
                "§e➤ Click to toggle"));

        // Master toggle (big button in the center bottom)
        inv.setItem(SLOT_TOGGLE, buildItem(
                on ? Material.REDSTONE_TORCH : Material.TORCH,
                (on ? "§c§lClick to DISABLE" : "§a§lClick to ENABLE"),
                "§7Master switch for announcements"));

        // Close
        inv.setItem(SLOT_CLOSE, buildItem(Material.BARRIER,
                "§c§lClose",
                "§7Close this menu"));

        // Fill empty slots with light-gray stained glass for visual structure
        fillEmpty(inv);

        p.openInventory(inv);
    }

    public void openDelay(@NotNull Player p) {
        GUISession session = sessions.computeIfAbsent(p.getUniqueId(), k -> new GUISession(GUIType.DELAY));
        session.currentType = GUIType.DELAY;

        Inventory inv = Bukkit.createInventory(new GUIHolder(GUIType.DELAY), 27,
                Component.text(ColorScheme.PRIMARY + "§lDelay Settings"));

        PluginConfig cfg = plugin.getPluginConfig();

        // Title
        inv.setItem(SLOT_D_TITLE, buildItem(Material.PAPER,
                "§d§lDelay Configuration",
                "§7Set the time between announcements",
                "§7Minimum: §e15 seconds"));

        // -10 button
        inv.setItem(SLOT_D_MINUS10, buildItem(Material.RED_STAINED_GLASS_PANE,
                "§c§l-10",
                "§7Decrease value by 10"));

        // -1 button
        inv.setItem(SLOT_D_MINUS1, buildItem(Material.RED_DYE,
                "§c§l-1",
                "§7Decrease value by 1"));

        // Current value
        inv.setItem(SLOT_D_VALUE, buildItem(Material.WRITABLE_BOOK,
                "§e§lValue: §f" + cfg.getDelayValue(),
                "§7Current delay value",
                "§7Unit: §b" + cfg.getDelayUnit().displayName(),
                "§7Total: §e" + cfg.getDelaySeconds() + " seconds",
                "",
                "§e➤ Click to type a value in chat"));

        // +1 button
        inv.setItem(SLOT_D_PLUS1, buildItem(Material.LIME_DYE,
                "§a§l+1",
                "§7Increase value by 1"));

        // +10 button
        inv.setItem(SLOT_D_PLUS10, buildItem(Material.LIME_STAINED_GLASS_PANE,
                "§a§l+10",
                "§7Increase value by 10"));

        // Format cycle button (changes unit when clicked)
        DelayUnit nextUnit = cfg.getDelayUnit().next();
        inv.setItem(SLOT_D_FORMAT, buildItem(Material.COMPARATOR,
                "§b§lFormat: §f" + cfg.getDelayUnit().displayName(),
                "§7Click to change to: §e" + nextUnit.displayName(),
                "",
                "§7Cycles: §eSeconds §7→ §eMinutes §7→ §eHours §7→ §eDays"));

        // Back
        inv.setItem(SLOT_D_BACK, buildItem(Material.ARROW,
                "§7§l← Back",
                "§7Return to main menu"));

        fillEmpty(inv);
        p.openInventory(inv);
    }

    public void openPlayerList(@NotNull Player p) {
        openPlayerList(p, 0);
    }

    public void openPlayerList(@NotNull Player p, int page) {
        GUISession session = sessions.computeIfAbsent(p.getUniqueId(), k -> new GUISession(GUIType.PLAYER_LIST));
        session.currentType = GUIType.PLAYER_LIST;

        List<CustomPlayer> players = plugin.getPluginConfig().getCustomPlayers();
        int totalPages = Math.max(1, (int) Math.ceil((double) players.size() / PLAYERS_PER_PAGE));
        if (page < 0) page = 0;
        if (page >= totalPages) page = totalPages - 1;
        session.playerListPage = page;

        Inventory inv = Bukkit.createInventory(new GUIHolder(GUIType.PLAYER_LIST), 54,
                Component.text(ColorScheme.PRIMARY + "§lCustom Players §7(Page " + (page+1) + "/" + totalPages + ")"));

        // Title
        inv.setItem(SLOT_P_TITLE, buildItem(Material.BOOK,
                "§d§lCustom Player List",
                "§7Total: §e" + players.size() + " §7players",
                "§7Mode: §e" + plugin.getPluginConfig().getMode(),
                "",
                "§e➤ Add players via §e/ca player add <name>",
                "§e➤ Click a head to §cremove"));

        // Page players
        int start = page * PLAYERS_PER_PAGE;
        int end = Math.min(start + PLAYERS_PER_PAGE, players.size());
        int slot = PLAYER_SLOTS_START;
        for (int i = start; i < end; i++) {
            CustomPlayer cp = players.get(i);
            OfflinePlayer op = Bukkit.getOfflinePlayer(cp.uuid());
            boolean online = (Bukkit.getPlayer(cp.uuid()) != null);

            ItemStack head = buildPlayerHead(op,
                    (online ? "§a§l" : "§7§l") + cp.name(),
                    "§7UUID: §8" + cp.uuid(),
                    "§7Status: " + (online ? "§aONLINE" : "§cOFFLINE"),
                    "",
                    "§c➤ Click to §c§lREMOVE");
            if (slot <= PLAYER_SLOTS_END) {
                inv.setItem(slot, head);
                slot++;
            }
        }

        // Prev / Next / Close
        if (page > 0) {
            inv.setItem(SLOT_P_PREV, buildItem(Material.ARROW,
                    "§7§l← Previous Page",
                    "§7Go to page §e" + page));
        }
        if (page < totalPages - 1) {
            inv.setItem(SLOT_P_NEXT, buildItem(Material.ARROW,
                    "§7§lNext Page →",
                    "§7Go to page §e" + (page + 2)));
        }
        inv.setItem(SLOT_P_CLOSE, buildItem(Material.BARRIER,
                "§c§lBack to Main",
                "§7Return to main menu"));

        // Fill empty
        fillEmpty(inv);
        p.openInventory(inv);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  CLICK HANDLER
    // ═══════════════════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        // Identify if the click happened in one of our GUIs by checking the
        // top inventory's holder (not getClickedInventory — that's null for
        // clicks outside any inventory, and may be the player's inventory
        // for shift-clicks).
        Inventory top = e.getView().getTopInventory();
        if (top == null) return;
        if (!(top.getHolder() instanceof GUIHolder gh)) return;

        // Always cancel — our GUIs are read-only (no item movement allowed)
        e.setCancelled(true);

        // If user clicked the bottom (player) inventory while our GUI is open,
        // ignore (we already cancelled the event to prevent shift-click exploits).
        if (e.getClickedInventory() == null
                || e.getClickedInventory().equals(e.getView().getBottomInventory())) {
            return;
        }

        if (!(e.getWhoClicked() instanceof Player p)) return;

        int slot = e.getRawSlot();
        // Make sure slot is within the top inventory (not player inventory)
        if (slot >= top.getSize()) return;

        GUIType type = gh.type();
        switch (type) {
            case MAIN -> handleMainClick(p, slot);
            case DELAY -> handleDelayClick(p, slot);
            case PLAYER_LIST -> handlePlayerListClick(p, slot);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        // Cancel drags in our GUIs too
        Inventory top = e.getView().getTopInventory();
        if (top != null && top.getHolder() instanceof GUIHolder) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        // Sessions are kept alive (so reopening is fast). They get cleaned
        // implicitly on player quit via PlayerQuitEvent listener below.
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        sessions.remove(e.getPlayer().getUniqueId());
        // Also remove any pending chat-input listener for this player
        ChatInputListener listener = activeChatListeners.remove(e.getPlayer().getUniqueId());
        if (listener != null) {
            try {
                org.bukkit.event.HandlerList.unregisterAll(listener);
            } catch (Throwable ignored) {}
        }
    }

    // ── Main GUI click handler ────────────────────────────────────────────

    private void handleMainClick(@NotNull Player p, int slot) {
        PluginConfig cfg = plugin.getPluginConfig();
        switch (slot) {
            case SLOT_STATUS -> {
                // Status is read-only — just play a sound for feedback
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
                p.sendMessage(ColorScheme.info("Click §eToggle §7below to change status."));
            }
            case SLOT_DELAY -> { p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.5f, 1.0f); openDelay(p); }
            case SLOT_MODE -> {
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
                PluginConfig.AnnouncementMode newMode = (cfg.getMode() == PluginConfig.AnnouncementMode.ALL)
                        ? PluginConfig.AnnouncementMode.CUSTOM
                        : PluginConfig.AnnouncementMode.ALL;
                cfg.setMode(newMode);
                p.sendMessage(ColorScheme.success("Mode set to §e" + newMode.name() + "§f."));
                if (newMode == PluginConfig.AnnouncementMode.CUSTOM
                        && cfg.getCustomPlayers().isEmpty()) {
                    p.sendMessage(ColorScheme.warn("§e⚠ §7Custom list is empty — use §e/ca player add <name>§7."));
                }
                openMain(p);
            }
            case SLOT_PLAYERS -> { p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.5f, 1.0f); openPlayerList(p); }
            case SLOT_OFFLINE -> {
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
                PluginConfig.OfflineHandling newH = (cfg.getOfflineHandling() == PluginConfig.OfflineHandling.SHOW)
                        ? PluginConfig.OfflineHandling.SKIP
                        : PluginConfig.OfflineHandling.SHOW;
                cfg.setOfflineHandling(newH);
                p.sendMessage(ColorScheme.success("Offline handling set to §e" + newH.name() + "§f."));
                openMain(p);
            }
            case SLOT_TOGGLE -> {
                boolean newState = !cfg.isEnabled();
                cfg.setEnabled(newState);
                if (newState) {
                    plugin.getAnnouncementManager().start();
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.5f);
                    p.sendMessage(ColorScheme.success("Coordinate Announcer §aENABLED§f."));
                } else {
                    plugin.getAnnouncementManager().stop();
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 0.5f);
                    p.sendMessage(ColorScheme.info("Coordinate Announcer §cDISABLED§f."));
                }
                openMain(p);
            }
            case SLOT_CLOSE -> { p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.5f, 1.0f); p.closeInventory(); }
            default -> {
                // Clicked a filler slot — play a soft "deny" sound
                p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.3f, 0.8f);
            }
        }
    }

    // ── Delay GUI click handler ───────────────────────────────────────────

    private void handleDelayClick(@NotNull Player p, int slot) {
        PluginConfig cfg = plugin.getPluginConfig();
        long currentValue = cfg.getDelayValue();
        DelayUnit currentUnit = cfg.getDelayUnit();

        switch (slot) {
            case SLOT_D_MINUS10 -> adjustDelay(p, currentValue - 10, currentUnit);
            case SLOT_D_MINUS1  -> adjustDelay(p, currentValue - 1, currentUnit);
            case SLOT_D_PLUS1   -> adjustDelay(p, currentValue + 1, currentUnit);
            case SLOT_D_PLUS10  -> adjustDelay(p, currentValue + 10, currentUnit);
            case SLOT_D_VALUE   -> {
                // Switch to chat-input mode.
                // First, unregister any previous chat-input listener for this player
                // (prevents stacking if user clicks the value paper multiple times).
                unregisterChatListener(p);

                p.closeInventory();
                p.sendMessage(ColorScheme.PRIMARY + "§lType the new delay value in chat:");
                p.sendMessage("§7Type a number (e.g., §e60§7) or §ecancel§7 to abort.");
                ChatInputListener listener = new ChatInputListener(p, value -> {
                    try {
                        // Handle "cancel"
                        if (value.trim().equalsIgnoreCase("cancel")) {
                            p.sendMessage(ColorScheme.info("Cancelled — delay unchanged."));
                            openDelay(p);
                            return;
                        }
                        long v = Long.parseLong(value.trim());
                        if (v <= 0) {
                            p.sendMessage(ColorScheme.error("Value must be positive."));
                        } else if (currentUnit.toSeconds(v) < 15) {
                            p.sendMessage(ColorScheme.error("Too short — minimum is §e15 " + currentUnit.shortName() + "§f (15s)."));
                        } else {
                            cfg.setDelay(v, currentUnit);
                            p.sendMessage(ColorScheme.success("Delay value set to §e" + v + "§f."));
                            if (cfg.isEnabled()) {
                                plugin.getAnnouncementManager().restart();
                                p.sendMessage(ColorScheme.info("Announcement task restarted."));
                            }
                        }
                    } catch (NumberFormatException ex) {
                        p.sendMessage(ColorScheme.error("Invalid number: §e" + value
                                + "§f. Type a number or §ecancel§f."));
                    }
                    openDelay(p);
                });
                activeChatListeners.put(p.getUniqueId(), listener);
                plugin.getServer().getPluginManager().registerEvents(listener, plugin);
            }
            case SLOT_D_FORMAT -> {
                DelayUnit next = currentUnit.next();
                long newValue = currentValue;
                // Clamp if switching makes delay too short
                if (next.toSeconds(newValue) < 15) {
                    newValue = 15;
                    p.sendMessage(ColorScheme.warn("§e⚠ §7Value clamped to §e15§f to maintain the 15s minimum."));
                }
                cfg.setDelay(newValue, next);
                p.sendMessage(ColorScheme.success("Format changed to §e" + next.displayName() + "§f."));
                openDelay(p);
            }
            case SLOT_D_BACK -> { p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.5f, 1.0f); openMain(p); }
            default -> p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.3f, 0.8f);
        }
    }

    private void adjustDelay(@NotNull Player p, long newValue, @NotNull DelayUnit unit) {
        if (newValue < 1) {
            p.sendMessage(ColorScheme.error("Value cannot be less than 1."));
            return;
        }
        if (unit.toSeconds(newValue) < 15) {
            p.sendMessage(ColorScheme.error("Delay too short — minimum is §e15 " + unit.shortName() + "§f (15s)."));
            return;
        }
        plugin.getPluginConfig().setDelay(newValue, unit);
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        openDelay(p);
    }

    // ── Player List GUI click handler ─────────────────────────────────────

    private void handlePlayerListClick(@NotNull Player p, int slot) {
        GUISession session = sessions.get(p.getUniqueId());
        if (session == null) return;

        if (slot == SLOT_P_PREV) {
            openPlayerList(p, session.playerListPage - 1);
            return;
        }
        if (slot == SLOT_P_NEXT) {
            openPlayerList(p, session.playerListPage + 1);
            return;
        }
        if (slot == SLOT_P_CLOSE) {
            openMain(p);
            return;
        }

        // Click on a player head → remove
        if (slot >= PLAYER_SLOTS_START && slot <= PLAYER_SLOTS_END) {
            int index = session.playerListPage * PLAYERS_PER_PAGE + (slot - PLAYER_SLOTS_START);
            List<CustomPlayer> players = plugin.getPluginConfig().getCustomPlayers();
            if (index >= 0 && index < players.size()) {
                CustomPlayer cp = players.get(index);
                boolean removed = plugin.getPluginConfig().removeCustomPlayerByName(cp.name());
                if (removed) {
                    p.sendMessage(ColorScheme.success("Removed §e" + cp.name() + "§f from custom list."));
                    p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.0f);
                    openPlayerList(p, session.playerListPage);
                }
            } else {
                // Empty slot in player-head area
                p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.3f, 0.8f);
            }
        } else {
            // Clicked a filler slot — play a soft "deny" sound
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.3f, 0.8f);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private ItemStack buildItem(@NotNull Material mat, @NotNull String name, @NotNull String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name));
            if (lore.length > 0) {
                List<Component> loreList = new ArrayList<>();
                for (String line : lore) loreList.add(Component.text(line));
                meta.lore(loreList);
            }
            meta.addItemFlags(ItemFlag.values());
            item.setItemMeta(meta);
        }
        return item;
    }

    @SuppressWarnings("deprecation")
    private ItemStack buildPlayerHead(@NotNull OfflinePlayer op, @NotNull String name, @NotNull String... lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(op);
            meta.displayName(Component.text(name));
            if (lore.length > 0) {
                List<Component> loreList = new ArrayList<>();
                for (String line : lore) loreList.add(Component.text(line));
                meta.lore(loreList);
            }
            meta.addItemFlags(ItemFlag.values());
            item.setItemMeta(meta);
        }
        return item;
    }

    private void fillEmpty(@NotNull Inventory inv) {
        ItemStack filler = buildItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler);
        }
    }

    /**
     * Unregister any active ChatInputListener for the given player (prevents leak).
     */
    private void unregisterChatListener(@NotNull Player p) {
        ChatInputListener old = activeChatListeners.remove(p.getUniqueId());
        if (old != null) {
            try {
                org.bukkit.event.HandlerList.unregisterAll(old);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Called by ChatInputListener after it self-unregisters, to clear our tracking map.
     */
    public void clearActiveChatListener(@NotNull UUID uuid) {
        activeChatListeners.remove(uuid);
    }

    // ── GUI Holder (used to identify our inventories) ─────────────────────

    private static class GUIHolder implements InventoryHolder {
        private final GUIType type;
        GUIHolder(GUIType type) { this.type = type; }
        public GUIType type() { return type; }
        @Override public @NotNull Inventory getInventory() {
            // Required by interface; we never actually use this.
            return Bukkit.createInventory(null, 9);
        }
    }
}
