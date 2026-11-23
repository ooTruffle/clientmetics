package com.ootruffle.clientmetics.render;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;

/**
 * The first-launch terms question (see {@link com.ootruffle.clientmetics.Consent}): a wrapped,
 * multi-line message with a yes and a no button, the same as ClientTag's. The buttons only work
 * after a delay, so the message gets read, and Escape doesn't close it.
 */
public final class TermsNoticeScreen extends GuiScreen {

    private static final int YES = 0;
    private static final int NO = 1;
    private static final int MARGIN = 10;
    /** The gap above the buttons, and the buttons. */
    private static final int BUTTONS = 40;
    private static final float MIN_SCALE = 0.5f;

    private final String title;
    private final String message;
    private final String yes;
    private final String no;
    private final Consumer<Boolean> onAnswer;
    private final long readyAt;
    private List<String> lines = new ArrayList<>();
    /** How much the text is shrunk to fit (see {@link #initGui()}). */
    private float scale = 1f;

    public TermsNoticeScreen(String title, String message, String yes, String no, int delaySeconds, Consumer<Boolean> onAnswer) {
        this.title = title;
        this.message = message;
        this.yes = yes;
        this.no = no;
        this.onAnswer = onAnswer;
        this.readyAt = System.currentTimeMillis() + delaySeconds * 1000L;
    }

    @Override
    public void initGui() {
        // Shrink the text until it fits above the buttons (GUI scale Large, small windows), down
        // to half size; past that the window is too small to read it anyway.
        scale = 1f;
        while (true) {
            lines = wrap(Math.round(Math.min(width - 40, 360) / scale));
            if (textHeight() * scale <= height - MARGIN * 2 - BUTTONS || scale <= MIN_SCALE) {
                break;
            }
            scale = Math.max(MIN_SCALE, scale * 0.9f);
        }
        buttonList.clear();
        final int y = Math.min(height - MARGIN - 20, Math.round(textTop() + textHeight() * scale) + 10);
        buttonList.add(new GuiButton(YES, width / 2 - 155, y, 150, 20, yes));
        buttonList.add(new GuiButton(NO, width / 2 + 5, y, 150, 20, no));
        updateScreen();
    }

    private List<String> wrap(int wrapWidth) {
        final List<String> out = new ArrayList<>();
        for (String paragraph : message.split("\n", -1)) {
            if (paragraph.isEmpty()) {
                out.add("");
            } else {
                out.addAll(fontRendererObj.listFormattedStringToWidth(paragraph, wrapWidth));
            }
        }
        return out;
    }

    /** The title and message, unscaled. */
    private int textHeight() {
        return 20 + lines.size() * (fontRendererObj.FONT_HEIGHT + 1);
    }

    @Override
    public void updateScreen() {
        final boolean ready = System.currentTimeMillis() >= readyAt;
        for (GuiButton button : buttonList) {
            button.enabled = ready;
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.enabled && (button.id == YES || button.id == NO)) {
            onAnswer.accept(button.id == YES);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        // No Escape: the question has to be answered.
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GlStateManager.pushMatrix();
        GlStateManager.translate(width / 2f, textTop(), 0f);
        GlStateManager.scale(scale, scale, 1f);
        int y = 0;
        drawCenteredString(fontRendererObj, title, 0, y, 0xFFFFFF);
        y += 20;
        for (String line : lines) {
            drawCenteredString(fontRendererObj, line, 0, y, 0xD0D0D0);
            y += fontRendererObj.FONT_HEIGHT + 1;
        }
        GlStateManager.popMatrix();
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /** Centres the text and buttons together, or starts at the top margin if they don't fit. */
    private int textTop() {
        final int total = Math.round(textHeight() * scale) + BUTTONS;
        return Math.max(MARGIN, (height - total) / 2);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

}
