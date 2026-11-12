package net.vexal.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persists the highest executed remote command id to {@code state.json}.
 */
public final class PluginState {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Logger logger;
    private final AtomicLong lastExecutedId = new AtomicLong(0L);

    public PluginState(Path dataFolder, Logger logger) {
        this.file = Objects.requireNonNull(dataFolder, "dataFolder").resolve("state.json");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void load() {
        if (!Files.isRegularFile(file)) {
            lastExecutedId.set(0L);
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            long id = root.has("lastExecutedId") ? root.get("lastExecutedId").getAsLong() : 0L;
            lastExecutedId.set(Math.max(0L, id));
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Failed to read state.json; starting from id 0", t);
            lastExecutedId.set(0L);
        }
    }

    public void save() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            JsonObject root = new JsonObject();
            root.addProperty("lastExecutedId", lastExecutedId.get());
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to write state.json", e);
        }
    }

    public long getLastExecutedId() {
        return lastExecutedId.get();
    }

    public void setLastExecutedId(long id) {
        lastExecutedId.updateAndGet(current -> Math.max(current, id));
    }

    public boolean shouldSkip(long id) {
        return id <= lastExecutedId.get();
    }
}
