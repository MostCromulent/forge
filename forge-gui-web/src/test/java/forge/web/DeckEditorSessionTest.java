package forge.web;

import com.google.gson.JsonObject;
import forge.deck.Deck;
import forge.model.FModel;
import forge.util.storage.IStorage;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.UUID;

/** The deck editor as a browser drives it: opened, changed and closed through the session's messages. */
public class DeckEditorSessionTest extends SessionsTest {

    // Fails if a guest's edit reaches the host's deck folders instead of the guest's browser
    @Test(timeOut = 120_000)
    public void guestEditorNeverWritesStorage() throws Exception {
        hostAt("invite");
        final Recorder guest = connect("guest");
        sessions.onMessage(guest, message("setName", "name", "Guest"));
        Assert.assertNotNull(guest.awaitLobby(l -> l.get("mySeat").getAsInt() >= 0), "the guest never sat down");
        final IStorage<Deck> constructed = FModel.getDecks().getConstructed();
        final long before = constructed.getItemNames().stream().filter(n -> n.startsWith(DeckEditor.NEW_DECK)).count();

        sessions.onMessage(guest, message("editorOpen", "newFormat", "Constructed", "copy", false));
        Assert.assertNotNull(guest.awaitMatching("editor", m -> m.has("state")), "the guest's editor never opened");
        sessions.onMessage(guest, message("editorEdit", "op", "add", "name", "Forest", "count", 1));

        Assert.assertNotNull(guest.awaitMatching("deviceDeck", m -> m.has("text") && m.get("text").getAsString().contains("Forest")),
                "the guest's browser was never sent its deck");
        Assert.assertEquals(constructed.getItemNames().stream().filter(n -> n.startsWith(DeckEditor.NEW_DECK)).count(), before,
                "a guest's deck was written to the host's deck folder");
    }

    // Fails if Import and edit saves a deck in one format's folder while the editor saves it to another
    @Test(timeOut = 120_000)
    public void importAndEditStaysInItsFormat() throws Exception {
        final Recorder host = connect("host");
        sessions.onMessage(host, JsonCodec.message("claimHost"));
        Assert.assertNotNull(host.awaitMatching("hello", h -> h.get("host").getAsBoolean()));
        sessions.onMessage(host, message("setName", "name", "Host"));
        final String name = "Import format test " + UUID.randomUUID().toString().substring(0, 8);
        try {
            final JsonObject commit = message("importCommit", "text", "Commander\n1 Meren of Clan Nel Toth\nDeck\n1 Sol Ring",
                    "name", name, "format", "Commander", "unrestricted", false, "action", "edit");
            sessions.onMessage(host, commit);
            Assert.assertNotNull(host.awaitMatching("editor", m -> m.has("state")
                    && "Commander".equals(m.getAsJsonObject("state").get("format").getAsString())), "the editor did not open on a Commander deck");
            sessions.onMessage(host, message("editorEdit", "op", "add", "name", "Swamp", "count", 1));
            Assert.assertNotNull(host.awaitMatching("editor", m -> m.has("state") && m.toString().contains("Swamp")));
            Assert.assertTrue(FModel.getDecks().getCommander().contains(name), "the deck is not among the Commander decks");
            Assert.assertFalse(FModel.getDecks().getConstructed().contains(name), "a copy of the deck was left among the Constructed decks");
        } finally {
            for (final IStorage<Deck> storage : List.of(FModel.getDecks().getCommander(), FModel.getDecks().getConstructed())) {
                if (storage.contains(name)) {
                    storage.delete(name);
                }
            }
        }
    }

    // Fails if Import and use puts a deck of another format on the seat
    @Test(timeOut = 120_000)
    public void importAndUseKeepsToTheTablesFormat() throws Exception {
        final Recorder host = hostAt("lobby");
        final int seat = host.awaitLobby(l -> true).get("mySeat").getAsInt();
        final String name = "Import seat test " + UUID.randomUUID().toString().substring(0, 8);
        try {
            sessions.onMessage(host, message("importCommit", "text", "Commander\n1 Meren of Clan Nel Toth\nDeck\n1 Sol Ring",
                    "name", name, "format", "Commander", "unrestricted", false, "action", "use", "seat", seat));
            Assert.assertNotNull(host.awaitMatching("notice", m -> m.toString().contains("Commander")), "no notice said why");
            final JsonObject table = host.awaitLobby(l -> true);
            final JsonObject mine = table.getAsJsonArray("seats").get(seat).getAsJsonObject();
            Assert.assertFalse(mine.has("deckName") && name.equals(mine.get("deckName").getAsString()),
                    "a Commander deck was put on a Constructed seat");
        } finally {
            if (FModel.getDecks().getCommander().contains(name)) {
                FModel.getDecks().getCommander().delete(name);
            }
        }
    }

    // Fails if Done puts the deck cached before the edits on the seat, rather than the edited one
    @Test(timeOut = 120_000)
    public void doneSeatsEditedDeck() throws Exception {
        final Recorder host = hostAt("lobby");
        final int seat = host.awaitLobby(l -> true).get("mySeat").getAsInt();
        final String name = "Editor session test " + UUID.randomUUID().toString().substring(0, 8);
        try {
            sessions.onMessage(host, message("editorOpen", "newFormat", "Constructed", "seat", seat, "copy", false));
            Assert.assertNotNull(host.awaitMatching("editor", m -> m.has("state")), "the editor never opened");
            sessions.onMessage(host, message("editorEdit", "op", "add", "name", "Forest", "count", 60));
            sessions.onMessage(host, message("editorRename", "name", name));
            Assert.assertNotNull(host.awaitMatching("editor", m -> m.has("state")
                    && name.equals(m.getAsJsonObject("state").get("name").getAsString())), "the rename never landed");
            sessions.onMessage(host, JsonCodec.message("editorClose"));

            final JsonObject table = host.awaitLobby(l -> {
                final JsonObject s = l.getAsJsonArray("seats").get(seat).getAsJsonObject();
                return s.has("deckName") && name.equals(s.get("deckName").getAsString());
            });
            Assert.assertNotNull(table, "the edited deck never reached the seat");
            Assert.assertEquals(table.getAsJsonArray("seats").get(seat).getAsJsonObject().get("deckSize").getAsInt(), 60);
        } finally {
            if (FModel.getDecks().getConstructed().contains(name)) {
                FModel.getDecks().getConstructed().delete(name);
            }
        }
    }
}
