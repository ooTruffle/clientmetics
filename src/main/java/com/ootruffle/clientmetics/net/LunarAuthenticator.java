package com.ootruffle.clientmetics.net;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Gets a JWT from Lunar's authenticator, proving who we are with the game's own Mojang session
 * (the same check an online-mode server does):
 * <ol>
 *     <li>-> {@code HelloMessage { identity, initiator }}</li>
 *     <li><- {@code EncryptionRequestMessage { public_key, random_bytes }}</li>
 *     <li>Mojang session join with the hash of a random secret + that key</li>
 *     <li>-> {@code EncryptionResponseMessage { rsa(secret), rsa(random_bytes) }}</li>
 *     <li><- {@code AuthSuccessMessage { jwt, encrypted_jwt }}</li>
 * </ol>
 * The initiator names what the token is for; {@code GAME_WEBSOCKET} is the game socket.
 */
public final class LunarAuthenticator {

    private static final String AUTH_URL = "wss://authenticator.lunarclientprod.com/launcher-v3";
    private static final String INITIATOR = "GAME_WEBSOCKET";
    private static final long TIMEOUT_MS = 15_000;

    /** Performs the Mojang session server join for the given server ID hash. */
    @FunctionalInterface
    public interface SessionJoiner {
        void join(String serverId) throws Exception;
    }

    private LunarAuthenticator() {}

    public static String fetchToken(UUID uuid, String name, SessionJoiner joiner) throws Exception {
        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", LunarSocket.USER_AGENT);
        headers.put("Accept", "application/x-protobuf");

        final WsConnection ws = WsConnection.open(AUTH_URL, headers);
        try {
            ws.send(new ProtoWriter()
                    .message(1, new ProtoWriter()
                            .message(1, ProtoWriter.uuidAndUsername(uuid, name))
                            .string(2, INITIATOR))
                    .toByteArray());

            final ProtoMessage challenge = ProtoMessage.parse(ws.nextMessage(TIMEOUT_MS)).message(1);
            if (challenge == null || challenge.bytes(1) == null || challenge.bytes(2) == null) {
                throw new IOException("authenticator sent no key challenge");
            }
            final byte[] publicKeyDer = challenge.bytes(1);
            final byte[] nonce = challenge.bytes(2);

            final byte[] secret = new byte[16];
            new SecureRandom().nextBytes(secret);
            joiner.join(minecraftDigest(secret, publicKeyDer));

            final PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(publicKeyDer));
            ws.send(new ProtoWriter()
                    .message(2, new ProtoWriter().bytes(1, rsaEncrypt(key, secret)).bytes(2, rsaEncrypt(key, nonce)))
                    .toByteArray());

            final ProtoMessage parsed = ProtoMessage.parse(ws.nextMessage(TIMEOUT_MS));
            final String jwt = findJwt(parsed.message(2), secret);
            if (jwt == null) {
                throw new IOException("authenticator did not issue a token (reply " + parsed.describe() + ")");
            }
            return jwt;
        } finally {
            ws.close();
        }
    }

    /** Plaintext {@code jwt}, or AES-128-GCM {@code encrypted_jwt} ({@code iv[12] | ciphertext | tag[16]}). */
    private static String findJwt(ProtoMessage issued, byte[] secret) {
        if (issued == null) {
            return null;
        }
        final String plain = asJwt(issued.string(1));
        if (plain != null) {
            return plain;
        }
        final byte[] encrypted = issued.bytes(2);
        if (encrypted == null || encrypted.length <= 28) {
            return null;
        }
        try {
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret, "AES"),
                    new GCMParameterSpec(128, Arrays.copyOfRange(encrypted, 0, 12)));
            final byte[] decrypted = cipher.doFinal(Arrays.copyOfRange(encrypted, 12, encrypted.length));
            return asJwt(new String(decrypted, StandardCharsets.US_ASCII).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String asJwt(String s) {
        return s != null && s.startsWith("eyJ") && s.chars().filter(c -> c == '.').count() == 2
                && s.matches("[A-Za-z0-9_\\-.=]+") ? s : null;
    }

    /** Minecraft's signed SHA-1 hex digest, as used for session server IDs. */
    static String minecraftDigest(byte[]... parts) throws Exception {
        final MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        for (byte[] part : parts) {
            sha1.update(part);
        }
        return new BigInteger(sha1.digest()).toString(16);
    }

    private static byte[] rsaEncrypt(PublicKey key, byte[] data) throws Exception {
        final Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return cipher.doFinal(data);
    }

}
