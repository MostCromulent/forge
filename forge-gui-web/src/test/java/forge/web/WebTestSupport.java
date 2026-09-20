package forge.web;

import forge.StaticData;
import forge.game.player.Player;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.net.server.RemoteClientGuiGame;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeNetPreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.PlayerControllerHuman;
import org.testng.SkipException;

final class WebTestSupport {
    private WebTestSupport() {}

    /** GuiBase must be set before ForgeConstants loads, because its static init reads the assets dir. */
    static synchronized void initModel() {
        if (GuiBase.getInterface() == null) {
            GuiBase.setInterface(new WebGuiBase());
        }
        if (StaticData.instance() == null) {
            FModel.initialize(null, prefs -> {
                prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                prefs.setPref(FPref.UI_LANGUAGE, "en-US");
                prefs.setPref(FPref.ENFORCE_DECK_LEGALITY, false);
                return null;
            });
        }
        FModel.getNetPreferences().setPref(ForgeNetPreferences.FNetPref.UPnP, "NEVER");
    }

    /** The host's own view of the web seat. Seeding cards with a {@link forge.game.GameState} fires no game event,
     *  so a test that places them must call {@code updateGameView()} on this to send them. */
    static RemoteClientGuiGame remoteGui(final HostedMatch match) {
        for (final Player p : match.getGame().getPlayers()) {
            if (p.getController() instanceof PlayerControllerHuman pch && pch.getGui() instanceof RemoteClientGuiGame r) {
                return r;
            }
        }
        throw new AssertionError("no remote human seat");
    }

    static void skipUnlessStress() {
        if (!"true".equalsIgnoreCase(System.getProperty("run.stress.tests"))) {
            throw new SkipException("Game-playing test skipped. Use -Drun.stress.tests=true to run.");
        }
    }
}
