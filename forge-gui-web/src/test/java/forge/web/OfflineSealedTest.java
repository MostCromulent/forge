package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.deck.Deck;
import forge.deck.DeckGroup;
import forge.gamemodes.limited.SealedCardPoolGenerator;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.util.storage.IStorage;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/** Offline sealed as a browser drives it: a pool made, kept, opened, played and removed through the session's messages. */
public class OfflineSealedTest {
    private static final int WAIT_MILLIS = 60_000;

    private static final class Recorder implements BrowserChannel {
        final List<JsonObject> got = new CopyOnWriteArrayList<>();

        @Override
        public void send(final JsonObject message) {
            got.add(message);
        }

        JsonObject await(final String type, final Predicate<JsonObject> wanted) throws InterruptedException {
            for (int i = 0; i < WAIT_MILLIS / 20; i++) {
                for (final JsonObject m : got) {
                    if (type.equals(m.get("t").getAsString()) && wanted.test(m)) {
                        return m;
                    }
                }
                Thread.sleep(20);
            }
            return null;
        }
    }

    private WebSessions sessions;
    private final List<Recorder> browsers = new CopyOnWriteArrayList<>();
    private final List<String> made = new ArrayList<>();

    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
        // Core asks its questions through the installed GUI, so the sessions must share it for the browser to be asked
        sessions = new WebSessions((WebGuiBase) GuiBase.getInterface(), 120_000, () -> { });
    }

    @AfterMethod(alwaysRun = true)
    public void cleanUp() {
        for (final Recorder browser : browsers) {
            sessions.disconnected(browser);
        }
        browsers.clear();
        final IStorage<DeckGroup> sealed = FModel.getDecks().getSealed();
        for (final String name : made) {
            if (sealed.contains(name)) {
                sealed.delete(name);
            }
        }
        made.clear();
    }

    @AfterClass
    public void tearDown() {
        sessions.shutdown();
    }

    private Recorder connect(final String id) {
        final Recorder browser = new Recorder();
        browsers.add(browser);
        sessions.connected(browser, id, "host".equals(id));
        return browser;
    }

    private static JsonObject message(final String type, final Object... fields) {
        final JsonObject m = JsonCodec.message(type);
        for (int i = 0; i < fields.length; i += 2) {
            final Object value = fields[i + 1];
            if (value instanceof Number n) {
                m.addProperty((String) fields[i], n);
            } else if (value instanceof Boolean b) {
                m.addProperty((String) fields[i], b);
            } else if (value != null) {
                m.addProperty((String) fields[i], (String) value);
            }
        }
        return m;
    }

    private Recorder host() throws InterruptedException {
        final Recorder host = connect("host");
        sessions.onMessage(host, JsonCodec.message("claimHost"));
        Assert.assertNotNull(host.await("hello", h -> h.get("host").getAsBoolean()));
        sessions.onMessage(host, message("setName", "name", "Host"));
        sessions.onMessage(host, message("limitedOpen", "kind", "sealed"));
        Assert.assertNotNull(host.await("hello", h -> h.has("inEvent") && h.get("inEvent").getAsBoolean()), "the Limited page never opened");
        return host;
    }

    /** The editor opened on this pool; a session that kept an earlier test's editor resends that one on connecting. */
    private static JsonObject editorOn(final Recorder host, final String name) throws InterruptedException {
        return host.await("editor", m -> m.has("state") && name.equals(m.getAsJsonObject("state").get("name").getAsString()));
    }

    private String name() {
        final String name = "Sealed test " + UUID.randomUUID().toString().substring(0, 8);
        made.add(name);
        return name;
    }

    private static JsonObject full(final String name, final boolean replace) {
        return message("sealedCreate", "product", "Full", "packs", 6, "name", name, "replace", replace);
    }

    // Fails if the pool is not stored with its opponents, or the editor opens on it unrestricted
    @Test(timeOut = 120_000)
    public void creatingAPoolSavesItAndOpensTheEditor() throws Exception {
        final Recorder host = host();
        final String name = name();
        sessions.onMessage(host, full(name, false));
        final JsonObject editor = editorOn(host, name);
        Assert.assertNotNull(editor, "the editor never opened on the pool");
        Assert.assertTrue(editor.getAsJsonObject("state").get("limited").getAsBoolean(), "the pool opened unrestricted");
        final DeckGroup group = FModel.getDecks().getSealed().get(name);
        Assert.assertNotNull(group, "the pool was not stored");
        Assert.assertEquals(group.getAiDecks().size(), 7);
        Assert.assertTrue(group.getHumanDeck().get(forge.deck.DeckSection.Sideboard).countAll() > 80);
    }

    // Fails if a pool is overwritten without the player confirming, as desktop asks before replacing
    @Test(timeOut = 120_000)
    public void aTakenNameNeedsConfirming() throws Exception {
        final Recorder host = host();
        final String name = name();
        sessions.onMessage(host, full(name, false));
        Assert.assertNotNull(editorOn(host, name));
        final Deck first = FModel.getDecks().getSealed().get(name).getHumanDeck();
        host.got.clear();
        sessions.onMessage(host, full(name, false));
        Assert.assertNotNull(host.await("nameTaken", m -> name.equals(m.get("name").getAsString())), "the taken name was not reported");
        Assert.assertSame(FModel.getDecks().getSealed().get(name).getHumanDeck(), first, "the pool was replaced without asking");
        sessions.onMessage(host, full(name, true));
        Assert.assertNotNull(editorOn(host, name));
        Assert.assertNotSame(FModel.getDecks().getSealed().get(name).getHumanDeck(), first, "a confirmed replace did not replace");
    }

    // Fails if a pool whose booster is chosen by the player is generated without asking the host's browser
    @Test(timeOut = 120_000)
    public void aMetaChooseBlockAsksTheBrowser() throws Exception {
        final Recorder host = host();
        final String name = name();
        final String block = "Return to Ravnica Guild Sealed";
        final String combo = SealedCardPoolGenerator.blockCombos(FModel.getBlocks().get(block)).stream()
                .filter(c -> c.contains("Guild")).findFirst().orElseThrow();
        sessions.onMessage(host, message("sealedCreate", "product", "Block", "block", block, "combo", combo,
                "packs", 0, "name", name, "replace", false));
        final JsonObject ask = host.await("hostChoice", m -> true);
        Assert.assertNotNull(ask, "the player was never asked which booster");
        final JsonObject answer = message("hostChoice", "id", ask.get("id").getAsInt());
        final JsonArray first = new JsonArray();
        first.add(0);
        answer.add("value", first);
        sessions.onMessage(host, answer);
        Assert.assertNotNull(editorOn(host, name), "the pool was not finished after the answer");
        Assert.assertTrue(FModel.getDecks().getSealed().contains(name));
    }

    // Fails if a second click on Open the packs, sent before the first pool is made, silently replaces it
    @Test(timeOut = 120_000)
    public void aDoubleSubmitDoesNotReplaceThePool() throws Exception {
        final Recorder host = host();
        final String name = name();
        sessions.onMessage(host, full(name, false));
        sessions.onMessage(host, full(name, false));
        Assert.assertNotNull(editorOn(host, name));
        Assert.assertNotNull(host.await("error", m -> true), "the second request was not refused");
        Thread.sleep(2_000);
        final long editors = host.got.stream().filter(m -> "editor".equals(m.get("t").getAsString()) && m.has("state")
                && name.equals(m.getAsJsonObject("state").get("name").getAsString())).count();
        Assert.assertEquals(editors, 1, "the pool was made twice");
    }

    // Fails if a pool finished after a reload is sent to the tab that was closed, leaving the new one without it
    @Test(timeOut = 120_000)
    public void aReloadWhileOpeningStillDelivers() throws Exception {
        final Recorder host = host();
        final String name = name();
        final String block = "Return to Ravnica Guild Sealed";
        final String combo = SealedCardPoolGenerator.blockCombos(FModel.getBlocks().get(block)).stream()
                .filter(c -> c.contains("Guild")).findFirst().orElseThrow();
        sessions.onMessage(host, message("sealedCreate", "product", "Block", "block", block, "combo", combo,
                "packs", 0, "name", name, "replace", false));
        Assert.assertNotNull(host.await("hostChoice", m -> true));
        sessions.disconnected(host);
        final Recorder again = connect("host");
        final JsonObject ask = again.await("hostChoice", m -> true);
        Assert.assertNotNull(ask, "the question was not asked again after the reload");
        final JsonObject answer = message("hostChoice", "id", ask.get("id").getAsInt());
        final JsonArray first = new JsonArray();
        first.add(0);
        answer.add("value", first);
        sessions.onMessage(again, answer);
        Assert.assertNotNull(editorOn(again, name), "the new tab never got the pool's deck");
        Assert.assertNotNull(again.await("limitedPools", m -> m.toString().contains(name)), "the new tab's pools list is stale");
    }

    // Fails if a reload on the opponents screen falls back to the menu
    @Test(timeOut = 120_000)
    public void aReloadReturnsToThePool() throws Exception {
        final Recorder host = host();
        final String name = name();
        sessions.onMessage(host, full(name, false));
        Assert.assertNotNull(editorOn(host, name));
        sessions.onMessage(host, message("poolOpen", "name", name));
        sessions.disconnected(host);
        final Recorder again = connect("host");
        final JsonObject hello = again.await("hello", h -> h.has("inEvent"));
        Assert.assertNotNull(hello);
        Assert.assertTrue(hello.get("inEvent").getAsBoolean());
        Assert.assertEquals(hello.get("eventPool").getAsString(), name);
        Assert.assertNotNull(again.await("limitedPools", m -> m.toString().contains(name)), "the pools were not sent again");
    }

    // Fails if the pool whose deck is open in the editor can be deleted from under it
    @Test(timeOut = 120_000)
    public void anOpenPoolCannotBeDeleted() throws Exception {
        final Recorder host = host();
        final String name = name();
        sessions.onMessage(host, full(name, false));
        Assert.assertNotNull(editorOn(host, name));
        sessions.onMessage(host, message("poolDelete", "name", name));
        Assert.assertNotNull(host.await("error", m -> true), "deleting an open pool was not refused");
        Assert.assertTrue(FModel.getDecks().getSealed().contains(name));
        sessions.onMessage(host, JsonCodec.message("editorClose"));
        sessions.onMessage(host, message("poolDelete", "name", name));
        for (int i = 0; i < 100 && FModel.getDecks().getSealed().contains(name); i++) {
            Thread.sleep(20);
        }
        Assert.assertFalse(FModel.getDecks().getSealed().contains(name), "a closed pool could not be deleted");
    }

    // Fails if a pool's match is typed as anything but Sealed, or leaving it opens a Constructed lobby instead of the pool
    @Test(timeOut = 180_000)
    public void aPoolsMatchIsSealedAndReturnsToThePool() throws Exception {
        WebTestSupport.skipUnlessStress();
        final Recorder host = host();
        final String name = name();
        sessions.onMessage(host, full(name, false));
        Assert.assertNotNull(editorOn(host, name));
        sessions.onMessage(host, JsonCodec.message("editorClose"));
        sessions.onMessage(host, message("poolPlay", "name", name, "opponent", 0, "games", 1));
        Assert.assertNotNull(host.await("hello", h -> h.get("inMatch").getAsBoolean()), "the match never started");
        // The lobby path used to type every limited match as Draft
        Assert.assertNotNull(host.await("state", m -> m.toString().contains("\"GameType\":\"Sealed\"")), "the match is not typed Sealed");
        host.got.clear();
        sessions.onMessage(host, JsonCodec.message("leave"));
        final JsonObject back = host.await("hello", h -> h.has("inEvent") && h.get("inEvent").getAsBoolean());
        Assert.assertNotNull(back, "leaving the match did not return to the pool");
        Assert.assertEquals(back.get("eventPool").getAsString(), name);
        Assert.assertFalse(back.get("inLobby").getAsBoolean());
    }
}
