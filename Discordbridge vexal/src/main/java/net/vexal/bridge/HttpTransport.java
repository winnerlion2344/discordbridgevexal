package net.vexal.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Outbound HTTP only: auth headers, timeouts, retries, and JSON parse helpers.
 */
public final class HttpTransport {

    public static final int MAX_BODY_BYTES = 8 * 1024;
    private static final String USER_AGENT = "VexalBridge/1.0 (Folia)";
    private static final Gson GSON = new Gson();

    private final Logger logger;
    private volatile boolean debug;
    private final HttpClient client;

    private volatile String baseUrl;
    private volatile String token;
    private volatile int timeoutMs;

    private final AtomicBoolean logged401 = new AtomicBoolean(false);
    private final AtomicBoolean logged400 = new AtomicBoolean(false);
    private final AtomicBoolean logged503 = new AtomicBoolean(false);
    private final AtomicBoolean authBlocked = new AtomicBoolean(false);
    private volatile long authRetryAfterMs;

    public HttpTransport(Logger logger, boolean debug) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.debug = debug;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.baseUrl = "";
        this.token = "";
        this.timeoutMs = 5000;
    }

    public void configure(String baseUrl, String token, int timeoutMs, boolean debug) {
        String url = baseUrl == null ? "" : baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        this.baseUrl = url;
        this.token = token == null ? "" : token.trim();
        this.timeoutMs = Math.max(500, timeoutMs);
        this.debug = debug;
        logged401.set(false);
        logged400.set(false);
        logged503.set(false);
        authBlocked.set(false);
        authRetryAfterMs = 0L;
    }

    public void configure(String baseUrl, String token, int timeoutMs) {
        configure(baseUrl, token, timeoutMs, this.debug);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public boolean isAuthBlocked() {
        if (!authBlocked.get()) {
            return false;
        }
        if (System.currentTimeMillis() >= authRetryAfterMs) {
            authBlocked.set(false);
            return false;
        }
        return true;
    }

    public void clearAuthBlock() {
        authBlocked.set(false);
        authRetryAfterMs = 0L;
        logged401.set(false);
    }

    public HttpResult postJson(String path, JsonObject body) {
        if (isAuthBlocked()) {
            return HttpResult.authBlocked();
        }
        String json = GSON.toJson(body);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            json = truncateJsonMessage(body);
        }
        try {
            HttpRequest request = baseRequest(path)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return handleResponse(response, path);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HttpResult.networkError("interrupted");
        } catch (Throwable t) {
            if (debug) {
                logger.log(Level.WARNING, "HTTP POST " + path + " failed", t);
            }
            return HttpResult.networkError(t.getMessage());
        }
    }

    public CompletableFuture<HttpResult> postJsonAsync(String path, JsonObject body) {
        if (isAuthBlocked()) {
            return CompletableFuture.completedFuture(HttpResult.authBlocked());
        }
        String json = GSON.toJson(body);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            json = truncateJsonMessage(body);
        }
        try {
            HttpRequest request = baseRequest(path)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenApply(response -> handleResponse(response, path))
                    .exceptionally(t -> {
                        if (debug) {
                            logger.log(Level.WARNING, "HTTP POST " + path + " failed", t);
                        }
                        return HttpResult.networkError(t.getMessage());
                    });
        } catch (Throwable t) {
            return CompletableFuture.completedFuture(HttpResult.networkError(t.getMessage()));
        }
    }

    public HttpResult getJson(String pathAndQuery) {
        if (isAuthBlocked()) {
            return HttpResult.authBlocked();
        }
        try {
            HttpRequest request = baseRequest(pathAndQuery)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return handleResponse(response, pathAndQuery);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HttpResult.networkError("interrupted");
        } catch (Throwable t) {
            if (debug) {
                logger.log(Level.WARNING, "HTTP GET " + pathAndQuery + " failed", t);
            }
            return HttpResult.networkError(t.getMessage());
        }
    }

    /**
     * POST with 3 retries on 401/503/network using 2s / 4s / 8s backoff, then drop.
     */
    public HttpResult postJsonWithBackoff(String path, JsonObject body) {
        long[] delays = {2000L, 4000L, 8000L};
        HttpResult last = HttpResult.networkError("no attempt");
        for (int attempt = 0; attempt <= delays.length; attempt++) {
            last = postJson(path, body);
            if (last.statusCode == 200 || last.statusCode == 400) {
                return last;
            }
            boolean retryable = last.networkError
                    || last.statusCode == 401
                    || last.statusCode == 503
                    || last.authBlocked;
            if (!retryable || attempt == delays.length) {
                break;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(delays[attempt]);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        logger.warning("Giving up on POST " + path + " after retries (last=" + last + ")");
        return last;
    }

    public List<RemoteCommand> parseCommands(HttpResult result) {
        List<RemoteCommand> out = new ArrayList<>();
        if (result.body == null || result.body.isBlank()) {
            return out;
        }
        try {
            JsonObject root = JsonParser.parseString(result.body).getAsJsonObject();
            if (!root.has("commands") || !root.get("commands").isJsonArray()) {
                return out;
            }
            JsonArray arr = root.getAsJsonArray("commands");
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject obj = el.getAsJsonObject();
                if (!obj.has("id") || !obj.has("command")) {
                    continue;
                }
                int id = obj.get("id").getAsInt();
                String command = obj.get("command").getAsString();
                String requestedBy = obj.has("requestedBy") && !obj.get("requestedBy").isJsonNull()
                        ? obj.get("requestedBy").getAsString()
                        : "unknown";
                long createdAt = obj.has("createdAt") && !obj.get("createdAt").isJsonNull()
                        ? obj.get("createdAt").getAsLong()
                        : 0L;
                out.add(new RemoteCommand(id, command, requestedBy, createdAt));
            }
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Failed to parse commands payload", t);
        }
        return out;
    }

    private HttpRequest.Builder baseRequest(String path) {
        String url = baseUrl + (path.startsWith("/") ? path : "/" + path);
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("X-Bridge-Token", token)
                .header("User-Agent", USER_AGENT);
    }

    private HttpResult handleResponse(HttpResponse<String> response, String path) {
        int code = response.statusCode();
        String body = response.body();
        if (debug) {
            logger.info("HTTP " + path + " -> " + code);
        }
        if (code == 200) {
            clearAuthBlock();
            logged503.set(false);
            return HttpResult.ok(code, body);
        }
        if (code == 400) {
            if (logged400.compareAndSet(false, true)) {
                logger.warning("Bridge returned 400 for " + path + " (logged once). Body: " + trim(body));
            }
            return HttpResult.ok(code, body);
        }
        if (code == 401) {
            authBlocked.set(true);
            authRetryAfterMs = System.currentTimeMillis() + 60_000L;
            if (logged401.compareAndSet(false, true)) {
                logger.severe("Bridge authentication failed (401) for " + path
                        + ". Check bot.token. Will retry every 60s without a restart.");
            }
            return HttpResult.auth(code, body);
        }
        if (code == 503) {
            if (logged503.compareAndSet(false, true)) {
                logger.warning("Bridge unavailable (503) for " + path + " (logged once); will keep retrying.");
            }
            return HttpResult.unavailable(code, body);
        }
        return HttpResult.ok(code, body);
    }

    private static String truncateJsonMessage(JsonObject body) {
        if (body.has("message") && body.get("message").isJsonPrimitive()) {
            String msg = body.get("message").getAsString();
            if (msg.length() > 500) {
                body.addProperty("message", msg.substring(0, 500));
            }
        }
        if (body.has("output") && body.get("output").isJsonPrimitive()) {
            String out = body.get("output").getAsString();
            if (out.length() > 1000) {
                body.addProperty("output", out.substring(0, 1000));
            }
        }
        String json = GSON.toJson(body);
        if (json.getBytes(StandardCharsets.UTF_8).length <= MAX_BODY_BYTES) {
            return json;
        }
        body.addProperty("message", "truncated");
        body.addProperty("output", "truncated");
        return GSON.toJson(body);
    }

    private static String trim(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }

    public record RemoteCommand(int id, String command, String requestedBy, long createdAt) {
    }

    public static final class HttpResult {
        public final int statusCode;
        public final String body;
        public final boolean networkError;
        public final boolean authBlocked;
        public final String error;

        private HttpResult(int statusCode, String body, boolean networkError, boolean authBlocked, String error) {
            this.statusCode = statusCode;
            this.body = body;
            this.networkError = networkError;
            this.authBlocked = authBlocked;
            this.error = error;
        }

        public static HttpResult ok(int code, String body) {
            return new HttpResult(code, body, false, false, null);
        }

        public static HttpResult auth(int code, String body) {
            return new HttpResult(code, body, false, true, "401");
        }

        public static HttpResult unavailable(int code, String body) {
            return new HttpResult(code, body, false, false, "503");
        }

        public static HttpResult networkError(String message) {
            return new HttpResult(-1, null, true, false, message);
        }

        public static HttpResult authBlocked() {
            return new HttpResult(401, null, false, true, "auth-blocked");
        }

        public boolean success() {
            return statusCode == 200 && !networkError;
        }

        @Override
        public String toString() {
            return "HttpResult{code=" + statusCode + ", networkError=" + networkError
                    + ", authBlocked=" + authBlocked + ", error=" + error + "}";
        }
    }
}
