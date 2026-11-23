package com.ootruffle.clientmetics.net;

import com.ootruffle.clientmetics.compat.ClientTagCompat;

/**
 * The Mojang session join Lunar's authenticator checks, made from the game's own logged-in
 * session (never a password or a typed-in token). Goes through ClientTag's queue when it's
 * installed; otherwise joins are kept a few seconds apart, which is all one mod needs.
 */
public final class SessionJoins {

    private static final long MIN_GAP_MS = 2_500;
    private static long lastJoinAt;

    private SessionJoins() {}

    public static synchronized void join(String serverId) throws Exception {
        if (ClientTagCompat.join(serverId)) {
            return;
        }
        final long wait = lastJoinAt + MIN_GAP_MS - System.currentTimeMillis();
        if (wait > 0) {
            Thread.sleep(wait);
        }
        try {
            com.ootruffle.clientmetics.platform.Game.get().joinServer(serverId);
        } finally {
            lastJoinAt = System.currentTimeMillis();
        }
    }

}
