package com.ootruffle.clientmetics.cosmetic;

import com.ootruffle.clientmetics.net.ProtoMessage;

/**
 * {@code lunarclient.websocket.cosmetic.v2.EquippedCosmetic}: 1 cosmetic_id, 2 geckolib_metadata
 * (Struct, unused), 3 cloth_cloak, 4 show_over_skin_layer, 5 show_over_helmet, 6 show_over_chestplate,
 * 7 show_over_leggings, 8 show_over_boots, 9 hat_height_offset (float), 10 flip_arm.
 */
public final class EquippedCosmetic {

    public final int id;
    public final boolean clothCloak;
    public final boolean showOverSkinLayer;
    public final boolean showOverHelmet;
    public final boolean showOverChestplate;
    public final boolean showOverLeggings;
    public final boolean showOverBoots;
    public final float hatHeightOffset;
    public final boolean flipArm;

    private EquippedCosmetic(ProtoMessage m) {
        id = (int) m.varint(1, 0);
        clothCloak = m.bool(3);
        showOverSkinLayer = m.bool(4);
        showOverHelmet = m.bool(5);
        showOverChestplate = m.bool(6);
        showOverLeggings = m.bool(7);
        showOverBoots = m.bool(8);
        hatHeightOffset = m.float32(9, 0);
        flipArm = m.bool(10);
    }

    public static EquippedCosmetic decode(ProtoMessage m) {
        return new EquippedCosmetic(m);
    }

    /** A cosmetic shown over every armor piece, for wardrobe previews. */
    public static EquippedCosmetic preview(int id) {
        return new EquippedCosmetic(ProtoMessage.parse(new com.ootruffle.clientmetics.net.ProtoWriter()
                .varint(1, id).varint(4, 1).varint(5, 1).varint(6, 1).varint(7, 1).varint(8, 1).toByteArray()));
    }

}
