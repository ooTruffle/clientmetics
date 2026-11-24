package com.ootruffle.clientmetics.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Clientmetics recolour server (see {@code server/} in the repository), separate from
 * ClientTag's:
 * <ul>
 *     <li>{@code POST /v1/login} { name, serverId } -> { token, expiresIn }, after a Mojang session join with serverId</li>
 *     <li>{@code POST /v1/sync} { colours?, lookup } -> { colours: { uuid: { key: "#rrggbb" } } };
 *     {@code colours}, when sent, replaces all of ours</li>
 *     <li>{@code POST /v1/leave} -> drops our presence right away</li>
 * </ul>
 * The server is {@value #DEFAULT_URL} unless {@code -Dclientmetics.server=<url>} says otherwise.
 * Blocking; worker threads only.
 */
public final class RecolourApi {

    public static final String DEFAULT_URL = "https://clientmetics.fluffykiwi.net";
    public static final String URL = stripSlash(System.getProperty("clientmetics.server", DEFAULT_URL));

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The server no longer accepts our token: log in again. */
    public static final class Unauthorized extends IOException {
        Unauthorized() {
            super("token rejected");
        }
    }

    /** HTTP 429. */
    public static final class RateLimited extends IOException {
        RateLimited() {
            super("rate limited");
        }
    }

    public static final class Token {
        public final String value;
        public final long expiresAt;

        Token(String value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }

    /** What a sync found out. */
    public static final class SyncResult {
        /** Players looked up -> their colours (RGB) by cosmetic key. */
        public final Map<UUID, Map<String, Integer>> colours = new HashMap<>();
        /** Players looked up -> the extras they wear -> whether it jingles. */
        public final Map<UUID, Map<String, Boolean>> wear = new HashMap<>();
        /** The extras the server lets us wear. */
        public final java.util.Set<String> granted = new java.util.HashSet<>();
    }

    private RecolourApi() {}

    /** Joins Mojang's session server (through {@link SessionJoins}) and logs in with it. */
    public static Token login(String name) throws Exception {
        final String serverId = serverId();
        SessionJoins.join(serverId);
        final JsonObject body = new JsonObject();
        body.addProperty("name", name);
        body.addProperty("serverId", serverId);
        final JsonObject response = post("/v1/login", null, body);
        final JsonElement token = response.get("token");
        if (token == null || !token.isJsonPrimitive()) {
            throw new IOException("recolour server sent no token");
        }
        final JsonElement expiresIn = response.get("expiresIn");
        final long ttl = expiresIn != null && expiresIn.isJsonPrimitive() ? expiresIn.getAsLong() * 1000 : 3_600_000L;
        return new Token(token.getAsString(), System.currentTimeMillis() + ttl);
    }

    /**
     * Refreshes our presence, replaces our colours if {@code colours} isn't null and what we wear if
     * {@code wear} (extra -> jingles) isn't, and returns the colours and extras of the Clientmetics
     * users among {@code lookup}, and the extras we're allowed.
     */
    public static SyncResult sync(Token token, Map<String, Integer> colours, Map<String, Boolean> wear,
                                  Collection<UUID> lookup) throws IOException {
        final JsonObject body = new JsonObject();
        if (colours != null) {
            final JsonObject own = new JsonObject();
            for (Map.Entry<String, Integer> e : colours.entrySet()) {
                own.addProperty(e.getKey(), hex(e.getValue()));
            }
            body.add("colours", own);
        }
        if (wear != null) {
            final JsonObject worn = new JsonObject();
            for (Map.Entry<String, Boolean> e : wear.entrySet()) {
                final JsonObject settings = new JsonObject();
                settings.addProperty("jingle", e.getValue());
                worn.add(e.getKey(), settings);
            }
            body.add("wear", worn);
        }
        final JsonArray lookupArray = new JsonArray();
        // add(JsonPrimitive), not add(String): 1.8.9 ships Gson 2.2.4.
        lookup.forEach(uuid -> lookupArray.add(new JsonPrimitive(uuid.toString())));
        body.add("lookup", lookupArray);

        final JsonObject response = post("/v1/sync", token, body);
        final SyncResult result = new SyncResult();
        final JsonElement granted = response.get("granted");
        if (granted != null && granted.isJsonArray()) {
            for (JsonElement id : granted.getAsJsonArray()) {
                if (id.isJsonPrimitive()) {
                    result.granted.add(id.getAsString());
                }
            }
        }
        final JsonElement worn = response.get("wear");
        if (worn != null && worn.isJsonObject()) {
            for (Map.Entry<String, JsonElement> player : worn.getAsJsonObject().entrySet()) {
                final UUID uuid = parseUuid(player.getKey());
                if (uuid == null || !player.getValue().isJsonObject()) {
                    continue;
                }
                final Map<String, Boolean> extras = new HashMap<>();
                for (Map.Entry<String, JsonElement> e : player.getValue().getAsJsonObject().entrySet()) {
                    final JsonElement jingle = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject().get("jingle") : null;
                    extras.put(e.getKey(), jingle == null || !jingle.isJsonPrimitive() || jingle.getAsBoolean());
                }
                if (!extras.isEmpty()) {
                    result.wear.put(uuid, extras);
                }
            }
        }
        final Map<UUID, Map<String, Integer>> out = result.colours;
        final JsonElement all = response.get("colours");
        if (all == null || !all.isJsonObject()) {
            return result;
        }
        for (Map.Entry<String, JsonElement> player : all.getAsJsonObject().entrySet()) {
            final UUID uuid = parseUuid(player.getKey());
            if (uuid == null || !player.getValue().isJsonObject()) {
                continue;
            }
            final Map<String, Integer> map = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : player.getValue().getAsJsonObject().entrySet()) {
                final Integer rgb = e.getValue().isJsonPrimitive() ? parseHex(e.getValue().getAsString()) : null;
                if (rgb != null) {
                    map.put(e.getKey(), rgb);
                }
            }
            if (!map.isEmpty()) {
                out.put(uuid, map);
            }
        }
        return result;
    }

    public static void leave(Token token) throws IOException {
        post("/v1/leave", token, new JsonObject());
    }

    public static String hex(int rgb) {
        return String.format("#%06x", rgb & 0xFFFFFF);
    }

    /** {@code #rrggbb} to RGB, or null. */
    public static Integer parseHex(String text) {
        if (text == null || text.length() != 7 || text.charAt(0) != '#') {
            return null;
        }
        try {
            return Integer.parseInt(text.substring(1), 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static UUID parseUuid(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static JsonObject post(String path, Token token, JsonObject body) throws IOException {
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(URL + path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", LunarSocket.USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (token != null) {
            request.header("Authorization", "Bearer " + token.value);
        }
        final HttpResponse<String> response;
        try {
            response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        if (response.statusCode() == 401) {
            throw new Unauthorized();
        }
        if (response.statusCode() == 429) {
            throw new RateLimited();
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException(path + " returned HTTP " + response.statusCode());
        }
        final String text = response.body();
        if (text == null || text.isEmpty()) {
            return new JsonObject();
        }
        final JsonElement json = new JsonParser().parse(text);
        return json.isJsonObject() ? json.getAsJsonObject() : new JsonObject();
    }

    private static String serverId() {
        final StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(LETTERS.charAt(RANDOM.nextInt(LETTERS.length())));
        }
        return sb.toString();
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

}
