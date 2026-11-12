package net.vexal.bridge;

import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Bounded in-memory event queue with flush on heartbeat / disable.
 */
public final class EventForwarder {

    private static final int MAX_QUEUE = 200;
    private static final int MAX_MESSAGE = 2000;

    private final VexalBridgePlugin plugin;
    private final HttpTransport transport;
    private final Scheduler scheduler;
    private final Logger logger;

    private final Deque<QueuedEvent> queue = new ArrayDeque<>();
    private final AtomicBoolean flushing = new AtomicBoolean(false);
    private volatile boolean enabled;
    private volatile String serverName = "My Server";

    public EventForwarder(VexalBridgePlugin plugin, HttpTransport transport, Scheduler scheduler) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = plugin.getLogger();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName == null || serverName.isBlank() ? "My Server" : serverName;
    }

    public int queueSize() {
        synchronized (queue) {
            return queue.size();
        }
    }

    public void enqueue(String type, String player, String message) {
        if (!enabled) {
            return;
        }
        if (type == null || type.isBlank()) {
            return;
        }
        String msg = message == null ? "" : message;
        if (msg.length() > MAX_MESSAGE) {
            msg = msg.substring(0, MAX_MESSAGE);
        }
        QueuedEvent event = new QueuedEvent(type, player, msg, serverName);
        synchronized (queue) {
            if (queue.size() >= MAX_QUEUE) {
                queue.removeFirst();
            }
            queue.addLast(event);
        }
        scheduler.runAsync(this::flushSafe);
    }

    public void flushSafe() {
        if (!enabled) {
            return;
        }
        if (!flushing.compareAndSet(false, true)) {
            return;
        }
        try {
            flushLoop(false);
        } finally {
            flushing.set(false);
        }
    }

    /**
     * Best-effort flush with a hard time cap (used from onDisable).
     */
    public void flushBlocking(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        if (!flushing.compareAndSet(false, true)) {
            long wait = deadline - System.currentTimeMillis();
            if (wait > 0) {
                try {
                    TimeUnit.MILLISECONDS.sleep(Math.min(wait, 200L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return;
        }
        try {
            while (System.currentTimeMillis() < deadline) {
                int before;
                synchronized (queue) {
                    before = queue.size();
                }
                if (before == 0) {
                    return;
                }
                boolean progress = flushLoop(true);
                if (!progress) {
                    return;
                }
            }
        } finally {
            flushing.set(false);
        }
    }

    private boolean flushLoop(boolean blocking) {
        while (true) {
            QueuedEvent next;
            synchronized (queue) {
                next = queue.peekFirst();
            }
            if (next == null) {
                return true;
            }
            if (transport.isAuthBlocked()) {
                return false;
            }
            JsonObject body = new JsonObject();
            body.addProperty("type", next.type);
            if (next.player == null) {
                body.add("player", null);
            } else {
                body.addProperty("player", next.player);
            }
            body.addProperty("message", next.message);
            body.addProperty("server", next.server);

            HttpTransport.HttpResult result;
            if (blocking) {
                result = transport.postJson("/minecraft/events", body);
            } else {
                CompletableFuture<HttpTransport.HttpResult> future =
                        transport.postJsonAsync("/minecraft/events", body);
                try {
                    result = future.get(Math.max(1000, plugin.httpTimeoutMs()), TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    result = HttpTransport.HttpResult.networkError(e.getMessage());
                }
            }

            if (result.success()) {
                synchronized (queue) {
                    queue.pollFirst();
                }
                continue;
            }
            if (result.statusCode == 400) {
                synchronized (queue) {
                    queue.pollFirst();
                }
                continue;
            }
            if (result.statusCode == 401 || result.authBlocked) {
                return false;
            }
            if (result.statusCode == 503 || result.networkError) {
                return false;
            }
            // unexpected non-200: drop to avoid poison pill
            synchronized (queue) {
                queue.pollFirst();
            }
            logger.warning("Dropping event after unexpected HTTP status " + result.statusCode);
        }
    }

    public List<String> debugSnapshot() {
        synchronized (queue) {
            List<String> out = new ArrayList<>(queue.size());
            for (QueuedEvent e : queue) {
                out.add(e.type + ":" + e.message);
            }
            return out;
        }
    }

    private record QueuedEvent(String type, String player, String message, String server) {
    }
}
