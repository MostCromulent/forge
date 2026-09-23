package forge.web;

import forge.interfaces.IGameController;
import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

public class WebSettingsTest {
    @BeforeClass
    public void initModel() {
        WebTestSupport.initModel();
    }

    /** A controller that only records the yield preferences it is told about. */
    private static IGameController recording(final List<String> told) {
        return (IGameController) Proxy.newProxyInstance(IGameController.class.getClassLoader(),
                new Class<?>[]{IGameController.class}, (proxy, method, args) -> {
                    if ("setYieldPref".equals(method.getName())) {
                        told.add(args[0] + "=" + args[1]);
                    }
                    return null;
                });
    }

    // A browser's first-run defaults are sent as its first match is attached, before the game has a controller
    @Test
    public void aSettingSentBeforeTheGameStartsIsKept() {
        final ForgePreferences prefs = FModel.getPreferences();
        final boolean was = prefs.getPrefBoolean(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS);
        try {
            WebSettings.set(null, "highlightPlayable", String.valueOf(!was));
            Assert.assertEquals(prefs.getPrefBoolean(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS), !was);
        } finally {
            prefs.setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, was);
            prefs.save();
        }
    }

    @Test
    public void aSettingSentToAGuiWithNoGameYetIsKept() {
        final ForgePreferences prefs = FModel.getPreferences();
        final boolean was = prefs.getPrefBoolean(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS);
        try {
            final WebGuiGame gui = new WebGuiGame();
            final com.google.gson.JsonObject msg = JsonCodec.message("setSetting");
            msg.addProperty("key", "highlightPlayable");
            msg.addProperty("value", String.valueOf(!was));
            gui.onBrowserMessage(msg);
            Assert.assertEquals(prefs.getPrefBoolean(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS), !was);
            gui.close();
        } finally {
            prefs.setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, was);
            prefs.save();
        }
    }

    // The host highlights playable cards and interrupts auto-pass from its own copy of these, so a change made
    // mid-game that only reached the preference file would do nothing until the next game
    @Test
    public void aSharedSettingChangedMidGameReachesTheHost() {
        final ForgePreferences prefs = FModel.getPreferences();
        final boolean was = prefs.getPrefBoolean(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS);
        final List<String> told = new ArrayList<>();
        try {
            WebSettings.set(recording(told), "highlightPlayable", String.valueOf(!was));
            Assert.assertEquals(told, List.of(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS + "=" + !was));
            Assert.assertEquals(prefs.getPrefBoolean(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS), !was);
        } finally {
            prefs.setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, was);
            prefs.save();
        }
    }
}
