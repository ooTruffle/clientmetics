package com.ootruffle.clientmetics.render;

import com.ootruffle.clientmetics.Clientmetics;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiScreen;

/**
 * A question with a yes and a no button (the first-launch warning, the updater's prompts). It's
 * drawn with PolyCompose in OneConfig's style, or as a plain Minecraft screen
 * ({@link TermsNoticeScreen}) when PolyCompose's UI is unavailable (turned off with
 * {@code -Dpolycompose.ui.disable}, or no AWT).
 */
public final class Notice {

    /** Implemented by both versions of the screen, so callers can tell it's open. */
    public interface Screen {}

    private Notice() {}

    /**
     * The screen. Its buttons only work after {@code delaySeconds}, Escape doesn't close it, and
     * {@code onAnswer} gets true for yes.
     */
    public static GuiScreen create(String title, String message, String yes, String no, int delaySeconds, Consumer<Boolean> onAnswer) {
        try {
            final GuiScreen screen = com.ootruffle.clientmetics.compat.compose.ComposeNotice.createOrNull(
                    title, message, yes, no, delaySeconds, onAnswer);
            if (screen != null) {
                return screen;
            }
        } catch (Throwable t) {
            Clientmetics.LOGGER.warn("Couldn't make the PolyCompose notice; using the plain one", t);
        }
        return new TermsNoticeScreen(title, message, yes, no, delaySeconds, onAnswer);
    }

}
