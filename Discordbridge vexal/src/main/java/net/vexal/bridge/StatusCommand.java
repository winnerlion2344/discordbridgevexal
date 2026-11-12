package net.vexal.bridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * /vexalbridge status|reload|test
 */
public final class StatusCommand implements CommandExecutor, TabCompleter {

    private final VexalBridgePlugin plugin;

    public StatusCommand(VexalBridgePlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("vexalbridge.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> sendStatus(sender);
            case "reload" -> {
                plugin.reloadBridge();
                sender.sendMessage(Component.text("VexalBridge config reloaded.", NamedTextColor.GREEN));
            }
            case "test" -> runTest(sender);
            default -> sendUsage(sender);
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text("Usage: /vexalbridge <status|reload|test>", NamedTextColor.YELLOW));
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(Component.text("VexalBridge status", NamedTextColor.GOLD));
        sender.sendMessage(Component.text(
                "  bridge-enabled: " + plugin.isBridgeEnabled(),
                NamedTextColor.GRAY
        ));
        sender.sendMessage(Component.text(
                "  base-url: " + plugin.getConfiguredBaseUrl(),
                NamedTextColor.GRAY
        ));
        sender.sendMessage(Component.text(
                "  server-name: " + plugin.getServerName(),
                NamedTextColor.GRAY
        ));
        sender.sendMessage(Component.text(
                "  scheduler: " + plugin.getScheduler().modeName(),
                NamedTextColor.GRAY
        ));
        sender.sendMessage(Component.text(
                "  last-poll: " + plugin.getCommandPoller().getLastPollResult(),
                NamedTextColor.GRAY
        ));
        sender.sendMessage(Component.text(
                "  queue-size: " + plugin.getEventForwarder().queueSize(),
                NamedTextColor.GRAY
        ));
        sender.sendMessage(Component.text(
                "  last-executed-id: " + plugin.getPluginState().getLastExecutedId(),
                NamedTextColor.GRAY
        ));
        sender.sendMessage(Component.text(
                "  poll-in-flight: " + plugin.getCommandPoller().isInFlight(),
                NamedTextColor.GRAY
        ));
    }

    private void runTest(CommandSender sender) {
        if (!plugin.isBridgeEnabled()) {
            sender.sendMessage(Component.text(
                    "Bridge is disabled (check bot.base-url / bot.token).",
                    NamedTextColor.RED
            ));
            return;
        }
        sender.sendMessage(Component.text("Sending test event...", NamedTextColor.YELLOW));
        plugin.getScheduler().runAsync(() -> {
            com.google.gson.JsonObject body = new com.google.gson.JsonObject();
            body.addProperty("type", "custom");
            body.add("player", null);
            body.addProperty("message", "VexalBridge test from " + plugin.getServerName());
            body.addProperty("server", plugin.getServerName());
            HttpTransport.HttpResult result = plugin.getHttpTransport().postJson("/minecraft/events", body);
            plugin.getScheduler().runGlobal(() -> {
                if (result.networkError) {
                    sender.sendMessage(Component.text(
                            "Test failed: network error (" + result.error + ")",
                            NamedTextColor.RED
                    ));
                } else {
                    sender.sendMessage(Component.text(
                            "Test HTTP status: " + result.statusCode,
                            result.success() ? NamedTextColor.GREEN : NamedTextColor.RED
                    ));
                }
            });
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("vexalbridge.admin")) {
            return out;
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String option : List.of("status", "reload", "test")) {
                if (option.startsWith(prefix)) {
                    out.add(option);
                }
            }
        }
        return out;
    }
}
