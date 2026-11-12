package net.vexal.bridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Event hooks with individually guarded registration so a missing event class
 * never prevents the rest of the plugin from loading.
 */
public final class Listeners implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final String SILENT_PERM = "vexalbridge.silent";

    private final VexalBridgePlugin plugin;
    private volatile EventForwarder forwarder;

    private volatile boolean chatEnabled = true;
    private volatile boolean joinEnabled = true;
    private volatile boolean leaveEnabled = true;
    private volatile boolean deathEnabled = true;
    private volatile boolean consoleEnabled = false;

    private ConsoleBridgeAppender consoleAppender;

    public Listeners(VexalBridgePlugin plugin, EventForwarder forwarder) {
        this.plugin = plugin;
        this.forwarder = forwarder;
    }

    public void setForwarder(EventForwarder forwarder) {
        this.forwarder = forwarder;
    }

    public void configure(boolean chat, boolean join, boolean leave, boolean death, boolean console) {
        this.chatEnabled = chat;
        this.joinEnabled = join;
        this.leaveEnabled = leave;
        this.deathEnabled = death;
        setConsoleForwarding(console);
    }

    public void registerAll() {
        PluginManager pm = plugin.getServer().getPluginManager();
        register(pm, "io.papermc.paper.event.player.AsyncChatEvent", this::handleAsyncChat);
        register(pm, PlayerJoinEvent.class.getName(), (listener, event) -> {
            if (event instanceof PlayerJoinEvent joinEvent) {
                onJoin(joinEvent);
            }
        });
        register(pm, PlayerQuitEvent.class.getName(), (listener, event) -> {
            if (event instanceof PlayerQuitEvent quitEvent) {
                onQuit(quitEvent);
            }
        });
        register(pm, PlayerDeathEvent.class.getName(), (listener, event) -> {
            if (event instanceof PlayerDeathEvent deathEvent) {
                onDeath(deathEvent);
            }
        });
    }

    private void register(PluginManager pm, String className, EventExecutor executor) {
        try {
            Class<?> raw = Class.forName(className);
            if (!Event.class.isAssignableFrom(raw)) {
                plugin.getLogger().warning("Not an Event class, skipping: " + className);
                return;
            }
            @SuppressWarnings("unchecked")
            Class<? extends Event> type = (Class<? extends Event>) raw;
            pm.registerEvent(type, this, EventPriority.MONITOR, executor, plugin, true);
            if (plugin.isDebug()) {
                plugin.getLogger().info("Registered listener for " + className);
            }
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            plugin.getLogger().warning("Event class not present, skipping: " + className);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Failed to register listener for " + className, t);
        }
    }

    private void handleAsyncChat(Listener listener, Event event) {
        if (!chatEnabled) {
            return;
        }
        if (plugin.isBridgeCommandActive()) {
            return;
        }
        try {
            Method playerMethod = event.getClass().getMethod("getPlayer");
            Object playerObj = playerMethod.invoke(event);
            if (!(playerObj instanceof Player player)) {
                return;
            }
            if (player.hasPermission(SILENT_PERM)) {
                return;
            }
            Method messageMethod = event.getClass().getMethod("message");
            Object componentObj = messageMethod.invoke(event);
            String plain;
            if (componentObj instanceof Component component) {
                plain = PLAIN.serialize(component);
            } else {
                plain = String.valueOf(componentObj);
            }
            plain = sanitize(plain, 500);
            forwarder.enqueue("chat", player.getName(), player.getName() + ": " + plain);
        } catch (Throwable t) {
            if (plugin.isDebug()) {
                plugin.getLogger().log(Level.FINE, "AsyncChat handling failed", t);
            }
        }
    }

    private void onJoin(PlayerJoinEvent event) {
        if (!joinEnabled) {
            return;
        }
        Player player = event.getPlayer();
        if (player.hasPermission(SILENT_PERM)) {
            return;
        }
        forwarder.enqueue("join", player.getName(), player.getName() + " joined the game");
    }

    private void onQuit(PlayerQuitEvent event) {
        if (!leaveEnabled) {
            return;
        }
        Player player = event.getPlayer();
        if (player.hasPermission(SILENT_PERM)) {
            return;
        }
        forwarder.enqueue("leave", player.getName(), player.getName() + " left the game");
    }

    private void onDeath(PlayerDeathEvent event) {
        if (!deathEnabled) {
            return;
        }
        Player player = event.getEntity();
        if (player.hasPermission(SILENT_PERM)) {
            return;
        }
        String message = null;
        try {
            Component death = event.deathMessage();
            if (death != null) {
                message = PLAIN.serialize(death);
            }
        } catch (Throwable ignored) {
            // older fallback below
        }
        if (message == null || message.isBlank()) {
            message = player.getName() + " died";
        }
        message = sanitize(message, 500);
        forwarder.enqueue("death", player.getName(), message);
    }

    private void setConsoleForwarding(boolean enabled) {
        this.consoleEnabled = enabled;
        if (enabled) {
            attachConsoleAppender();
        } else {
            removeConsoleAppender();
        }
    }

    private void attachConsoleAppender() {
        if (consoleAppender != null) {
            return;
        }
        try {
            Logger root = (Logger) LogManager.getRootLogger();
            ConsoleBridgeAppender appender = new ConsoleBridgeAppender(this);
            appender.start();
            root.addAppender(appender);
            consoleAppender = appender;
            plugin.getLogger().info("Console forwarding enabled");
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Could not attach console appender; console events disabled", t);
            this.consoleEnabled = false;
        }
    }

    public void removeConsoleAppender() {
        ConsoleBridgeAppender appender = consoleAppender;
        consoleAppender = null;
        if (appender == null) {
            return;
        }
        try {
            appender.stop();
            Logger root = (Logger) LogManager.getRootLogger();
            root.removeAppender(appender);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Failed to remove console appender", t);
        }
    }

    void onConsoleLine(String line) {
        if (!consoleEnabled || line == null) {
            return;
        }
        if (line.contains("VexalBridge")) {
            return;
        }
        String sanitized = sanitize(line, 500);
        if (sanitized.isEmpty()) {
            return;
        }
        forwarder.enqueue("console", null, sanitized);
    }

    private static String sanitize(String input, int max) {
        if (input == null) {
            return "";
        }
        String flat = input.replace('\n', ' ').replace('\r', ' ').trim();
        if (flat.length() > max) {
            return flat.substring(0, max);
        }
        return flat;
    }

    /**
     * Log4j2 appender — log4j-core is provided by the Paper/Folia server at runtime.
     * Loaded reflectively-safe: if log4j-core is absent, attachConsoleAppender catches and disables.
     */
    private static final class ConsoleBridgeAppender extends AbstractAppender {

        private final Listeners owner;
        private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
        private final AtomicInteger windowCount = new AtomicInteger(0);

        private ConsoleBridgeAppender(Listeners owner) {
            super("VexalBridgeConsole", null, null, true, Property.EMPTY_ARRAY);
            this.owner = owner;
        }

        @Override
        public void append(LogEvent event) {
            if (event == null) {
                return;
            }
            String message = event.getMessage() == null ? null : event.getMessage().getFormattedMessage();
            if (message == null || message.isBlank()) {
                return;
            }
            long now = System.currentTimeMillis();
            long start = windowStart.get();
            if (now - start >= 1000L) {
                windowStart.set(now);
                windowCount.set(0);
            }
            if (windowCount.incrementAndGet() > 5) {
                return;
            }
            owner.onConsoleLine(message);
        }
    }
}
