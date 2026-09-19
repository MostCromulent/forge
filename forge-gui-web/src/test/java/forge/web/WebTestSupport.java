package forge.web;

import forge.StaticData;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeNetPreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
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

    static void skipUnlessStress() {
        if (!"true".equalsIgnoreCase(System.getProperty("run.stress.tests"))) {
            throw new SkipException("Game-playing test skipped. Use -Drun.stress.tests=true to run.");
        }
    }
}
