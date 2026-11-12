package net.vexal.bridge;

import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minecraft ↔ HTTP bot bridge for Folia / Paper 1.21+.
 */
public final class VexalBridgePlugin extends JavaPlugin {

    private Scheduler scheduler;
    private HttpTransport httpTransport;
    private PluginState pluginState;
    private EventForwarder eventForwarder;
    private CommandExecutor commandExecutor;
    private CommandPoller commandPoller;
    private Listeners listeners;

    private final AtomicBoolean bridgeCommandActive = new AtomicBoolean(false);

    private volatile boolean bridgeEnabled;
    private volatile boolean debug;
    private volatile int httpTimeoutMs = 5000;
    private volatile String serverName = "My Server";
    private volatile String configuredBaseUrl = "";

    @Override
    public void onEnable() {
        saveDefaultConfig();

        scheduler = new Scheduler(this);
        httpTransport = new HttpTransport(getLogger(), false);
        pluginState = new PluginState(getDataFolder().toPath(), getLogger());
        pluginState.load();

        eventForwarder = new EventForwarder(this, httpTransport, scheduler);
        commandExecutor = new CommandExecutor(this, scheduler);
        commandPoller = new CommandPoller(this, httpTransport, commandExecutor, pluginState, scheduler);
        listeners = new Listeners(this, eventForwarder);
        listeners.registerAll();

        StatusCommand statusCommand = new StatusCommand(this);
        PluginCommand command = getCommand("vexalbridge");
        if (command != null) {
            command.setExecutor(statusCommand);
            command.setTabCompleter(statusCommand);
        } else {
            getLogger().warning("Command 'vexalbridge' missing from plugin.yml");
        }

        applyConfig();
        getLogger().info("VexalBridge enabled (scheduler=" + scheduler.modeName()
                + ", bridge=" + (bridgeEnabled ? "on" : "off") + ")");
    }

    @Override
    public void onDisable() {
        if (commandPoller != null) {
            commandPoller.stop();
        }
        if (scheduler != null) {
            scheduler.cancelAll();
        }
        if (eventForwarder != null && bridgeEnabled) {
            eventForwarder.flushBlocking(2000L);
        }
        if (pluginState != null) {
            pluginState.save();
        }
        if (listeners != null) {
            listeners.removeConsoleAppender();
        }
        getLogger().info("VexalBridge disabled");
    }

    public void reloadBridge() {
        if (commandPoller != null) {
            commandPoller.stop();
        }
        if (scheduler != null) {
            scheduler.cancelAll();
            scheduler.reset();
        }
        reloadConfig();
        applyConfig();
        getLogger().info("VexalBridge reloaded (bridge=" + (bridgeEnabled ? "on" : "off") + ")");
    }

    private void applyConfig() {
        FileConfiguration config = getConfig();

        String baseUrl = config.getString("bot.base-url", "http://CHANGE-ME:3000");
        String token = config.getString("bot.token", "CHANGE-ME");
        int timeoutMs = config.getInt("bot.timeout-ms", 5000);
        String name = config.getString("server.name", "My Server");

        boolean chat = config.getBoolean("events.chat", true);
        boolean join = config.getBoolean("events.join", true);
        boolean leave = config.getBoolean("events.leave", true);
        boolean death = config.getBoolean("events.death", true);
        boolean console = config.getBoolean("events.console", false);

        boolean commandsEnabled = config.getBoolean("commands.enabled", true);
        int pollInterval = config.getInt("commands.poll-interval-seconds", 5);
        int maxPerPoll = config.getInt("commands.max-per-poll", 10);
        debug = config.getBoolean("debug", false);

        httpTimeoutMs = timeoutMs <= 0 ? 5000 : timeoutMs;
        serverName = (name == null || name.isBlank()) ? "My Server" : name;
        configuredBaseUrl = baseUrl == null ? "" : baseUrl;

        boolean urlOk = isConfiguredValue(baseUrl) && looksLikeUrl(baseUrl);
        boolean tokenOk = isConfiguredValue(token);

        if (!urlOk || !tokenOk) {
            bridgeEnabled = false;
            if (!urlOk) {
                getLogger().warning(
                        "VexalBridge HTTP disabled: set a real value for bot.base-url (placeholder/missing)."
                );
            }
            if (!tokenOk) {
                getLogger().warning(
                        "VexalBridge HTTP disabled: set a real value for bot.token (placeholder/missing)."
                );
            }
        } else {
            bridgeEnabled = true;
        }

        httpTransport.configure(
                urlOk ? baseUrl.trim() : "",
                tokenOk ? token.trim() : "",
                httpTimeoutMs,
                debug
        );

        eventForwarder = new EventForwarder(this, httpTransport, scheduler);
        eventForwarder.setServerName(serverName);
        eventForwarder.setEnabled(bridgeEnabled);

        commandExecutor = new CommandExecutor(this, scheduler);
        commandPoller = new CommandPoller(this, httpTransport, commandExecutor, pluginState, scheduler);
        commandPoller.configure(bridgeEnabled && commandsEnabled, pollInterval, maxPerPoll);

        listeners.setForwarder(eventForwarder);
        listeners.configure(chat, join, leave, death, bridgeEnabled && console);

        if (bridgeEnabled && commandsEnabled) {
            commandPoller.start();
        }
    }

    private static boolean isConfiguredValue(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        return !trimmed.contains("CHANGE-ME");
    }

    private static boolean looksLikeUrl(String value) {
        String v = value.trim().toLowerCase();
        return v.startsWith("http://") || v.startsWith("https://");
    }

    public void markBridgeCommandActive(boolean active) {
        bridgeCommandActive.set(active);
    }

    public boolean isBridgeCommandActive() {
        return bridgeCommandActive.get();
    }

    public boolean isBridgeEnabled() {
        return bridgeEnabled;
    }

    public boolean isDebug() {
        return debug;
    }

    public int httpTimeoutMs() {
        return httpTimeoutMs;
    }

    public String getServerName() {
        return serverName;
    }

    public String getConfiguredBaseUrl() {
        return configuredBaseUrl;
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    public HttpTransport getHttpTransport() {
        return httpTransport;
    }

    public PluginState getPluginState() {
        return pluginState;
    }

    public EventForwarder getEventForwarder() {
        return eventForwarder;
    }

    public CommandPoller getCommandPoller() {
        return commandPoller;
    }
}
