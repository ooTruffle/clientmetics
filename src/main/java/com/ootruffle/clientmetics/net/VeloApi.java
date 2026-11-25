package com.ootruffle.clientmetics.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Velo Client's public API, read-only. Only unauthenticated calls are made: the online list and
 * cape files. Nothing signs in to Velo and no heartbeat is sent, so this mod never shows up as a Velo
 * user or reports anything about you to Velo.
 * <p>
 * {@code -Dclientmetics.veloApi=<url>} points it at another server.
 */
public final class VeloApi {

    public static final String BASE = System.getProperty("clientmetics.veloApi", "https://api.velo-client.com")
            .replaceAll("/+$", "");

    private VeloApi() {}

    /** {@code GET <base><path>}, at most {@code maxBytes}. Blocking. */
    public static byte[] get(String path, long maxBytes) throws IOException {
        final HttpURLConnection conn = (HttpURLConnection) new URL(BASE + path).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(20_000);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("User-Agent", LunarSocket.USER_AGENT);
        conn.setRequestProperty("Accept", "application/json, image/gif, image/png, */*");
        try {
            final int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = conn.getInputStream()) {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                final byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > maxBytes) {
                        throw new IOException("response too large");
                    }
                }
                return out.toByteArray();
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * {@code GET /v1/online}: every Velo user Velo counts as online right now (worldwide, not per
     * server), as UUID to cape ID. Users with no cape map to an empty string.
     */
    public static Map<UUID, String> online() throws IOException {
        final JsonElement root = new JsonParser().parse(new String(get("/v1/online", 32L * 1024 * 1024), StandardCharsets.UTF_8));
        final JsonArray users = root.isJsonObject() && root.getAsJsonObject().has("users")
                && root.getAsJsonObject().get("users").isJsonArray() ? root.getAsJsonObject().getAsJsonArray("users") : null;
        if (users == null) {
            throw new IOException("no users list in the reply");
        }
        final Map<UUID, String> out = new HashMap<>(users.size() * 2);
        for (JsonElement element : users) {
            if (!element.isJsonObject()) {
                continue;
            }
            final JsonObject user = element.getAsJsonObject();
            final UUID uuid = parseUuid(string(user, "uuid"));
            if (uuid != null) {
                final String cape = string(user, "capeId");
                out.put(uuid, cape == null ? "" : cape);
            }
        }
        return out;
    }

    private static String string(JsonObject object, String key) {
        final JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    /** A UUID with or without dashes, or null. */
    static UUID parseUuid(String raw) {
        if (raw == null) {
            return null;
        }
        final String hex = raw.replace("-", "").toLowerCase(Locale.ROOT);
        if (!hex.matches("[0-9a-f]{32}")) {
            return null;
        }
        return new UUID(Long.parseUnsignedLong(hex.substring(0, 16), 16), Long.parseUnsignedLong(hex.substring(16), 16));
    }

}
