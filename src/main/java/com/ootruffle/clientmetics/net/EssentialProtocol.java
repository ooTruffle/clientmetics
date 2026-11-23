package com.ootruffle.clientmetics.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Frame layout for Essential's connection manager socket.
 *
 * <p>Every message is a binary frame of big-endian {@code int32} fields, in order: the packet type
 * id, the length and bytes of the packet id (a UUID string, empty when unsolicited), and the length
 * and bytes of the UTF-8 JSON body. Type ids are assigned per connection and per direction; id 0 is
 * always the register packet, whose body is {@code {"a": class name, "b": id}}.
 *
 * <p>This matches the format ClientTag uses against the same endpoint (Essential 1.5.0.1,
 * protocol 11). The keep-alive echo relies on the packet id being preserved verbatim.
 */
final class EssentialProtocol {

    private EssentialProtocol() {}

    /** A decoded frame; {@code body} is null if the payload wasn't a JSON object. */
    static final class Frame {
        final int typeId;
        final String packetId;
        final JsonObject body;

        Frame(int typeId, String packetId, JsonObject body) {
            this.typeId = typeId;
            this.packetId = packetId;
            this.body = body;
        }
    }

    /** One frame, ready to send. */
    static ByteBuffer encode(int typeId, String packetId, byte[] bodyBytes) {
        final byte[] idBytes = packetId.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer frame = ByteBuffer.allocate(12 + idBytes.length + bodyBytes.length);
        frame.putInt(typeId).putInt(idBytes.length).put(idBytes).putInt(bodyBytes.length).put(bodyBytes).flip();
        return frame;
    }

    /** Parses one frame, or returns null if it is too short or its lengths don't add up. */
    static Frame decode(byte[] message) {
        final ByteBuffer buf = ByteBuffer.wrap(message);
        try {
            final int typeId = buf.getInt();
            final String packetId = readString(buf);
            final JsonObject body = parseObject(readString(buf));
            return body == null ? null : new Frame(typeId, packetId, body);
        } catch (BufferUnderflowException | IllegalArgumentException e) {
            return null;
        }
    }

    private static String readString(ByteBuffer buf) {
        final int length = buf.getInt();
        if (length < 0 || length > buf.remaining()) {
            throw new IllegalArgumentException("bad string length " + length);
        }
        final byte[] bytes = new byte[length];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static JsonObject parseObject(String text) {
        try {
            final JsonElement parsed = new JsonParser().parse(text);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

}
