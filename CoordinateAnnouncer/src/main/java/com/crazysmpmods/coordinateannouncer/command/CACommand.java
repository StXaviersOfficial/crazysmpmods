package com.crazysmpmods.coordinateannouncer.command;

import com.crazysmpmods.coordinateannouncer.CoordinateAnnouncer;
import com.crazysmpmods.coordinateannouncer.config.PluginConfig;
import com.crazysmpmods.coordinateannouncer.model.CustomPlayer;
import com.crazysmpmods.coordinateannouncer.model.DelayUnit;
import com.crazysmpmods.coordinateannouncer.util.ColorScheme;
import com.crazysmpmods.coordinateannouncer.util.NpcFilter;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Handles /ca and /coordinateannouncer.
 *
 * Permission: coordinateannouncer.admin (default: OP — see plugin.yml).
 * Tab-completion is provided for all subcommands.
 */
public class CACommand implements CommandExecutor, TabCompleter {

    private final CoordinateAnnouncer plugin;

    public CACommand(@NotNull CoordinateAnnouncer plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {

        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "toggle" -> handleToggle(sender);
            case "gui"    -> handleGui(sender);
            case "delay"  -> handleDelay(sender, args);
            case "player" -> handlePlayer(sender, args);
            case "mode"   -> handleMode(sender, args);
            case "offline"-> handleOffline(sender, args);
            case "info"   -> handleInfo(sender);
            case "reload" -> handleReload(sender);
            case "help"   -> sendHelp(sender, label);
            default -> {
                sender.sendMessage(ColorScheme.error("Unknown subcommand: §e" + sub));
                sendHelp(sender, label);
            }
        }
        return true;
    }

    // ── Subcommands ───────────────────────────────────────────────────────

    private void handleToggle(@NotNull CommandSender sender) {
        PluginConfig cfg = plugin.getPluginConfig();
        boolean newState = !cfg.isEnabled();
        cfg.setEnabled(newState);

        if (newState) {
            plugin.getAnnouncementManager().start();
            sender.sendMessage(ColorScheme.success("Coordinate Announcer §aENABLED§f."));
            sender.sendMessage("§7Delay: §e" + cfg.getDelayValue() + " " + cfg.getDelayUnit().displayName()
                    + " §7| Mode: §e" + cfg.getMode()
                    + " §7| Offline: §e" + cfg.getOfflineHandling());
            sender.sendMessage("§7First announcement in §e" + cfg.getDelayValue() + " "
                    + cfg.getDelayUnit().displayName().toLowerCase() + "§7 (with 10s countdown).");
        } else {
            plugin.getAnnouncementManager().stop();
            sender.sendMessage(ColorScheme.info("Coordinate Announcer §cDISABLED§f."));
            sender.sendMessage("§7Any running countdown has been cancelled.");
        }
    }

