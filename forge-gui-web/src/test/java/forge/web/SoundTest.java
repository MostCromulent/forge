package forge.web;

import com.google.gson.JsonObject;
import forge.deck.Deck;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.sound.SoundSystem;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;

/** Game events reach the browser as sound names, and the server can serve the file each name stands for. */
public class SoundTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    @Test(timeOut = 300000)
    public void soundNamesReachTheBrowserAndResolveToFiles() throws Exception {
        WebTestSupport.skipUnlessStress();
        final String saved = FModel.getPreferences().getPref(FPref.UI_ENABLE_SOUNDS);
        // In memory only: the player may have sound switched off and their file must not change
        FModel.getPreferences().setPref(FPref.UI_ENABLE_SOUNDS, true);
        final LocalGame local = new LocalGame();
        final Deck bears = TestDecks.of("Bears", "Grizzly Bears", 20, "Forest", 20);
        final Deck forests = TestDecks.of("Forests", "Forest", 40);
        try {
            final WebGuiGame gui = new WebGuiGame();
            final FakeBrowser browser = new FakeBrowser(gui, true);
            gui.attach(browser);
            GuiBase.getInterface().invokeInEdtAndWait(() -> local.startMatch("Web Player", bears, "AI", forests, gui));

            JsonObject sound = null;
            for (int i = 0; i < 1200 && sound == null; i++) {
                sound = browser.last("sound");
                Thread.sleep(100);
            }
            Assert.assertNotNull(sound, "no sound reached the browser");
            final String name = sound.get("name").getAsString();
            final File file = SoundSystem.instance.getSoundResource(name);
            Assert.assertTrue(file != null && file.isFile(), "no sound file for " + name);
        } finally {
            FModel.getPreferences().setPref(FPref.UI_ENABLE_SOUNDS, saved);
            GuiBase.getInterface().invokeInEdtAndWait(local::shutdown);
        }
    }
}
