package net.vexal.bridge;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Folia / Paper scheduler shim. Reflection is used only once to detect Folia
 * via {@code Class.forName("io.papermc.paper.threadedregions.RegionizedServer")}.
 * Nothing else in the plugin may call a Bukkit/Paper scheduler directly.
 */
public final class Scheduler {

    private final Plugin plugin;
    private final boolean folia;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final List<Object> handles = new ArrayList<>();

    public Scheduler(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.folia = detectFolia();
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean isFolia() {
        return folia;
    }

    public String modeName() {
        return folia ? "Folia" : "Paper";
    }

    public void runGlobal(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (cancelled.get()) {
            return;
        }
        Runnable wrapped = wrap(task);
        if (folia) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, wrapped);
            return;
        }
        track(Bukkit.getScheduler().runTask(plugin, wrapped));
    }

    public void runAsync(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (cancelled.get()) {
            return;
        }
        Runnable wrapped = wrap(task);
        if (folia) {
            track(Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> wrapped.run()));
            return;
        }
        track(Bukkit.getScheduler().runTaskAsynchronously(plugin, wrapped));
    }

    /**
     * Schedule async work after a delay. Used by the poller for interval / backoff.
     */
    public void runAsyncLater(Runnable task, long delayMs) {
        Objects.requireNonNull(task, "task");
        if (cancelled.get()) {
            return;
        }
        long delay = Math.max(0L, delayMs);
        Runnable wrapped = wrap(task);
        if (folia) {
            track(Bukkit.getAsyncScheduler().runDelayed(
                    plugin,
                    scheduledTask -> wrapped.run(),
                    delay,
                    TimeUnit.MILLISECONDS
            ));
            return;
        }
        long ticks = delay == 0L ? 0L : Math.max(1L, (delay + 49L) / 50L);
        track(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, wrapped, ticks));
    }

    public void cancelAll() {
        cancelled.set(true);
        List<Object> snapshot;
        synchronized (handles) {
            snapshot = new ArrayList<>(handles);
            handles.clear();
        }
        for (Object handle : snapshot) {
            try {
                if (handle instanceof BukkitTask bukkitTask) {
                    bukkitTask.cancel();
                } else if (handle instanceof ScheduledTask scheduledTask) {
                    scheduledTask.cancel();
                }
            } catch (Throwable ignored) {
                // best-effort cancellation
            }
        }
    }

    /** Allow a clean restart (reload) to schedule again after cancelAll. */
    public void reset() {
        cancelled.set(false);
    }

    private Runnable wrap(Runnable task) {
        return () -> {
            if (cancelled.get()) {
                return;
            }
            try {
                task.run();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "Scheduled task crashed", t);
            }
        };
    }

    private void track(Object handle) {
        if (handle == null) {
            return;
        }
        synchronized (handles) {
            handles.add(handle);
        }
    }
}
