package com.ootruffle.clientmetics;

import com.ootruffle.clientmetics.asset.Catalog;
import com.ootruffle.clientmetics.asset.DawnCatalog;
import com.ootruffle.clientmetics.asset.MemoryJanitor;
import com.ootruffle.clientmetics.asset.Textures;
import com.ootruffle.clientmetics.compat.ClientTagCompat;
import com.ootruffle.clientmetics.compat.EssentialCompat;
import com.ootruffle.clientmetics.compat.OneConfigCompat;
import com.ootruffle.clientmetics.cosmetic.CosmeticManager;
import com.ootruffle.clientmetics.cosmetic.DawnCosmeticManager;
import net.minecraft.client.Minecraft;
import net.ornithemc.osl.entrypoints.api.ModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Shows the Lunar Client cosmetics other players have equipped, so a vanilla 1.8.9 player sees
 * what Lunar users see.
 * <ul>
 *     <li>Who wears what comes from Lunar's game websocket ({@link CosmeticManager}).</li>
 *     <li>Models and textures are read from the user's own Lunar install ({@link Catalog}); none
 *     are bundled with this mod.</li>
 *     <li>Drawing happens in a player render layer and the cape texture hook (see {@code render}).</li>
 * </ul>
 * Dawn Client cosmetics work the same way: who wears what comes from Dawn's gateway
 * ({@link DawnCosmeticManager}), and the files from the user's Dawn install or Dawn's public asset
 * host ({@link DawnCatalog}).
 * <p>
 * It only shows cosmetics players own and have equipped; it never unlocks anything.
 * Essential is supported through soft coexistence: when its mod is present, Essential remains
 * responsible for Essential sign-in, outfits, assets and rendering.
 */
public final class Clientmetics implements ModInitializer {

    public static final Logger LOGGER = LogManager.getLogger("Clientmetics");

    @Override
    public void init() {
        com.ootruffle.clientmetics.platform.Game.set(new com.ootruffle.clientmetics.platform.LegacyGame());
        Config.load();
        OneConfigCompat.init();
        ClientTagCompat.init();
        EssentialCompat.init();
        com.ootruffle.clientmetics.compat.HostClients.init();
        // The catalogues are loaded once the first-launch warning has been accepted (see Consent).
        LOGGER.info("Clientmetics initialised");
    }

    private static volatile boolean openWardrobe;

    /**
     * Opens the Lunar Wardrobe on the next tick: commands run while the chat screen is still
     * closing, which would close the wardrobe straight away.
     */
    public static void openWardrobe() {
        openWardrobe = true;
    }

    /** Like {@link #openWardrobe()}, showing your Dawn account ({@code /dawnwardrobe}). */
    public static void openDawnWardrobe() {
        openDawn = true;
        openWardrobe = true;
    }

    /** Like {@link #openWardrobe()}, showing your Essential account ({@code /essentialwardrobe}). */
    public static void openEssentialWardrobe() {
        openEssential = true;
        openWardrobe = true;
    }

    private static volatile boolean openDawn;
    private static volatile boolean openEssential;

    /** The wardrobe screen (drawn with PolyCompose). */
    public static net.minecraft.client.gui.GuiScreen wardrobeScreen() {
        return com.ootruffle.clientmetics.compat.compose.ComposeWardrobe.create();
    }

    /**
     * Opens the wardrobe. If PolyCompose can't make or open it (say, a version this mod wasn't
     * built against), says so in the log and chat instead of taking the game down from the client
     * tick. ({@code -Dpolycompose.ui.disable} doesn't land here: PolyCompose refuses the screen
     * itself, with its own chat message.)
     */
    private static void openWardrobeScreen() {
        final Minecraft mc = Minecraft.getMinecraft();
        try {
            mc.displayGuiScreen(wardrobeScreen());
        } catch (Throwable t) {
            LOGGER.error("Couldn't open the wardrobe (PolyCompose {} is installed; Clientmetics needs 3.0 or newer)",
                    net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("polycompose")
                            .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?"), t);
            if (!(mc.currentScreen instanceof net.minecraft.client.gui.GuiMainMenu)) {
                mc.displayGuiScreen(null);
            }
            if (mc.thePlayer != null) {
                mc.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText(
                        "§c[Clientmetics] Couldn't open the wardrobe: " + t + ". See the log for details."));
            }
        }
    }

    /** End of every client tick (see {@code MinecraftMixin}). */
    public static void onClientTick() {
        if (openWardrobe) {
            openWardrobe = false;
            final boolean dawn = openDawn;
            final boolean essential = openEssential;
            openDawn = false;
            openEssential = false;
            if (Consent.accepted()) {
                if (dawn && com.ootruffle.clientmetics.render.WardrobeSource.DAWN.available()) {
                    com.ootruffle.clientmetics.render.WardrobeSource.select(com.ootruffle.clientmetics.render.WardrobeSource.DAWN);
                } else if (essential && com.ootruffle.clientmetics.render.WardrobeSource.ESSENTIAL.available()) {
                    com.ootruffle.clientmetics.render.WardrobeSource.select(com.ootruffle.clientmetics.render.WardrobeSource.ESSENTIAL);
                }
                openWardrobeScreen();
            } else {
                // The wardrobe talks to Lunar: ask the first-launch question (again) instead.
                Consent.askAgain();
            }
        }
        DevAutotest.onClientTick();
        // Nothing is loaded or connects anywhere until the first-launch warning has been accepted.
        Consent.onClientTick();
        Textures.onTick();
        MemoryJanitor.onClientTick();
        OneConfigCompat.onClientTick();
        CosmeticManager.onClientTick();
        DawnCosmeticManager.onClientTick();
        com.ootruffle.clientmetics.cosmetic.EssentialCosmeticManager.onClientTick();
    }

}
