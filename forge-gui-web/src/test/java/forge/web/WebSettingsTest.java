package forge.web;

import forge.game.phase.PhaseType;
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
            WebSettings.set(PlayerSettings.saved(), null, "highlightPlayable", String.valueOf(!was));
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
            WebSettings.set(PlayerSettings.saved(), recording(told), "highlightPlayable", String.valueOf(!was));
            Assert.assertEquals(told, List.of(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS + "=" + !was));
            Assert.assertEquals(prefs.getPrefBoolean(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS), !was);
        } finally {
            prefs.setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, was);
            prefs.save();
        }
    }

    // Every browser shares the process's preferences, which are the host's, so a guest's choice must stay its own
    @Test
    public void aGuestsSettingLeavesTheHostsAlone() {
        final ForgePreferences prefs = FModel.getPreferences();
        final boolean was = prefs.getPrefBoolean(FPref.YIELD_INTERRUPT_ON_ATTACKERS);
        final PlayerSettings guest = PlayerSettings.fresh();
        WebSettings.set(guest, null, "interruptAttackers", String.valueOf(!was));
        Assert.assertEquals(guest.getBoolean(FPref.YIELD_INTERRUPT_ON_ATTACKERS), !was);
        Assert.assertEquals(prefs.getPrefBoolean(FPref.YIELD_INTERRUPT_ON_ATTACKERS), was,
                "a guest's setting reached the host's preferences");
    }

    @Test
    public void aGuestStartsFromForgesDefaultsNotTheHostsChoices() {
        final ForgePreferences prefs = FModel.getPreferences();
        final String was = prefs.getPref(FPref.UI_TARGETING_OVERLAY);
        try {
            prefs.setPref(FPref.UI_TARGETING_OVERLAY, "0".equals(FPref.UI_TARGETING_OVERLAY.getDefault()) ? "2" : "0");
            Assert.assertEquals(PlayerSettings.fresh().get(FPref.UI_TARGETING_OVERLAY), FPref.UI_TARGETING_OVERLAY.getDefault());
            Assert.assertEquals(PlayerSettings.saved().get(FPref.UI_TARGETING_OVERLAY), prefs.getPref(FPref.UI_TARGETING_OVERLAY));
        } finally {
            prefs.setPref(FPref.UI_TARGETING_OVERLAY, was);
        }
    }

    // The host's engine and the client's own controller each keep a copy, seeded from the shared preferences
    @Test
    public void aGamesCopyOfTheSettingsIsThisPlayers() {
        final PlayerSettings guest = PlayerSettings.fresh();
        guest.set(FPref.YIELD_AUTO_PASS_NO_ACTIONS, true);
        final List<String> told = new ArrayList<>();
        WebSettings.applyAll(guest, recording(told));
        Assert.assertTrue(told.contains(FPref.YIELD_AUTO_PASS_NO_ACTIONS + "=true"), told.toString());
        Assert.assertEquals(told.size(), PlayerSettings.PER_PLAYER_ON_HOST.size(),
                "every setting the host keeps per player should be sent, and the auto-yield mode, which it does not, should not");
    }

    @Test
    public void settingARowOfStopsSetsItWhateverItWasAndSaysWhatChanged() {
        final PlayerSettings guest = PlayerSettings.fresh();
        WebSettings.setStops(guest, true, List.of(PhaseType.MAIN1, PhaseType.MAIN2));
        Assert.assertEquals(WebSettings.stops(guest, FPref.PHASES_HUMAN), List.of(PhaseType.MAIN1, PhaseType.MAIN2));
        Assert.assertEquals(WebSettings.setStops(guest, true, List.of(PhaseType.MAIN2)), List.of(PhaseType.MAIN1),
                "only the stop that went off changed");
        Assert.assertEquals(WebSettings.setStops(guest, true, List.of(PhaseType.MAIN2)), List.of(), "setting it again changes nothing");
        Assert.assertEquals(WebSettings.stops(guest, FPref.PHASES_HUMAN), List.of(PhaseType.MAIN2));
    }
}
