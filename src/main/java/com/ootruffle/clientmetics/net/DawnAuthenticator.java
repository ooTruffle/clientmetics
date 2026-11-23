package com.ootruffle.clientmetics.net;

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

/** Gets a Dawn JWT using the game's existing Microsoft session and Mojang's server-join proof. */
public final class DawnAuthenticator {
    private static final String API = "https://api.dawn.gg/v2";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private DawnAuthenticator() { }

    public static String fetchToken() throws Exception {
        String name = com.ootruffle.clientmetics.platform.Game.get().sessionName();
        String serverId = get("/minecraft/server-id").get("token").getAsString();
        SessionJoins.join(serverId);
        String path = "/minecraft/has-joined/" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                + "?token=" + URLEncoder.encode(serverId, "UTF-8").replace("+", "%20");
        String jwt = get(path).get("token").getAsString();
        if (jwt == null || jwt.isEmpty()) throw new IOException("Dawn returned no access token");
        return jwt;
    }

    private static JsonObject get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + path)).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json").header("User-Agent", "DawnClient/standalone-clientmetics")
                .header("X-Dawn-Surface", "client").header("X-Dawn-OS", osName()).header("X-Dawn-Edition", "dawn").GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("Dawn authentication returned HTTP " + response.statusCode());
        return new JsonParser().parse(response.body()).getAsJsonObject();
    }
    private static String osName() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        return os.contains("win") ? "windows" : os.contains("mac") ? "macos" : "linux";
    }
}
