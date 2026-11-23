package com.ootruffle.clientmetics.cosmetic;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Decoded player cosmetics from Dawn's players.tracked gateway channel. */
public final class DawnCosmeticSnapshot {
    public final UUID player;
    /** Dawn sends slot names mapped to cosmetic asset slugs. */
    public final Map<String, String> equipped;

    private DawnCosmeticSnapshot(UUID player, Map<String, String> equipped) {
        this.player = player;
        this.equipped = Collections.unmodifiableMap(equipped);
    }

    /** A snapshot made locally (dev checks). */
    public static DawnCosmeticSnapshot of(UUID player, Map<String, String> equipped) {
        return new DawnCosmeticSnapshot(player, new LinkedHashMap<>(equipped));
    }

    /** Decodes either a snapshot or an equipped event payload; malformed entries are skipped. */
    public static DawnCosmeticSnapshot decode(JsonObject payload) {
        if (payload == null) return null;
        final String rawUuid = string(payload, "mc_uuid");
        if (rawUuid == null) return null;
        final UUID uuid;
        try { uuid = UUID.fromString(rawUuid); }
        catch (IllegalArgumentException ignored) { return null; }
        final Map<String, String> items = new LinkedHashMap<>();
        if (payload.has("equipped") && payload.get("equipped").isJsonObject()) {
            for (Map.Entry<String, JsonElement> item : payload.getAsJsonObject("equipped").entrySet()) {
                if (item.getValue().isJsonPrimitive() && item.getValue().getAsJsonPrimitive().isString()) {
                    final String slug = item.getValue().getAsString();
                    if (!slug.isEmpty()) items.put(item.getKey(), slug);
                }
            }
        }
        return new DawnCosmeticSnapshot(uuid, items);
    }

    private static String string(JsonObject json, String key) {
        final JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }
}
