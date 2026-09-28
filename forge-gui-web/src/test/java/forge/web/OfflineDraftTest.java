package forge.web;

import com.google.gson.JsonObject;
import forge.deck.DeckGroup;
import forge.deck.DeckSection;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.util.storage.IStorage;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** An offline booster draft as a browser drives it: started, picked through, reloaded, saved or thrown away. */
public class OfflineDraftTest extends SessionsTest {
    @Override
    WebGuiBase gui() {
        return (WebGuiBase) GuiBase.getInterface();
    }

    private final List<String> made = new ArrayList<>();

    @AfterMethod(alwaysRun = true)
    public void discardDrafts() {
        for (final Recorder browser : browsers) {
            sessions.onMessage(browser, JsonCodec.message("draftDiscard"));
        }
    }

    @Override
    void afterDisconnecting() {
        final IStorage<DeckGroup> drafts = FModel.getDecks().getDraft();
        for (final String name : made) {
            if (drafts.contains(name)) {
                drafts.delete(name);
            }
        }
        made.clear();
    }

    /** A host on the draft page with a Full draft started, showing its first pack. */
    private Recorder drafting() throws InterruptedException {
        final Recorder host = connect("host");
        sessions.onMessage(host, JsonCodec.message("claimHost"));
        Assert.assertNotNull(host.awaitNewest("hello", h -> h.get("host").getAsBoolean()));
        sessions.onMessage(host, message("setName", "name", "Host"));
        sessions.onMessage(host, JsonCodec.message("draftDiscard"));
        sessions.onMessage(host, message("limitedOpen", "kind", "draft"));
        Assert.assertNotNull(host.awaitNewest("hello", h -> h.has("inEvent") && h.get("inEvent").getAsBoolean()));
        host.got.clear();
        sessions.onMessage(host, message("draftStart", "product", "Full"));
        host.awaitNewest("draft", d -> d.get("pick").getAsInt() == 1, "the draft never showed its first pack");
        return host;
    }

    /** Picks the first card of the latest pack and waits for the state after it. */
    private JsonObject pick(final Recorder host) throws InterruptedException {
        final JsonObject now = host.awaitNewest("draft", d -> true);
        final int step = now.get("step").getAsInt();
        final int from = host.got.size();
        sessions.onMessage(host, message("draftPick", "step", step, "index", 0));
        final JsonObject next = host.awaitAfter(from, "draft", d -> d.get("step").getAsInt() > step, "the pick at step " + step + " never moved the draft on");
        return next;
    }

    private String name() {
        final String name = "Draft test " + UUID.randomUUID().toString().substring(0, 8);
        made.add(name);
        return name;
    }

    // Fails if the driver skips or double-counts a pick, or the saved draft lacks the picks or the computer's decks
    @Test(timeOut = 300_000)
    public void aDraftRunsToItsEnd() throws Exception {
        final Recorder host = drafting();
        JsonObject state = host.awaitNewest("draft", d -> true);
        final int seats = state.getAsJsonArray("seats").size();
        while (!state.get("done").getAsBoolean()) {
            state = pick(host);
        }
        Assert.assertEquals(state.getAsJsonArray("picks").size(), 45);
        final String name = name();
        sessions.onMessage(host, message("draftSave", "name", name, "replace", false));
        host.awaitNewest("editor", m -> m.has("state") && name.equals(m.getAsJsonObject("state").get("name").getAsString())
                && m.getAsJsonObject("state").get("limited").getAsBoolean(), "the editor never opened on the draft");
        final DeckGroup group = FModel.getDecks().getDraft().get(name);
        Assert.assertNotNull(group);
        Assert.assertEquals(group.getAiDecks().size(), seats - 1);
        // A pick goes into the main deck, as a click on a card does, so the deck opens holding every pick
        Assert.assertEquals(group.getHumanDeck().get(DeckSection.Main).countAll(), 45);
    }

