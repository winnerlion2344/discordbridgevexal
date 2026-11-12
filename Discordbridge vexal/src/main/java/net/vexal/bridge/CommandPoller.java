package net.vexal.bridge;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Polls remote commands, deduplicates by persisted id, reports results.
 */
public final class CommandPoller {

    private final VexalBridgePlugin plugin;
    private final HttpTransport transport;
    private final CommandExecutor executor;
    private final PluginState state;
    private final Scheduler scheduler;
    private final Logger logger;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final AtomicReference<String> lastPollResult = new AtomicReference<>("never");

    private volatile boolean enabled;
    private volatile long intervalMs = 5000L;
    private volatile int maxPerPoll = 10;
    private volatile long currentBackoffMs = 5000L;

    public CommandPoller(
            VexalBridgePlugin plugin,
            HttpTransport transport,
            CommandExecutor executor,
            PluginState state,
            Scheduler scheduler
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.state = Objects.requireNonNull(state, "state");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = plugin.getLogger();
    }

    public void configure(boolean enabled, int pollIntervalSeconds, int maxPerPoll) {
        this.enabled = enabled;
        int seconds = Math.max(3, pollIntervalSeconds);
        this.intervalMs = seconds * 1000L;
        this.maxPerPoll = Math.max(1, Math.min(50, maxPerPoll));
        this.currentBackoffMs = this.intervalMs;
    }

    public void start() {
        if (!enabled) {
            lastPollResult.set("disabled");
            return;
        }
        running.set(true);
        currentBackoffMs = intervalMs;
        scheduler.runAsyncLater(this::tick, intervalMs);
    }

    public void stop() {
        running.set(false);
    }

    public boolean isInFlight() {
        return inFlight.get();
    }

    public String getLastPollResult() {
        return lastPollResult.get();
    }

    private void tick() {
        if (!running.get() || !enabled) {
            return;
        }
        try {
            pollOnce();
        } catch (Throwable t) {
            lastPollResult.set("error: " + t.getMessage());
            logger.warning("Poller tick failed: " + t.getMessage());
            bumpBackoff();
        } finally {
            if (running.get() && enabled) {
                scheduler.runAsyncLater(this::tick, currentBackoffMs);
            }
        }
    }

    private void pollOnce() {
        if (!inFlight.compareAndSet(false, true)) {
            lastPollResult.set("skipped (in-flight)");
            return;
        }
        try {
            plugin.getEventForwarder().flushSafe();

            if (transport.isAuthBlocked()) {
                lastPollResult.set("auth-blocked");
                bumpBackoff();
                return;
            }

            HttpTransport.HttpResult result = transport.getJson("/minecraft/commands?limit=" + maxPerPoll);
            if (!result.success()) {
                if (result.statusCode == 401 || result.authBlocked) {
                    lastPollResult.set("401");
                } else if (result.statusCode == 503) {
                    lastPollResult.set("503");
                } else if (result.networkError) {
                    lastPollResult.set("network: " + result.error);
                } else {
                    lastPollResult.set("http " + result.statusCode);
                }
                bumpBackoff();
                return;
            }

            List<HttpTransport.RemoteCommand> commands = transport.parseCommands(result);
            commands.sort(Comparator.comparingInt(HttpTransport.RemoteCommand::id));

            List<HttpTransport.RemoteCommand> todo = new ArrayList<>();
            for (HttpTransport.RemoteCommand cmd : commands) {
                if (state.shouldSkip(cmd.id())) {
                    continue;
                }
                todo.add(cmd);
            }

            for (HttpTransport.RemoteCommand cmd : todo) {
                CommandExecutor.ExecutionResult exec;
                try {
                    exec = executor.execute(cmd).get();
                } catch (Exception e) {
                    exec = new CommandExecutor.ExecutionResult(
                            cmd.id(),
                            false,
                            "",
                            e.getMessage() == null ? "execution failed" : e.getMessage()
                    );
                }

                JsonObject body = new JsonObject();
                body.addProperty("id", exec.id());
                body.addProperty("ok", exec.ok());
                String output = exec.output() == null ? "" : exec.output();
                if (output.length() > 1500) {
                    output = output.substring(0, 1500);
                }
                body.addProperty("output", output);
                if (exec.error() == null) {
                    body.add("error", JsonNull.INSTANCE);
                } else {
                    body.addProperty("error", exec.error());
                }

                transport.postJsonWithBackoff("/minecraft/commands/result", body);
                state.setLastExecutedId(cmd.id());
                state.save();
            }

            lastPollResult.set("ok (" + todo.size() + " cmds)");
            currentBackoffMs = intervalMs;
        } finally {
            inFlight.set(false);
        }
    }

    private void bumpBackoff() {
        long next = Math.min(60_000L, Math.max(intervalMs, currentBackoffMs) * 2L);
        currentBackoffMs = next;
    }
}
