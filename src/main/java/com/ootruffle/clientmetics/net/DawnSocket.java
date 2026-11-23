package com.ootruffle.clientmetics.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ootruffle.clientmetics.cosmetic.DawnCosmeticSnapshot;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Dawn's JSON gateway websocket, subscribed to players.tracked. */
public final class DawnSocket implements WebSocket.Listener {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final String URL = "wss://api.dawn.gg/v2/ws";
    private final StringBuilder partial = new StringBuilder();
    private final Consumer<DawnCosmeticSnapshot> updates;
    private volatile WebSocket socket;

    private DawnSocket(Consumer<DawnCosmeticSnapshot> updates) { this.updates = updates; }

    public static DawnSocket connect(String jwt, Consumer<DawnCosmeticSnapshot> updates) throws IOException {
        DawnSocket listener = new DawnSocket(updates);
        try {
            HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + jwt)
                    .header("User-Agent", "DawnClient/standalone-clientmetics")
                    .header("X-Dawn-Surface", "client")
                    .header("X-Dawn-OS", osName())
                    .header("X-Dawn-Edition", "dawn")
                    .buildAsync(URI.create(URL), listener).get();
            listener.send(frame("subscribe", "players.tracked", null));
            return listener;
        } catch (Exception e) { throw new IOException("couldn't connect to Dawn gateway", e); }
    }

    public void track(Collection<java.util.UUID> add, Collection<java.util.UUID> remove) {
        JsonObject payload = new JsonObject();
        if (!add.isEmpty()) payload.add("add", uuids(add));
        if (!remove.isEmpty()) payload.add("remove", uuids(remove));
        send(frame("track", "players.tracked", payload));
    }

    private void send(String text) { WebSocket ws = socket; if (ws != null) ws.sendText(text, true); }

    public boolean isOpen() { return socket != null && !socket.isOutputClosed(); }
    public void close() { if (socket != null) socket.sendClose(WebSocket.NORMAL_CLOSURE, ""); }

    private static JsonArray uuids(Collection<java.util.UUID> values) {
        JsonArray result = new JsonArray();
        for (java.util.UUID uuid : values) { JsonObject o = new JsonObject(); o.addProperty("mc_uuid", uuid.toString()); result.add(o); }
        return result;
    }
    private static String frame(String type, String channel, JsonObject payload) {
        JsonObject frame = new JsonObject(); frame.addProperty("type", type); frame.addProperty("channel", channel);
        frame.addProperty("id", channel); if (payload != null) frame.add("payload", payload); return frame.toString();
    }
    private static String osName() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        return os.contains("win") ? "windows" : os.contains("mac") ? "macos" : "linux";
    }

    @Override public void onOpen(WebSocket ws) { socket = ws; ws.request(1); }
    @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        partial.append(data);
        if (last) { String text = partial.toString(); partial.setLength(0); receive(text); }
        ws.request(1); return null;
    }
    private void receive(String text) {
        if ("ping".equals(text)) { socket.sendText("pong", true); return; }
        try {
            JsonObject msg = new JsonParser().parse(text).getAsJsonObject();
            if ("ping".equals(msg.has("type") ? msg.get("type").getAsString() : "")) { socket.sendText("{\"type\":\"pong\"}", true); return; }
            JsonObject payload = msg.has("payload") && msg.get("payload").isJsonObject() ? msg.getAsJsonObject("payload") : null;
            if (payload == null) return;
            String kind = payload.has("kind") ? payload.get("kind").getAsString() : "";
            if ("snapshot_batch".equals(kind) && payload.has("snapshots") && payload.get("snapshots").isJsonArray()) {
                for (com.google.gson.JsonElement e : payload.getAsJsonArray("snapshots")) if (e.isJsonObject()) emit(e.getAsJsonObject());
            } else if ("snapshot".equals(kind) || "equipped".equals(kind)) emit(payload);
        } catch (RuntimeException ignored) { }
    }
    private void emit(JsonObject payload) { DawnCosmeticSnapshot s = DawnCosmeticSnapshot.decode(payload); if (s != null) updates.accept(s); }
    @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) { socket = null; return null; }
    @Override public void onError(WebSocket ws, Throwable error) { socket = null; }
}