    // Fails if a double click, sent twice for the same pack, drafts two cards
    @Test(timeOut = 120_000)
    public void aStalePickIsIgnored() throws Exception {
        final Recorder host = drafting();
        final JsonObject first = host.awaitNewest("draft", d -> true);
        final JsonObject click = message("draftPick", "step", first.get("step").getAsInt(), "index", 0);
        sessions.onMessage(host, click);
        sessions.onMessage(host, click);
        final JsonObject after = host.awaitNewest("draft", d -> d.get("pick").getAsInt() == 2);
        Assert.assertNotNull(after);
        Thread.sleep(1_000);
        final JsonObject latest = host.awaitNewest("draft", d -> true);
        Assert.assertEquals(latest.get("pick").getAsInt(), 2, "the second click drafted another card");
        Assert.assertEquals(latest.getAsJsonArray("picks").size(), 1);
    }

    // Fails if a pick made on an earlier state is taken from the pack shown now, which chaos drafts' mixed pack sizes
    // can number alike
    @Test(timeOut = 120_000)
    public void aPickOnAnEarlierStateIsIgnored() throws Exception {
        final Recorder host = drafting();
        final int first = host.awaitNewest("draft", d -> true).get("step").getAsInt();
        pick(host);
        final JsonObject now = host.awaitNewest("draft", d -> true);
        final int from = host.got.size();
        sessions.onMessage(host, message("draftPick", "step", first, "index", 0));
        Thread.sleep(1_000);
        Assert.assertTrue(host.got.subList(from, host.got.size()).stream().noneMatch(m -> "draft".equals(m.get("t").getAsString())),
                "a pick on an earlier state was taken");
        Assert.assertEquals(host.awaitNewest("draft", d -> true).getAsJsonArray("picks").size(), now.getAsJsonArray("picks").size());
    }

    // Fails if a reload in the middle of a draft loses it, or lands on the menu
    @Test(timeOut = 120_000)
    public void aReloadReturnsToTheDraft() throws Exception {
        final Recorder host = drafting();
        pick(host);
        pick(host);
        final JsonObject before = pick(host);
        sessions.disconnected(host);
        final Recorder again = connect("host");
        final JsonObject hello = again.awaitNewest("hello", h -> h.has("drafting"));
        Assert.assertTrue(hello.get("drafting").getAsBoolean());
        final JsonObject replayed = again.awaitNewest("draft", d -> true, "the draft was not sent again");
        Assert.assertEquals(replayed.get("pick").getAsInt(), before.get("pick").getAsInt());
        Assert.assertEquals(replayed.getAsJsonArray("picks").size(), 3);
    }

    // Fails if a finished draft overwrites a saved one without asking, or Discard still saves
    @Test(timeOut = 300_000)
    public void aTakenDraftNameNeedsConfirming() throws Exception {
        final Recorder host = drafting();
        JsonObject state = host.awaitNewest("draft", d -> true);
        while (!state.get("done").getAsBoolean()) {
            state = pick(host);
        }
        final String name = name();
        final DeckGroup existing = new DeckGroup(name);
        existing.setHumanDeck(new forge.deck.Deck(name));
        FModel.getDecks().getDraft().add(existing);
        sessions.onMessage(host, message("draftSave", "name", name, "replace", false));
        host.awaitNewest("nameTaken", m -> name.equals(m.get("name").getAsString()), "the taken name was not reported");
        Assert.assertSame(FModel.getDecks().getDraft().get(name), existing);
        sessions.onMessage(host, message("draftSave", "name", name, "replace", true));
        Assert.assertNotNull(host.awaitNewest("editor", m -> m.has("state") && name.equals(m.getAsJsonObject("state").get("name").getAsString())));
        Assert.assertEquals(FModel.getDecks().getDraft().get(name).getHumanDeck().get(DeckSection.Main).countAll(), 45);
    }

    // Fails if a second draft can start over the first, whose state then changes under the player
    @Test(timeOut = 120_000)
    public void oneDraftAtATime() throws Exception {
        final Recorder host = drafting();
        final JsonObject first = host.awaitNewest("draft", d -> true);
        host.got.clear();
        sessions.onMessage(host, message("draftStart", "product", "Full"));
        host.awaitNewest("error", m -> true, "a second draft was not refused");
        Thread.sleep(1_000);
        Assert.assertTrue(host.got.stream().noneMatch(m -> "draft".equals(m.get("t").getAsString())
                && m.get("pick").getAsInt() != first.get("pick").getAsInt()), "the running draft changed");
    }
}
