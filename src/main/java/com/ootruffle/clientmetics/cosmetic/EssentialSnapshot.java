package com.ootruffle.clientmetics.cosmetic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What one player has equipped, as Essential's backend reports it: a slot to cosmetic id map plus
 * each cosmetic's settings.
 *
 * <p>Essential's per-cosmetic settings are typed objects ({@code VARIANT},
 * {@code PLAYER_POSITION_ADJUSTMENT}, {@code SIDE}, {@code ANIMATION_VARIANT}); this keeps them
 * decoded and exposes only what drawing needs.
 */
public final class EssentialSnapshot {

    public final UUID player;
    /** Slot name ({@code HAT}, {@code CAPE}, ...) to cosmetic id. */
    public final Map<String, String> equipped;
    /** Cosmetic id to its settings, in the order Essential sent them. */
    public final Map<String, JsonArray> settings;

    public EssentialSnapshot(UUID player, Map<String, String> equipped, Map<String, JsonArray> settings) {
        this.player = player;
        this.equipped = Collections.unmodifiableMap(new LinkedHashMap<>(equipped));
        this.settings = Collections.unmodifiableMap(new LinkedHashMap<>(settings));
    }

    /** The asset variant this cosmetic is set to, or "" for the default one. */
    public String variantOf(String cosmeticId) {
        final JsonArray list = settings.get(cosmeticId);
        if (list != null) {
            for (JsonElement e : list) {
                if (!e.isJsonObject()) {
                    continue;
                }
                final JsonObject setting = e.getAsJsonObject();
                if (!"VARIANT".equalsIgnoreCase(type(setting))) {
                    continue;
                }
                final JsonElement data = data(setting);
                if (data != null && data.isJsonObject()) {
                    final JsonElement variant = data.getAsJsonObject().get("variant");
                    if (variant != null && variant.isJsonPrimitive()) {
                        return variant.getAsString();
                    }
                }
            }
        }
        return "";
    }

    /** A translate offset a {@code PLAYER_POSITION_ADJUSTMENT} setting asks for, in blocks, or null. */
    public float[] positionAdjustment(String cosmeticId) {
        final JsonArray list = settings.get(cosmeticId);
        if (list == null) {
            return null;
        }
        for (JsonElement e : list) {
            if (!e.isJsonObject()) {
                continue;
            }
            final JsonObject setting = e.getAsJsonObject();
            if (!"PLAYER_POSITION_ADJUSTMENT".equalsIgnoreCase(type(setting)) || !enabled(setting)) {
                continue;
            }
            final JsonElement data = data(setting);
            if (data != null && data.isJsonObject()) {
                final JsonObject o = data.getAsJsonObject();
                return new float[]{number(o, "x"), number(o, "y"), number(o, "z")};
            }
        }
        return null;
    }

    /** {@code b}/{@code type}: the setting kind. */
    private static String type(JsonObject setting) {
        final JsonElement e = setting.has("b") ? setting.get("b") : setting.get("type");
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    /** {@code d}/{@code data}: the setting's payload. */
    private static JsonElement data(JsonObject setting) {
        return setting.has("d") ? setting.get("d") : setting.get("data");
    }

    /** Settings carry {@code c}/{@code enabled}; absent means on. */
    private static boolean enabled(JsonObject setting) {
        final JsonElement e = setting.has("c") ? setting.get("c") : setting.get("enabled");
        return e == null || !e.isJsonPrimitive() || e.getAsBoolean();
    }

    private static float number(JsonObject o, String key) {
        final JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsFloat() : 0;
    }

}
