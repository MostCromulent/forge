package forge.web;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.deck.Deck;
import forge.gui.GuiBase;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Records every state message of a real game, and the table they build, as a trace both copies of the model rules
 * are tested against: {@link SharedTraceTest} for BrowserModel, src/test/ts/model.test.ts for model.ts. It plays a
 * game, so it runs only when asked: -Dforge.web.writeTraces=true.
 */
public class TraceRecordingTest {
    static final String TRACE = "traces/whole-game.json";

    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    @Test(timeOut = 360000)
    public void recordAWholeGame() throws Exception {
        if (!Boolean.getBoolean("forge.web.writeTraces")) {
            throw new SkipException("Records a trace only with -Dforge.web.writeTraces=true");
        }
        final LocalGame local = new LocalGame();
        final Deck bears = TestDecks.of("Bears", "Grizzly Bears", 20, "Forest", 20);
        final Deck islands = TestDecks.of("Islands", "Island", 40);
        try {
            final WebGuiGame gui = new WebGuiGame();
            final FakeBrowser browser = new FakeBrowser(gui, true);
            gui.attach(browser);
            GuiBase.getInterface().invokeInEdtAndWait(() -> local.startMatch("Web Player", islands, "AI", bears, gui));
            Assert.assertTrue(browser.gameOver.await(300, TimeUnit.SECONDS), "the game did not finish");
            final List<JsonObject> states = browser.all("state");
            final BrowserModel model = new BrowserModel();
            states.forEach(model::applyStateMessage);
            final JsonObject expected = new JsonObject();
            final JsonObject objects = new JsonObject();
            for (final Map.Entry<Integer, JsonObject> e : model.objectsCopy().entrySet()) {
                objects.add(String.valueOf(e.getKey()), e.getValue());
            }
            expected.add("objects", objects);
            final JsonObject trace = new JsonObject();
            trace.addProperty("about", "Every state message of a web game, Islands against Grizzly Bears, and the table they"
                    + " build. Recorded by TraceRecordingTest; both BrowserModel and model.ts must build that table from them.");
            final JsonArray messages = new JsonArray();
            states.forEach(messages::add);
            trace.add("messages", messages);
            trace.add("expected", expected);
            final Path file = Path.of(Files.isDirectory(Path.of("src/test/resources")) ? "src/test/resources" : "forge-gui-web/src/test/resources")
                    .resolve(TRACE);
            Files.createDirectories(file.getParent());
            // A null in a delta is a property going back to its default, so it is written like any other value
            Files.writeString(file, new GsonBuilder().serializeNulls().create().toJson(trace) + "\n", StandardCharsets.UTF_8);
        } finally {
            GuiBase.getInterface().invokeInEdtAndWait(local::shutdown);
        }
    }
}
