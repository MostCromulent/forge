package forge.web;

import com.google.gson.JsonObject;
import forge.deck.Deck;
import forge.deck.DeckGroup;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.util.storage.IStorage;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Offline sealed as a browser drives it: a pool made, kept, opened, played and removed through the session's messages. */
public class OfflineSealedTest extends SessionsTest {
    /** Core asks its questions through the installed GUI, so the sessions must share it for the browser to be asked. */
    @Override
    WebGuiBase gui() {
        return (WebGuiBase) GuiBase.getInterface();
    }

    private final List<String> made = new ArrayList<>();

    @Override
    void afterDisconnecting() {
        final IStorage<DeckGroup> sealed = FModel.getDecks().getSealed();
        for (final String name : made) {
            if (sealed.contains(name)) {
                sealed.delete(name);
            }
        }
        made.clear();
    }

    private TestBrowser host() throws InterruptedException {
        final TestBrowser host = connect("host");
        sessions.onMessage(host, JsonCodec.message("claimHost"));
        Assert.assertNotNull(host.awaitMatching("hello", h -> h.get("host").getAsBoolean()));
        sessions.onMessage(host, message("setName", "name", "Host"));
        sessions.onMessage(host, message("limitedOpen", "kind", "sealed"));
        host.awaitMatching("hello", h -> h.has("inEvent") && h.get("inEvent").getAsBoolean(), "the Limited page never opened");
        return host;
    }

    /** The editor opened on this pool; a session that kept an earlier test's editor resends that one on connecting. */
    private static JsonObject editorOn(final TestBrowser host, final String name) throws InterruptedException {
        return host.awaitMatching("editor", m -> m.has("state") && name.equals(m.getAsJsonObject("state").get("name").getAsString()));
    }

    private String name() {
        final String name = "Sealed test " + UUID.randomUUID().toString().substring(0, 8);
        made.add(name);
        return name;
    }

    private static JsonObject full(final String name, final boolean replace) {
        return message("sealedCreate", "product", "Full", "packs", 6, "name", name, "replace", replace);
    }

    // Fails if a pool is overwritten without the player confirming, as desktop asks before replacing
    @Test(timeOut = 120_000)
    public void aTakenNameNeedsConfirming() throws Exception {
        final TestBrowser host = host();
        final String name = name();
        sessions.onMessage(host, full(name, false));
        Assert.assertNotNull(editorOn(host, name));
        final Deck first = FModel.getDecks().getSealed().get(name).getHumanDeck();
        host.got.clear();
        sessions.onMessage(host, full(name, false));
        host.awaitMatching("nameTaken", m -> name.equals(m.get("name").getAsString()), "the taken name was not reported");
        Assert.assertSame(FModel.getDecks().getSealed().get(name).getHumanDeck(), first, "the pool was replaced without asking");
        sessions.onMessage(host, full(name, true));
        Assert.assertNotNull(editorOn(host, name));
        Assert.assertNotSame(FModel.getDecks().getSealed().get(name).getHumanDeck(), first, "a confirmed replace did not replace");
    }
    // Fails if a draft pool's free-for-all still stops at three opponents
    @Test(timeOut = 120_000)
    public void aDraftFreeForAllSeatsEveryOpponent() throws Exception {
        WebTestSupport.skipUnlessStress();
        final String name = "Draft test " + UUID.randomUUID().toString().substring(0, 8);
        final DeckGroup group = new DeckGroup(name);
        group.setHumanDeck(TestDecks.of(name, "Forest", 40));
        for (int i = 0; i < 7; i++) {
            group.addAiDeck(TestDecks.of("Opponent " + (i + 1), "Mountain", 40));
        }
        final IStorage<DeckGroup> drafts = FModel.getDecks().getDraft();
        drafts.add(group);
        try {
            final TestBrowser host = connect("host");
            sessions.onMessage(host, JsonCodec.message("claimHost"));
            sessions.onMessage(host, message("setName", "name", "Host"));
            sessions.onMessage(host, message("limitedOpen", "kind", "draft"));
            host.awaitMatching("hello", h -> h.has("inEvent") && h.get("inEvent").getAsBoolean(), "the Limited page never opened");
            sessions.onMessage(host, message("poolOpen", "name", name));
            // No game count is sent, so the saved games-per-match is left as other tests expect it
            sessions.onMessage(host, message("poolPlay", "name", name, "mode", "several", "opponent", 0, "count", 7, "games", 0));
            awaitTrue(() -> sessions.hostLobby() != null && sessions.hostLobby().getHostedMatch() != null
                    && sessions.hostLobby().getHostedMatch().getGame() != null, "the free-for-all never started");
            Assert.assertEquals(sessions.hostLobby().getHostedMatch().getGame().getRegisteredPlayers().size(), 8);
            awaitPriority(host);
            send(host, JsonCodec.message("leave"));
            host.awaitMatching("hello", h -> h.has("inEvent") && h.get("inEvent").getAsBoolean(), "leaving did not return to the pool");
        } finally {
            drafts.delete(name);
        }
    }
}