    private void handleGui(@NotNull CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ColorScheme.error("GUI can only be opened by a player."));
            return;
        }
        plugin.getGuiManager().openMain(p);
        p.sendMessage(ColorScheme.info("Opening Coordinate Announcer menu..."));
    }

    private void handleDelay(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ColorScheme.error("Usage: §e/ca delay <value> <unit>"));
            sender.sendMessage("§7Units: §eseconds, minutes, hours, days§7 (or s/m/h/d)");
            return;
        }
        long value;
        try {
            value = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ColorScheme.error("Invalid number: §e" + args[1]));
            return;
        }
        DelayUnit unit = parseUnit(args[2]);
        if (unit == null) {
            sender.sendMessage(ColorScheme.error("Invalid unit: §e" + args[2]
                    + "§f. Use: §eseconds, minutes, hours, days"));
            return;
        }
        if (value <= 0) {
            sender.sendMessage(ColorScheme.error("Delay must be positive."));
            return;
        }
        long seconds = unit.toSeconds(value);
        if (seconds < 15) {
            sender.sendMessage(ColorScheme.error("Delay too short: §e" + seconds + "s§f. Minimum is §e15s§f (countdown needs 11s)."));
            return;
        }

        plugin.getPluginConfig().setDelay(value, unit);
        sender.sendMessage(ColorScheme.success("Delay set to §e" + value + " " + unit.displayName()
                + "§f (" + seconds + "s)."));

        // Restart the task if currently enabled
        if (plugin.getPluginConfig().isEnabled()) {
            plugin.getAnnouncementManager().restart();
            sender.sendMessage(ColorScheme.info("Announcement task restarted with new delay."));
        }
    }

    private void handlePlayer(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ColorScheme.error("Usage: §e/ca player <add|remove|list|clear> [name]"));
            return;
        }
        String action = args[1].toLowerCase();
        switch (action) {
            case "add" -> {
                if (args.length < 3) {
                    sender.sendMessage(ColorScheme.error("Usage: §e/ca player add <name>"));
                    return;
                }
                String name = args[2];
                UUID uuid = NpcFilter.lookupUuid(name);
                if (uuid == null) {
                    sender.sendMessage(ColorScheme.error("Player §e" + name + "§f not found."));
                    return;
                }
                // Get the canonical name
                String canonicalName = name;
                Player online = Bukkit.getPlayer(uuid);
                if (online != null) canonicalName = online.getName();
                else {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                    if (op.getName() != null) canonicalName = op.getName();
                }

                boolean added = plugin.getPluginConfig().addCustomPlayer(new CustomPlayer(uuid, canonicalName));
                if (added) {
                    sender.sendMessage(ColorScheme.success("Added §e" + canonicalName + "§f to custom list."));
                } else {
                    sender.sendMessage(ColorScheme.info("§e" + canonicalName + "§f is already on the custom list."));
                }
            }
            case "remove" -> {
                if (args.length < 3) {
                    sender.sendMessage(ColorScheme.error("Usage: §e/ca player remove <name>"));
                    return;
                }
                String name = args[2];
                boolean removed = plugin.getPluginConfig().removeCustomPlayerByName(name);
                if (removed) {
                    sender.sendMessage(ColorScheme.success("Removed §e" + name + "§f from custom list."));
                } else {
                    sender.sendMessage(ColorScheme.error("§e" + name + "§f is not on the custom list."));
                }
            }
            case "list" -> {
                var list = plugin.getPluginConfig().getCustomPlayers();
                if (list.isEmpty()) {
                    sender.sendMessage(ColorScheme.info("Custom player list is empty."));
                    return;
                }
                sender.sendMessage(ColorScheme.PRIMARY + "§lCustom Players §7(" + list.size() + "):");
                for (CustomPlayer cp : list) {
                    Player online = Bukkit.getPlayer(cp.uuid());
                    String status = (online != null && online.isOnline()) ? "§a[ONLINE]" : "§c[OFFLINE]";
                    sender.sendMessage("§e" + cp.name() + " §7" + status + " §8" + cp.uuid());
                }
            }
            case "clear" -> {
                int n = plugin.getPluginConfig().getCustomPlayers().size();
                plugin.getPluginConfig().clearCustomPlayers();
                sender.sendMessage(ColorScheme.success("Cleared §e" + n + "§f players from custom list."));
            }
            default -> sender.sendMessage(ColorScheme.error("Usage: §e/ca player <add|remove|list|clear> [name]"));
        }
    }

    private void handleMode(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ColorScheme.error("Usage: §e/ca mode <all|custom>"));
            return;
        }
        String m = args[1].toUpperCase();
        try {
            PluginConfig.AnnouncementMode mode = PluginConfig.AnnouncementMode.valueOf(m);
            plugin.getPluginConfig().setMode(mode);
            sender.sendMessage(ColorScheme.success("Mode set to §e" + mode.name() + "§f."));
            if (mode == PluginConfig.AnnouncementMode.CUSTOM
                    && plugin.getPluginConfig().getCustomPlayers().isEmpty()) {
                sender.sendMessage(ColorScheme.warn("§e⚠ §7Custom list is empty — announcements will be skipped. Add players via §e/ca player add <name>§7."));
            }
        } catch (IllegalArgumentException e) {
            sender.sendMessage(ColorScheme.error("Invalid mode: §e" + m + "§f. Use §eall§f or §ecustom§f."));
        }
    }

    private void handleOffline(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ColorScheme.error("Usage: §e/ca offline <show|skip>"));
            return;
        }
        String m = args[1].toUpperCase();
        try {
            PluginConfig.OfflineHandling h = PluginConfig.OfflineHandling.valueOf(m);
            plugin.getPluginConfig().setOfflineHandling(h);
            sender.sendMessage(ColorScheme.success("Offline handling set to §e" + h.name() + "§f."));
            if (h == PluginConfig.OfflineHandling.SHOW) {
                sender.sendMessage("§7Offline players will appear as: §8[OFFLINE] §7name → §8Last known: §fX Y Z dim");
            } else {
                sender.sendMessage("§7Offline players will be silently skipped from announcements.");
            }
        } catch (IllegalArgumentException e) {
            sender.sendMessage(ColorScheme.error("Invalid value: §e" + m + "§f. Use §eshow§f or §eskip§f."));
        }
    }

    private void handleInfo(@NotNull CommandSender sender) {
        PluginConfig cfg = plugin.getPluginConfig();
        sender.sendMessage(ColorScheme.DIVIDER);
        sender.sendMessage(ColorScheme.HEADER);
        sender.sendMessage(ColorScheme.DIVIDER);
        sender.sendMessage("§7Status:        " + (cfg.isEnabled() ? "§aENABLED" : "§cDISABLED"));
        sender.sendMessage("§7Delay:         §e" + cfg.getDelayValue() + " " + cfg.getDelayUnit().displayName()
                + " §7(" + cfg.getDelaySeconds() + "s)");
        sender.sendMessage("§7Mode:          §e" + cfg.getMode());
        sender.sendMessage("§7Offline:       §e" + cfg.getOfflineHandling());
        sender.sendMessage("§7Custom players:§e " + cfg.getCustomPlayers().size());
        sender.sendMessage("§7Cache size:    §e" + plugin.getOfflinePositionCache().size() + " §7players");
        sender.sendMessage("§7NPC filter:    §e" + (cfg.isFilterNpcs() ? "ON" : "OFF"));
        sender.sendMessage("§7Countdown:     §e" + (cfg.isCountdownGlobal() ? "GLOBAL" : "RECIPIENTS ONLY"));
        sender.sendMessage("§7Task running:  §e" + (plugin.getAnnouncementManager().isRunning() ? "YES" : "NO"));
        sender.sendMessage(ColorScheme.DIVIDER);
    }

    private void handleReload(@NotNull CommandSender sender) {
        sender.sendMessage(ColorScheme.info("Reloading config..."));
        plugin.getAnnouncementManager().stop();
        try {
            plugin.getPluginConfig().load();
            plugin.getOfflinePositionCache().load();
        } catch (Exception e) {
            sender.sendMessage(ColorScheme.error("Reload failed: §e" + e.getMessage()));
            return;
        }
        if (plugin.getPluginConfig().isEnabled()) {
            plugin.getAnnouncementManager().start();
        }
        sender.sendMessage(ColorScheme.success("Config reloaded."));
    }

    // ── Help ──────────────────────────────────────────────────────────────

    private void sendHelp(@NotNull CommandSender sender, @NotNull String label) {
        sender.sendMessage(ColorScheme.DIVIDER);
        sender.sendMessage(ColorScheme.HEADER);
        sender.sendMessage(ColorScheme.DIVIDER);
        sender.sendMessage("§e/" + label + " toggle §7- Enable/disable announcements");
        sender.sendMessage("§e/" + label + " gui §7- Open the chest customization menu");
        sender.sendMessage("§e/" + label + " delay <value> <unit> §7- Set delay (units: seconds, minutes, hours, days)");
        sender.sendMessage("§e/" + label + " player add <name> §7- Add player to custom list");
        sender.sendMessage("§e/" + label + " player remove <name> §7- Remove player from custom list");
        sender.sendMessage("§e/" + label + " player list §7- List custom players");
        sender.sendMessage("§e/" + label + " player clear §7- Clear custom player list");
        sender.sendMessage("§e/" + label + " mode all §7- Announce ALL online players");
        sender.sendMessage("§e/" + label + " mode custom §7- Announce ONLY custom-listed players");
        sender.sendMessage("§e/" + label + " offline show §7- Show offline players with last-known coords");
        sender.sendMessage("§e/" + label + " offline skip §7- Skip offline players silently");
        sender.sendMessage("§e/" + label + " info §7- Show current settings");
        sender.sendMessage("§e/" + label + " reload §7- Reload config from disk");
        sender.sendMessage("§e/" + label + " help §7- Show this help");
        sender.sendMessage(ColorScheme.DIVIDER);
    }

    // ── Tab completion ───────────────────────────────────────────────────

    private static final List<String> SUBS = Arrays.asList(
            "toggle", "gui", "delay", "player", "mode", "offline", "info", "reload", "help");
    private static final List<String> PLAYER_SUBS = Arrays.asList(
            "add", "remove", "list", "clear");
    private static final List<String> MODE_SUBS = Arrays.asList("all", "custom");
    private static final List<String> OFFLINE_SUBS = Arrays.asList("show", "skip");
    private static final List<String> UNITS = Arrays.asList(
            "seconds", "minutes", "hours", "days", "s", "m", "h", "d");

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                       @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(SUBS, args[0]);
        }
        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "player"  -> { return filter(PLAYER_SUBS, args[1]); }
                case "mode"    -> { return filter(MODE_SUBS, args[1]); }
                case "offline" -> { return filter(OFFLINE_SUBS, args[1]); }
            }
        }
        if (args.length == 3) {
            switch (args[0].toLowerCase()) {
                case "delay" -> { return filter(UNITS, args[2]); }
                case "player" -> {
                    if (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove")) {
                        // Suggest online players
                        List<String> names = new ArrayList<>();
                        for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
                        // Also add offline players from the custom list for remove
                        if (args[1].equalsIgnoreCase("remove")) {
                            for (CustomPlayer cp : plugin.getPluginConfig().getCustomPlayers()) {
                                if (!names.contains(cp.name())) names.add(cp.name());
                            }
                        }
                        return filter(names, args[2]);
                    }
                }
            }
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        return options.stream()
                .filter(o -> o.toLowerCase().startsWith(lower))
                .collect(Collectors.toList());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private DelayUnit parseUnit(String s) {
        if (s == null) return null;
        return switch (s.toLowerCase()) {
            case "seconds", "second", "secs", "sec", "s" -> DelayUnit.SECONDS;
            case "minutes", "minute", "mins", "min", "m" -> DelayUnit.MINUTES;
            case "hours",   "hour",   "hrs",  "hr",  "h" -> DelayUnit.HOURS;
            case "days",    "day",    "d"           -> DelayUnit.DAYS;
            default -> null;
        };
    }
}
