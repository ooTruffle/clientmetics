package com.ootruffle.clientmetics.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dawn's signed-in cosmetics endpoints, as the Dawn client calls them:
 * <ul>
 *     <li>{@code GET /cosmetics/owned?page_size=100[&page_token=]}: {@code {"ownerships":[{"cosmeticId":{"value":slug}}], "page":{"nextPageToken"}}}</li>
 *     <li>{@code GET /cosmetics/equipped}: {@code {"equipped":{"slots":{"COSMETIC_TYPE_HAT":{"value":slug}}}}}</li>
 *     <li>{@code PUT /cosmetics/equipped} with {@code {"slots":{...}}}: replaces every slot; the reply is the same shape as the GET.</li>
 * </ul>
 * The access token is the one {@link DawnAuthenticator} gets. Blocking; worker threads only.
 */
public final class DawnApi {

    private static final String API = "https://api.dawn.gg/v2";
    private static final int MAX_PAGES = 50;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** HTTP 401/403: the token expired or was refused, so sign in again and retry. */
    public static final class Unauthorized extends IOException {
        Unauthorized(int code) {
            super("Dawn returned HTTP " + code);
        }
    }

    private DawnApi() {}

    /** Slugs of every cosmetic the account owns. */
    public static List<String> owned(String jwt) throws IOException {
        final List<String> slugs = new ArrayList<>();
        String token = "";
        for (int page = 0; page < MAX_PAGES; page++) {
            final String path = "/cosmetics/owned?page_size=100"
                    + (token.isEmpty() ? "" : "&page_token=" + URLEncoder.encode(token, "UTF-8").replace("+", "%20"));
            final JsonObject response = send(jwt, "GET", path, null);
            if (response.has("ownerships") && response.get("ownerships").isJsonArray()) {
                for (JsonElement e : response.getAsJsonArray("ownerships")) {
                    final String slug = e.isJsonObject() ? value(e.getAsJsonObject().get("cosmeticId")) : null;
                    if (slug != null && !slug.isEmpty() && !slugs.contains(slug)) {
                        slugs.add(slug);
                    }
                }
            }
            final JsonElement pageInfo = response.get("page");
            token = pageInfo != null && pageInfo.isJsonObject() && pageInfo.getAsJsonObject().has("nextPageToken")
                    && !pageInfo.getAsJsonObject().get("nextPageToken").isJsonNull()
                    ? pageInfo.getAsJsonObject().get("nextPageToken").getAsString() : "";
            if (token.isEmpty()) {
                break;
            }
        }
        return slugs;
    }

    /** Slot ({@code COSMETIC_TYPE_HAT}...) to slug, as saved on the account. */
    public static Map<String, String> equipped(String jwt) throws IOException {
        return slots(send(jwt, "GET", "/cosmetics/equipped", null));
    }

    /** Saves the whole outfit (slots left out are emptied); returns what Dawn saved. */
    public static Map<String, String> setEquipped(String jwt, Map<String, String> slots) throws IOException {
        final JsonObject map = new JsonObject();
        for (Map.Entry<String, String> e : slots.entrySet()) {
            final JsonObject v = new JsonObject();
            v.addProperty("value", e.getValue());
            map.add(e.getKey(), v);
        }
        final JsonObject body = new JsonObject();
        body.add("slots", map);
        final Map<String, String> saved = slots(send(jwt, "PUT", "/cosmetics/equipped", body.toString()));
        return saved.isEmpty() && !slots.isEmpty() ? new LinkedHashMap<>(slots) : saved;
    }

    private static Map<String, String> slots(JsonObject response) {
        final Map<String, String> out = new LinkedHashMap<>();
        final JsonElement equipped = response.get("equipped");
        final JsonElement slots = equipped != null && equipped.isJsonObject() ? equipped.getAsJsonObject().get("slots") : null;
        if (slots != null && slots.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : slots.getAsJsonObject().entrySet()) {
                final String slug = value(e.getValue());
                if (slug != null && !slug.isEmpty()) {
                    out.put(e.getKey(), slug);
                }
            }
        }
        return out;
    }

    /** {@code {"value": "..."}}, Dawn's wrapper for IDs. */
    private static String value(JsonElement wrapper) {
        if (wrapper == null || !wrapper.isJsonObject()) {
            return null;
        }
        final JsonElement v = wrapper.getAsJsonObject().get("value");
        return v != null && v.isJsonPrimitive() ? v.getAsString() : null;
    }

    private static JsonObject send(String jwt, String method, String path, String body) throws IOException {
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(API + path)).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + jwt)
                .header("User-Agent", "DawnClient/standalone-clientmetics")
                .header("X-Dawn-Surface", "client")
                .header("X-Dawn-OS", osName())
                .header("X-Dawn-Edition", "dawn");
        if (body != null) {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        final HttpResponse<String> response;
        try {
            response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        final int code = response.statusCode();
        if (code == 401 || code == 403) {
            throw new Unauthorized(code);
        }
        if (code != 200) {
            throw new IOException("Dawn returned HTTP " + code + " for " + method + " " + path.replaceAll("\\?.*", ""));
        }
        final String text = response.body();
        if (text == null || text.trim().isEmpty()) {
            return new JsonObject();
        }
        final JsonElement json = new JsonParser().parse(text);
        return json.isJsonObject() ? json.getAsJsonObject() : new JsonObject();
    }

    private static String osName() {
        final String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        return os.contains("win") ? "windows" : os.contains("mac") ? "macos" : "linux";
    }

}
