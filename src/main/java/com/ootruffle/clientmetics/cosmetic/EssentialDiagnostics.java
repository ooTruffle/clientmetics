package com.ootruffle.clientmetics.cosmetic;

import com.ootruffle.clientmetics.Clientmetics;
import com.ootruffle.clientmetics.Config;
import com.ootruffle.clientmetics.asset.Assets;
import com.ootruffle.clientmetics.asset.EssentialCatalog;
import com.ootruffle.clientmetics.asset.EssentialFiles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A one-shot report of what Essential support actually has, written to the log on demand
 * ({@code /clientmetics essential}). It exists because "the tab shows too much" and "nothing loads"
 * look identical from the outside: this prints the numbers that tell them apart.
 */
public final class EssentialDiagnostics {

    private EssentialDiagnostics() {}

    public static void report() {
        final Config config = Config.get();
        final EssentialCatalog catalog = EssentialCatalog.get();
        final EssentialAccount.State state = EssentialAccount.state();
        final StringBuilder out = new StringBuilder("Essential diagnostics:");
        line(out, "connected", EssentialCosmeticManager.isConnected());
        line(out, "status", EssentialAccount.status());
        line(out, "settings", "enabled=" + config.enabled + " termsAccepted=" + config.termsNoticeSeen
                + " essential=" + config.essential + " debug=" + config.debug);
        line(out, "catalogue", catalog == null ? "not loaded" : catalog.size() + " cosmetics");
        line(out, "owned", state == null ? "unknown (no outfit yet)" : state.owned.size() + " cosmetics");
        if (state != null) {
            line(out, "selected outfit", state.outfitId);
            line(out, "slots", String.valueOf(state.slots));
            line(out, "owned sample", sample(state.owned, 8));
        }
        line(out, "assets", EssentialFiles.failedCount() + " failed, "
                + Assets.count("geo") + " models loaded");
        line(out, "players", EssentialCosmeticManager.knownPlayers() + " with outfits");
        Clientmetics.LOGGER.info(out.toString());
    }

    private static void line(StringBuilder out, String key, Object value) {
        out.append("\n  ").append(key).append(": ").append(value);
    }

    private static String sample(Collection<String> ids, int count) {
        final List<String> out = new ArrayList<>();
        for (String id : ids) {
            if (out.size() >= count) {
                out.add("...");
                break;
            }
            out.add(id);
        }
        return out.isEmpty() ? "(none)" : String.join(", ", out);
    }

}
