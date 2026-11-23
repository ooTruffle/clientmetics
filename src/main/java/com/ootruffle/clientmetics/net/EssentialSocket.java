package com.ootruffle.clientmetics.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Essential's connection manager socket ({@code wss://connect.essential.gg/v1}) — the socket
 * Essential's client keeps open, and the only place another player's outfit can be read from.
 *
 * <p><b>This speaks Essential's private protocol; Essential neither documents nor permits it.</b>
 * The wire format and the headers below follow ClientTag's working implementation of the same
 * endpoint, which is written against Essential 1.5.0.1 (protocol 11).
 *
 * <p>Sign-in is the Mojang "server join" proof, the same one used for Lunar and Dawn: a random
 * secret, {@code SHA1(secret + salt)} submitted as the server id, then the secret handed over in the
 * websocket's Basic auth header so Essential can call Mojang's {@code hasJoined}. The socket has to
 * be opened within a few seconds of the join. No password or Essential account is involved.
 *
 * <p>Every message is a binary frame of big-endian {@code int32} fields:
 * {@code typeId, len + UTF-8 packet id, len + UTF-8 JSON body}. Type ids are per connection and per
 * direction; id 0 is always the register packet ({@code {"a": name, "b": id}}), and each side
 * registers a name before it first sends one. The server's keep-alive is echoed back with the same
 * packet id.
 *
 * <p>Blocking; call {@link #connect} on a worker thread.
 */
public final class EssentialSocket implements WebSocket.Listener {

    private static final String SOCKET_URL = "wss://connect.essential.gg/v1";
    /** The client version Essential is told about; the server refuses clients it considers outdated. */
    private static final String MOD_VERSION = "1.5.0.1";
    private static final String MAX_PROTOCOL_VERSION = "11";

    /** Mixed into the server id hash after the secret. */
    private static final byte[] SERVER_ID_SALT = hex("173be201d4e5591dcef37bcaf701d136");

    private static final String REGISTER = "connection.ConnectionRegisterPacketTypeIdPacket";
    private static final String KEEP_ALIVE = "connection.ConnectionKeepAlivePacket";
    private static final String SUBSCRIPTION_UPDATE = "subscription.SubscriptionUpdatePacket";
    private static final String REQUEST_OUTFIT = "cosmetic.outfit.ClientCosmeticOutfitSelectedRequestPacket";
    private static final String UPDATE_EQUIPPED = "cosmetic.outfit.ClientCosmeticOutfitEquippedCosmeticsUpdatePacket";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** WebSocket close code for a suspended account. */
    public static final int CLOSE_SUSPENDED = 4008;

    /** Receives every packet the server sends, by class name, with the decoded JSON body. */
    public interface Listener {
        void onPacket(String className, JsonObject body);
    }

    /** The upgrade was refused with HTTP 404/410: Essential considers this client version outdated. */
    public static final class OutdatedException extends IOException {
        OutdatedException(int status) {
            super("Essential rejected this client version (HTTP " + status + ")");
        }
    }

    private final Listener listener;
    private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
    private final CompletableFuture<String> closed = new CompletableFuture<>();
    private final Map<String, Integer> outIds = new HashMap<>();
    private final Map<Integer, String> inIds = new HashMap<>();
    private CompletableFuture<WebSocket> sendChain;
    private volatile WebSocket ws;
    private volatile int closeCode;

    private EssentialSocket(Listener listener) {
        this.listener = listener;
        outIds.put(REGISTER, 0);
        inIds.put(0, REGISTER);
    }

    /** Joins with the game's own session and opens the socket. Blocking; worker threads only. */
    public static EssentialSocket connect(UUID uuid, String name, Listener listener) throws IOException {
        final String authorization;
        try {
            authorization = authorize(name);
        } catch (Exception e) {
            throw new IOException("couldn't prove this session to Essential: " + e, e);
        }
        final EssentialSocket socket = new EssentialSocket(listener);
        final WebSocket.Builder builder = HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10));
        builder.header("Authorization", authorization);
        builder.header("Essential-Max-Protocol-Version", MAX_PROTOCOL_VERSION);
        builder.header("Essential-User-UUID", uuid.toString());
        builder.header("Essential-User-Name", name);
        builder.header("Essential-Mod-Version", MOD_VERSION);
        builder.header("Essential-Minecraft-Version", "1.8.9");
        builder.header("Essential-Minecraft-Modloader", "FORGE");
        try {
            builder.buildAsync(URI.create(SOCKET_URL), socket).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            final Throwable cause = e.getCause();
            if (cause instanceof WebSocketHandshakeException) {
                final int status = ((WebSocketHandshakeException) cause).getResponse().statusCode();
                if (status == 404 || status == 410) {
                    throw new OutdatedException(status);
                }
                throw new IOException("couldn't open " + SOCKET_URL + ": HTTP " + status, e);
            }
            throw new IOException("couldn't open " + SOCKET_URL + ": " + e, e);
        }
        return socket;
    }

    /**
     * The {@code Authorization} header: {@code Basic base64(name ":" raw secret)}, after joining the
     * matching server id. The secret is raw bytes, not hex.
     */
    private static String authorize(String name) throws Exception {
        final byte[] secret = new byte[16];
        RANDOM.nextBytes(secret);
        SessionJoins.join(serverId(secret));
        final byte[] user = (name + ":").getBytes(StandardCharsets.UTF_8);
        final byte[] credentials = new byte[user.length + secret.length];
        System.arraycopy(user, 0, credentials, 0, user.length);
        System.arraycopy(secret, 0, credentials, user.length, secret.length);
        return "Basic " + Base64.getEncoder().encodeToString(credentials);
    }

    /** Minecraft's signed SHA-1 hex digest of secret + salt. */
    private static String serverId(byte[] secret) throws Exception {
        final MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(secret);
        sha1.update(SERVER_ID_SALT);
        return new BigInteger(sha1.digest()).toString(16);
    }

    public boolean isOpen() {
        return !closed.isDone();
    }

    /** The close code the server sent, or 0 if it didn't send one (yet). */
    public int closeCode() {
        return closeCode;
    }

    public void close() {
        if (ws != null && isOpen()) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "").whenComplete((w, e) -> ws.abort());
        }
        closed.complete("closed locally");
    }

    /** Subscribes to (or, with {@code subscribe} false, unsubscribes from) these players' changes. */
    public void updateSubscription(Collection<UUID> players, boolean subscribe) {
        final JsonArray list = new JsonArray();
        for (UUID uuid : players) {
            list.add(new JsonPrimitive(uuid.toString()));
        }
        final JsonObject body = new JsonObject();
        body.add("a", list);
        body.addProperty("b", false);
        body.addProperty("c", subscribe);
        send(SUBSCRIPTION_UPDATE, UUID.randomUUID().toString(), body);
    }

    /** Asks what a player is wearing; the answer arrives through the {@link Listener}. */
    public void requestOutfit(UUID player) {
        final JsonObject body = new JsonObject();
        body.addProperty("a", player.toString());
        send(REQUEST_OUTFIT, UUID.randomUUID().toString(), body);
    }

    /**
     * Puts {@code cosmeticId} in one slot of an outfit, or empties it when the id is null.
     * This edits the outfit saved on the account, the same way Essential's own wardrobe does.
     */
    public void equipCosmetic(String outfitId, String slot, String cosmeticId) {
        if (outfitId == null || slot == null) {
            return;
        }
        final JsonObject body = new JsonObject();
        body.addProperty("a", outfitId);
        body.addProperty("b", slot);
        if (cosmeticId == null) {
            body.add("c", com.google.gson.JsonNull.INSTANCE);
        } else {
            body.addProperty("c", cosmeticId);
        }
        send(UPDATE_EQUIPPED, UUID.randomUUID().toString(), body);
    }

    // ---- sending ----

    /** Sends one packet, registering its type first if this connection hasn't sent it before. */
    private synchronized void send(String name, String packetId, JsonObject body) {
        Integer id = outIds.get(name);
        if (id == null) {
            id = outIds.size();
            outIds.put(name, id);
            final JsonObject register = new JsonObject();
            register.addProperty("a", name);
            register.addProperty("b", id);
            sendFrame(0, "", register);
        }
        sendFrame(id, packetId, body);
    }

    private void sendFrame(int typeId, String packetId, JsonObject body) {
        final CompletableFuture<WebSocket> chain = sendChain;
        if (chain == null) {
            return; // Not open yet; onOpen sets the chain up.
        }
        final ByteBuffer frame = EssentialProtocol.encode(typeId, packetId,
                body.toString().getBytes(StandardCharsets.UTF_8));
        sendChain = chain.thenCompose(w -> w.sendBinary(frame, true)).whenComplete(this::checkSent);
    }

    /** A failed send fails every send after it too, so drop the connection and let the manager reconnect. */
    private void checkSent(WebSocket w, Throwable error) {
        if (error != null && closed.complete("send failed: " + error) && ws != null) {
            ws.abort();
        }
    }

    // ---- receiving ----

    private void onMessage(byte[] message) {
        final EssentialProtocol.Frame frame = EssentialProtocol.decode(message);
        if (frame == null) {
            return;
        }
        final String name = inIds.get(frame.typeId);
        if (name == null) {
            return;
        }
        if (REGISTER.equals(name)) {
            final String registered = string(frame.body, "a");
            final JsonElement registeredId = frame.body.get("b");
            if (registered != null && registeredId != null && registeredId.isJsonPrimitive()) {
                inIds.put(registeredId.getAsInt(), registered);
            }
            return;
        }
        if (KEEP_ALIVE.equals(name)) {
            send(KEEP_ALIVE, frame.packetId, new JsonObject());
            return;
        }
        try {
            listener.onPacket(name, frame.body);
        } catch (RuntimeException ignored) {
            // A packet we can't read shouldn't take the connection down.
        }
    }

    /** UUIDs arrive with or without dashes ({@code uuid} on responses, {@code a} on pushes). */
    public static UUID parseUuid(String s) {
        if (s == null) {
            return null;
        }
        try {
            return s.length() == 32
                    ? UUID.fromString(s.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"))
                    : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String string(JsonObject obj, String key) {
        final JsonElement element = obj.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static byte[] hex(String s) {
        final byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    // ---- websocket callbacks ----

    @Override
    public void onOpen(WebSocket webSocket) {
        // Set up here rather than after buildAsync returns: frames can arrive before that.
        synchronized (this) {
            ws = webSocket;
            sendChain = CompletableFuture.completedFuture(webSocket);
        }
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        final byte[] chunk = new byte[data.remaining()];
        data.get(chunk);
        partial.write(chunk, 0, chunk.length);
        if (last) {
            final byte[] message = partial.toByteArray();
            partial.reset();
            try {
                onMessage(message);
            } catch (RuntimeException ignored) {
                // A malformed frame shouldn't take the socket down.
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        closeCode = statusCode;
        closed.complete(statusCode + " " + reason);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        closed.complete(String.valueOf(error));
    }

}
