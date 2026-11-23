package com.ootruffle.clientmetics.platform;

import java.util.List;
import java.util.UUID;

/**
 * Everything the version-independent code needs from the running game. Each Minecraft version
 * provides one ({@link #set}) from its entrypoint, before anything else runs.
 * <p>
 * Methods marked "client thread" may only be called there; the others are safe from any thread.
 */
public interface Game {

    /** The logged-in account's UUID, or null. */
    UUID sessionId();

    /** The logged-in account's name. */
    String sessionName();

    /** The session's access token, or null. Never logged or sent anywhere but Mojang's join. */
    String sessionToken();

    /** Joins {@code serverId} with Mojang's session server, as the game does for a server. */
    void joinServer(String serverId) throws Exception;

    /** Whether a world is loaded. Client thread. */
    boolean inWorld();

    /** Whether the loaded world is the integrated (singleplayer) server. Client thread. */
    boolean singleplayer();

    /** The players in the loaded world, nearest to you first (you first). Client thread. */
    List<Player> playersByDistance();

    /** A player in the world. */
    final class Player {
        public final UUID uuid;
        /** This is you. */
        public final boolean self;

        public Player(UUID uuid, boolean self) {
            this.uuid = uuid;
            this.self = self;
        }
    }

    // ---- the running game ----

    static Game get() {
        final Game game = Holder.game;
        if (game == null) {
            throw new IllegalStateException("Clientmetics: no Game set for this Minecraft version");
        }
        return game;
    }

    static void set(Game game) {
        Holder.game = game;
    }

    final class Holder {
        private static volatile Game game;

        private Holder() {}
    }

}
