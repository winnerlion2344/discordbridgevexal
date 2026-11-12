package net.vexal.bridge;

import org.bukkit.Bukkit;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Runs remote commands on the global region with a capturing console sender.
 */
public final class CommandExecutor {

    private final VexalBridgePlugin plugin;
    private final Scheduler scheduler;
    private final Logger logger;

    public CommandExecutor(VexalBridgePlugin plugin, Scheduler scheduler) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = plugin.getLogger();
    }

    public CompletableFuture<ExecutionResult> execute(HttpTransport.RemoteCommand remote) {
        Objects.requireNonNull(remote, "remote");
        CompletableFuture<ExecutionResult> future = new CompletableFuture<>();
        scheduler.runGlobal(() -> {
            String raw = remote.command() == null ? "" : remote.command().trim();
            if (raw.startsWith("/")) {
                raw = raw.substring(1);
            }
            final String command = raw;
            plugin.markBridgeCommandActive(true);
            try {
                CapturingSender sender = new CapturingSender(Bukkit.getConsoleSender());
                boolean ok;
                String error = null;
                try {
                    ok = Bukkit.dispatchCommand(sender, command);
                    if (!ok) {
                        error = "Command returned false (unknown or rejected)";
                    }
                } catch (Throwable t) {
                    ok = false;
                    error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                    logger.warning("Command id=" + remote.id() + " threw: " + error);
                }
                String output = sender.capturedOutput();
                if (output.length() > 1500) {
                    output = output.substring(0, 1500);
                }
                logger.info("Executed remote command id=" + remote.id()
                        + " by=" + remote.requestedBy()
                        + " cmd='" + command + "' ok=" + ok);
                future.complete(new ExecutionResult(remote.id(), ok, output, error));
            } catch (Throwable t) {
                logger.warning("Command dispatch failed id=" + remote.id() + ": " + t.getMessage());
                future.complete(new ExecutionResult(remote.id(), false, "", t.getMessage()));
            } finally {
                plugin.markBridgeCommandActive(false);
            }
        });
        return future;
    }

    public record ExecutionResult(int id, boolean ok, String output, String error) {
    }
}
