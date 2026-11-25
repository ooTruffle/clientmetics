package com.ootruffle.clientmetics.net;

import com.ootruffle.clientmetics.cosmetic.EquippedCosmetic;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Lunar's game websocket and its protobuf RPC envelope:
 * <ul>
 *     <li>First frame: {@code Handshake} carrying the authenticator JWT</li>
 *     <li>Request: {@code { 1: request_id, 2: service, 3: method, 4: input }}</li>
 *     <li>Response: {@code { 1: { 1: request_id, 2: output } }}</li>
 *     <li>Push: {@code { 2: google.protobuf.Any }}</li>
 * </ul>
 * The handshake says honestly what this is: an Ornithe mod on vanilla 1.8.9. It sends no Lunar
 * launcher or client version, no hardware ID and no canary token. If Lunar refuses that, the mod
 * shows nothing.
 */
public final class LunarSocket {

    public static final String MOD_VERSION = "1.1.0";
    public static final String USER_AGENT = "Clientmetics/" + MOD_VERSION + " (Minecraft 1.8.9; Ornithe)";

    private static final String SOCKET_URL = "wss://websocket.lunarclientprod.com/game";
    private static final String SUBSCRIPTION_SERVICE = "lunarclient.websocket.subscription.v1.SubscriptionService";
    private static final String COSMETIC_SERVICE = "lunarclient.websocket.cosmetic.v2.CosmeticService";
    private static final String PUSH_TYPE = "lunarclient.websocket.cosmetic.v2.PlayerCosmeticsPushV2";
    private static final long CALL_TIMEOUT_MS = 10_000;

    /** One player's equipped outfit, from {@code PlayerCosmeticsPushV2}. */
    public static final class Push {
        public final UUID player;
        public final List<EquippedCosmetic> cosmetics;

        Push(UUID player, List<EquippedCosmetic> cosmetics) {
            this.player = player;
            this.cosmetics = cosmetics;
        }
    }

    private final WsConnection ws;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<String, CompletableFuture<ProtoMessage>> pending = new ConcurrentHashMap<>();
    private volatile Consumer<Push> pushHandler;

    private LunarSocket(WsConnection ws) {
        this.ws = ws;
        ws.setHandler(this::onMessage);
        ws.onClosed(reason -> {
            final IOException e = new IOException("socket closed: " + reason);
            pending.values().forEach(f -> f.completeExceptionally(e));
            pending.clear();
        });
    }

    public static LunarSocket connect(UUID uuid, String name, String jwt, String installationId) throws IOException {
        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", USER_AGENT);
        headers.put("Accept", "application/x-protobuf");
        final WsConnection ws = WsConnection.open(SOCKET_URL, headers);
        ws.send(handshake(uuid, name, jwt, installationId).toByteArray());
        return new LunarSocket(ws);
    }

    public boolean isOpen() {
        return ws.isOpen();
    }

    public void close() {
        ws.close();
    }

    /** Called for every outfit pushed after subscribing (someone changed theirs). */
    public void onPush(Consumer<Push> handler) {
        this.pushHandler = handler;
    }

    /** {@code SubscribeV2 { 1: repeated Uuid target_uuids } -> { 1: repeated PlayerCosmeticsPushV2 cosmetic_pushes }}. */
    public CompletableFuture<List<Push>> subscribe(Collection<UUID> uuids) {
        return call(SUBSCRIPTION_SERVICE, "SubscribeV2", uuidList(uuids)).thenApply(res -> {
            final List<Push> pushes = new ArrayList<>();
            for (ProtoMessage push : res.messages(1)) {
                final Push decoded = decodePush(push);
                if (decoded != null) {
                    pushes.add(decoded);
                }
            }
            return pushes;
        });
    }

    /**
     * {@code CosmeticService.Login {} -> LoginResponse}: 6 owned_cosmetics (OwnedCosmetic { 1 cosmetic_id,
     * 3 expires_at }), 7 has_all_cosmetics_flag, 10 outfits (Outfit), 11 outfit_tree (OutfitTree { 2 default_outfit_id }).
     */
    public CompletableFuture<ProtoMessage> login() {
        return call(COSMETIC_SERVICE, "Login", null);
    }

    /**
     * {@code CosmeticService.UpdateOutfit { 1: Outfit } -> { 1: status (1 = OK), 2: Outfit }}. The
     * outfit is sent as already-encoded bytes so fields this mod doesn't use are kept.
     */
    public CompletableFuture<ProtoMessage> updateOutfit(byte[] outfit) {
        return call(COSMETIC_SERVICE, "UpdateOutfit", new ProtoWriter().bytes(1, outfit));
    }

    /** {@code Unsubscribe { 1: repeated Uuid target_uuids }}. */
    public CompletableFuture<ProtoMessage> unsubscribe(Collection<UUID> uuids) {
        return call(SUBSCRIPTION_SERVICE, "Unsubscribe", uuidList(uuids));
    }

