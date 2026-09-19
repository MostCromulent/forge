package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import forge.gamemodes.net.ProtocolMethod;
import forge.gui.interfaces.IGuiGame;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class WebGuiGameTest {
    private WebGuiGame gui;
    private FakeBrowser browser;

    @BeforeClass
    public void setUpModel() {
        WebTestSupport.initModel();
    }

    @BeforeMethod
    public void setUp() {
        gui = new WebGuiGame();
        browser = new FakeBrowser(gui, false);
        gui.attach(browser);
    }

    @Test
    public void attachSendsFullStateThenPrompt() {
        Assert.assertEquals(browser.received.get(0).get("t").getAsString(), "state");
        Assert.assertTrue(browser.received.get(0).get("full").getAsBoolean());
        Assert.assertNotNull(browser.last("prompt"));
    }

    @Test
    public void promptCarriesMessageAndButtons() {
        gui.showPromptMessage(null, "Play a land", null);
        gui.updateButtons(null, "OK", "Cancel", true, false, true);
        final JsonObject prompt = browser.last("prompt");
        Assert.assertEquals(prompt.get("message").getAsString(), "Play a land");
        Assert.assertTrue(prompt.getAsJsonObject("ok").get("enabled").getAsBoolean());
        Assert.assertFalse(prompt.getAsJsonObject("cancel").get("enabled").getAsBoolean());
        Assert.assertTrue(prompt.get("focusOk").getAsBoolean());
    }

    @Test
    public void messageBecomesNotice() {
        gui.message("Opponent chose heads", "Coin flip");
        final JsonObject notice = browser.last("notice");
        Assert.assertEquals(notice.get("title").getAsString(), "Coin flip");
        Assert.assertFalse(notice.get("error").getAsBoolean());
    }

    @Test
    public void getChoicesMapsReplyIndicesBackToObjects() throws Exception {
        final CompletableFuture<List<String>> picked = CompletableFuture.supplyAsync(
                () -> gui.getChoices("Pick one", 1, 1, List.of("a", "b", "c"), null, null));
        final JsonObject request = browser.awaitLast("request", 2000);
        Assert.assertEquals(request.get("kind").getAsString(), "choices");
        Assert.assertEquals(request.getAsJsonArray("options").get(2).getAsJsonObject().get("label").getAsString(), "c");
        final JsonArray choice = new JsonArray();
        choice.add(2);
        gui.onBrowserMessage(FakeBrowser.reply(request.get("id").getAsInt(), choice));
        Assert.assertEquals(picked.get(2, TimeUnit.SECONDS), List.of("c"));
    }

    @Test
    public void concedeAnswersOpenRequestsWithDefaults() throws Exception {
        final CompletableFuture<List<String>> picked = CompletableFuture.supplyAsync(
                () -> gui.getChoices("Pick one", 1, 1, List.of("a", "b"), null, null));
        browser.awaitLast("request", 2000);
        gui.onBrowserMessage(FakeBrowser.action("concede"));
        Assert.assertEquals(picked.get(2, TimeUnit.SECONDS), List.of("a"));
    }

    @Test
    public void reloadReplaysTheOpenRequest() throws Exception {
        final CompletableFuture<Boolean> answer = CompletableFuture.supplyAsync(
                () -> gui.showConfirmDialog("Keep?", "Mulligan", "Keep", "Mulligan", true));
        final JsonObject request = browser.awaitLast("request", 2000);
        final FakeBrowser reloaded = new FakeBrowser(gui, false);
        gui.attach(reloaded);
        Assert.assertEquals(reloaded.last("request").get("id"), request.get("id"));
        gui.onBrowserMessage(FakeBrowser.reply(request.get("id").getAsInt(), new JsonPrimitive(1)));
        Assert.assertFalse(answer.get(2, TimeUnit.SECONDS));
    }

    @Test
    public void everyHostCallThatReturnsAValueIsImplementedHere() throws Exception {
        for (final ProtocolMethod pm : ProtocolMethod.values()) {
            final Method m = pm.getMethod();
            if (m == null || m.getDeclaringClass() != IGuiGame.class || m.getReturnType() == void.class) {
                continue;
            }
            final Method impl = WebGuiGame.class.getMethod(m.getName(), m.getParameterTypes());
            Assert.assertEquals(impl.getDeclaringClass(), WebGuiGame.class, pm.name() + " has no WebGuiGame override or documented default");
        }
    }
}
