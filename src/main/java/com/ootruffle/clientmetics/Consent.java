package com.ootruffle.clientmetics;

import com.ootruffle.clientmetics.asset.Assets;
import com.ootruffle.clientmetics.asset.Catalog;
import com.ootruffle.clientmetics.asset.DawnCatalog;
import com.ootruffle.clientmetics.compat.ClientTagCompat;
import com.ootruffle.clientmetics.render.TermsNoticeScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

/**
 * The first-launch warning, working the way ClientTag's does. Nothing is loaded or connects
 * anywhere until it's accepted - in the prompt, or by setting {@code termsNoticeSeen} to true in
 * config.json (e.g. for a modpack).
 * <p>
 * It's a screen over whatever is open once the game has loaded, opened again if another mod
 * replaces it before it's answered. Its buttons only work after {@link #DELAY_SECONDS}. Declining
 * keeps Clientmetics idle until the next launch, which asks again.
 * <p>
 * With ClientTag installed its warning goes first, and this one follows once that's answered.
 */
public final class Consent {

    /**
     * Bump this whenever the message changes what's being agreed to: everyone who accepted an
     * older version is asked again (see {@link Config#load()}).
     * <ul>
     *     <li>0: Lunar and Dawn (before this was tracked).</li>
     *     <li>2: Essential, now on by default, and the Lunar switch.</li>
     * </ul>
     */
    public static final int NOTICE_VERSION = 2;

    private static final int DELAY_SECONDS = 4;
    /** Ticks between attempts to put the question back after something replaced it. */
    private static final int REOPEN_TICKS = 40;
    /** Ticks to wait for ClientTag's warning to show up before going ahead without it. */
    private static final int CLIENTTAG_WAIT_TICKS = 100;

    private static final String TITLE = "Clientmetics - please read";
    private static final String MESSAGE =
            "Clientmetics shows the cosmetics other players have equipped by logging your Minecraft account into "
            + "Lunar Client, Dawn Client and Essential services, and by reading or downloading their cosmetic files.\n"
            + "This may break those services' terms of service or a server's rules, and they could ban or "
            + "restrict your account or your access to them.\n"
            + "\n"
            + "Clientmetics is unofficial and isn't endorsed by Lunar, Dawn or Essential. Each can be turned off "
            + "with its own setting (Lunar, Dawn and Essential Cosmetics).\n"
            + "Essential support talks to Essential's own connection manager, which Essential neither documents nor "
            + "permits, and which its source licence forbids reimplementing. It can break or be refused at any time.\n"
            + "\n"
            + "Enable Clientmetics?";
    private static final String YES = "I understand, enable";
    private static final String NO = "Not now";

    private static boolean declined;
    private static boolean started;
    private static int reopenCooldown;
    private static int clientTagWait;

    private Consent() {}

    /** Whether the warning has been accepted; until then nothing is loaded or connects. */
    public static boolean accepted() {
        return Config.get().termsNoticeSeen;
    }

    /** Asks again after a "Not now" (e.g. when the wardrobe command is used). */
    public static void askAgain() {
        declined = false;
        reopenCooldown = 0;
    }

    /** Called every client tick: asks until answered, and loads the catalogues once accepted. */
    public static void onClientTick() {
        if (accepted()) {
            if (!started) {
                started = true;
                // The catalogue is a few MB of JSON: read it off the client thread.
                if (Config.get().lunar) {
                    Assets.EXECUTOR.execute(Catalog::load);
                }
                if (Config.get().dawn) {
                    Assets.EXECUTOR.execute(DawnCatalog::load);
                }
                if (Config.get().essential) {
                    Assets.EXECUTOR.execute(com.ootruffle.clientmetics.asset.EssentialCatalog::load);
                }
            }
            return;
        }
        if (declined) {
            return;
        }
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen instanceof TermsNoticeScreen) {
            return;
        }
        // ClientTag's warning goes first. If it never shows up, don't wait for it forever.
        if (ClientTagCompat.consentPending()) {
            if (ClientTagCompat.isAsking()) {
                clientTagWait = 0;
            }
            if (++clientTagWait < CLIENTTAG_WAIT_TICKS) {
                return;
            }
        }
        if (--reopenCooldown > 0) {
            return;
        }
        reopenCooldown = REOPEN_TICKS;
        final GuiScreen previous = mc.currentScreen;
        mc.displayGuiScreen(new TermsNoticeScreen(TITLE, MESSAGE, YES, NO, DELAY_SECONDS, yes -> {
            answer(yes);
            mc.displayGuiScreen(previous);
        }));
    }

    private static void answer(boolean yes) {
        if (yes) {
            Config.get().termsNoticeSeen = true;
            Config.get().termsNoticeVersion = NOTICE_VERSION;
            Config.save();
            Clientmetics.LOGGER.info("Warning accepted - Clientmetics is on");
        } else {
            declined = true;
            Clientmetics.LOGGER.info("Warning declined - Clientmetics stays off until the next launch");
        }
    }

}