    private static ProtoWriter uuidList(Collection<UUID> uuids) {
        final ProtoWriter body = new ProtoWriter();
        for (UUID uuid : uuids) {
            body.message(1, ProtoWriter.uuid(uuid));
        }
        return body;
    }

    private CompletableFuture<ProtoMessage> call(String service, String method, ProtoWriter body) {
        final String id = String.valueOf(nextId.getAndIncrement());
        final ProtoWriter frame = new ProtoWriter().string(1, id).string(2, service).string(3, method);
        if (body != null) {
            frame.message(4, body);
        }
        final CompletableFuture<ProtoMessage> future = new CompletableFuture<>();
        pending.put(id, future);
        ws.send(frame.toByteArray());
        return future.orTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .whenComplete((r, e) -> pending.remove(id));
    }

    private void onMessage(byte[] buf) {
        final ProtoMessage msg;
        try {
            msg = ProtoMessage.parse(buf);
        } catch (IllegalArgumentException e) {
            return;
        }
        final ProtoMessage any = msg.message(2);
        if (any != null) {
            onAny(any);
            return;
        }
        final ProtoMessage response = msg.message(1);
        if (response == null) {
            return;
        }
        final CompletableFuture<ProtoMessage> future = pending.get(response.string(1));
        if (future == null) {
            return;
        }
        if (response.has(2)) {
            try {
                future.complete(ProtoMessage.parse(response.bytes(2)));
            } catch (IllegalArgumentException e) {
                future.completeExceptionally(e);
            }
        } else if (response.fieldNumbers().size() > 1) {
            future.completeExceptionally(new IOException("call failed, response " + response.describe()));
        } else {
            future.complete(ProtoMessage.EMPTY);
        }
    }

    /** {@code google.protobuf.Any { 1: type_url, 2: value }}. */
    private void onAny(ProtoMessage any) {
        final String type = any.string(1);
        final byte[] value = any.bytes(2);
        final Consumer<Push> handler = pushHandler;
        if (type == null || value == null || handler == null || !type.endsWith(PUSH_TYPE)) {
            return;
        }
        try {
            final Push push = decodePush(ProtoMessage.parse(value));
            if (push != null) {
                handler.accept(push);
            }
        } catch (IllegalArgumentException ignored) {
            // Malformed push: skip it.
        }
    }

    /**
     * {@code PlayerCosmeticsPushV2 { 1: Uuid player_uuid, 2: Color logo_color, 3: bool logo_always_show,
     * 4: Color plus_color, 5: repeated ConditionalCosmetics, 6: repeated EquippedCosmetic default_cosmetics,
     * 7: int32 badge_id, 8: Uuid outfit_uuid }}. Only the default outfit is used for now.
     */
    private static Push decodePush(ProtoMessage push) {
        final ProtoMessage player = push.message(1);
        if (player == null) {
            return null;
        }
        final List<EquippedCosmetic> cosmetics = new ArrayList<>();
        for (ProtoMessage equipped : push.messages(6)) {
            cosmetics.add(EquippedCosmetic.decode(equipped));
        }
        return new Push(player.toUuid(), cosmetics);
    }

    /**
     * {@code Handshake}: 1 identity, 3 installation_id, 5 operating_system, 6 cpu_architecture,
     * 7 language, 8 game_handshake, 11 operating_system_release. Launcher version (2), hwid (4)
     * and canary_token (10) are left out on purpose.
     */
    private static ProtoWriter handshake(UUID uuid, String name, String jwt, String installationId) {
        final String locale = Locale.getDefault().toString();
        return new ProtoWriter()
                // MinecraftIdentity { 1 player, 2 type (2 = Microsoft), 3 authenticator_jwt }
                .message(1, new ProtoWriter()
                        .message(1, ProtoWriter.uuidAndUsername(uuid, name))
                        .varint(2, 2)
                        .string(3, jwt))
                .string(3, installationId)
                .string(5, System.getProperty("os.name", "unknown"))
                .string(6, System.getProperty("os.arch", "unknown"))
                .message(7, new ProtoWriter().string(2, locale.isEmpty() ? "en_US" : locale))
                // GameHandshake { 1 minecraft_version, 5 installed_mods: InstalledMod { 1 id, 2 name, 3 version, 4 type } }
                .message(8, new ProtoWriter()
                        .message(1, new ProtoWriter().string(1, "v1_8"))
                        .message(5, new ProtoWriter()
                                .string(1, "clientmetics")
                                .string(2, "Clientmetics")
                                .string(3, MOD_VERSION)
                                .varint(4, 2)) // TYPE_FABRIC_EXTERNAL
                        .string(8, UUID.randomUUID().toString()))
                .string(11, System.getProperty("os.version", "unknown"));
    }

}
