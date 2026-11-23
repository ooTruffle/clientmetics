package com.ootruffle.clientmetics.platform;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

/** {@link Game} for 1.8.9. */
public final class LegacyGame implements Game {

    @Override
    public UUID sessionId() {
        return Minecraft.getMinecraft().getSession().getProfile().getId();
    }

    @Override
    public String sessionName() {
        return Minecraft.getMinecraft().getSession().getUsername();
    }

    @Override
    public String sessionToken() {
        return Minecraft.getMinecraft().getSession().getToken();
    }

    @Override
    public void joinServer(String serverId) throws Exception {
        final Minecraft mc = Minecraft.getMinecraft();
        mc.getSessionService().joinServer(mc.getSession().getProfile(), mc.getSession().getToken(), serverId);
    }

    @Override
    public boolean inWorld() {
        final Minecraft mc = Minecraft.getMinecraft();
        return mc.theWorld != null && mc.thePlayer != null;
    }

    @Override
    public boolean singleplayer() {
        return Minecraft.getMinecraft().isSingleplayer();
    }

    @Override
    public List<Player> playersByDistance() {
        final Minecraft mc = Minecraft.getMinecraft();
        final EntityPlayer self = mc.thePlayer;
        if (mc.theWorld == null || self == null) {
            return new ArrayList<>();
        }
        final List<EntityPlayer> players = new ArrayList<>(mc.theWorld.playerEntities);
        players.sort(Comparator.comparingDouble(p -> p.getDistanceSqToEntity(self)));
        final List<Player> out = new ArrayList<>(players.size());
        for (EntityPlayer player : players) {
            out.add(new Player(player.getUniqueID(), player == self));
        }
        return out;
    }

}
