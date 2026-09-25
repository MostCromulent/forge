package forge.web;

import com.google.gson.JsonObject;
import forge.deck.Deck;
import forge.model.FModel;
import forge.util.storage.IStorage;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/** The deck editor as a browser drives it: opened, changed and closed through the session's messages. */
public class DeckEditorSessionTest {
    private static final int WAIT_MILLIS = 20_000;

    private static final class Recorder implements BrowserChannel {
        final List<JsonObject> got = new CopyOnWriteArrayList<>();

        @Override
        public void send(final JsonObject message) {
            got.add(message);
        }

        JsonObject awaitMatching(final String type, final Predicate<JsonObject> wanted) throws InterruptedException {
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

        JsonObject awaitLobby(final Predicate<JsonObject> wanted) throws InterruptedException {
            for (int i = 0; i < WAIT_MILLIS / 20; i++) {
                JsonObject latest = null;
                for (final JsonObject m : got) {
                    if ("lobby".equals(m.get("t").getAsString()) && m.has("table")) {
                        latest = m.getAsJsonObject("table");
                    }
                }
                if (latest != null && wanted.test(latest)) {
                    return latest;
                }
                Thread.sleep(20);
            }
            return null;
        }
    }

    private WebSessions sessions;
    private final List<Recorder> browsers = new CopyOnWriteArrayList<>();

    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
        sessions = new WebSessions(new WebGuiBase(), 120_000, () -> { });
    }

    @AfterMethod(alwaysRun = true)
    public void disconnectBrowsers() {
        for (final Recorder browser : browsers) {
            sessions.disconnected(browser);
        }
        browsers.clear();
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
            } else {
                m.addProperty((String) fields[i], (String) value);
            }
        }
        return m;
    }

    private Recorder host(final boolean invite) throws InterruptedException {
        final Recorder host = connect("host");
        sessions.onMessage(host, JsonCodec.message("claimHost"));
        Assert.assertNotNull(host.awaitMatching("hello", h -> h.get("host").getAsBoolean()));
        host.got.clear();
        sessions.onMessage(host, message("setName", "name", "Host"));
        sessions.onMessage(host, JsonCodec.message(invite ? "invite" : "lobby"));
        Assert.assertNotNull(host.awaitLobby(l -> l.get("mySeat").getAsInt() >= 0), "the host never sat down");
        return host;
    }

    // Fails if a guest's edit reaches the host's deck folders instead of the guest's browser
    @Test(timeOut = 120_000)
    public void guestEditorNeverWritesStorage() throws Exception {
        host(true);
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
        final Recorder host = host(false);
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
        final Recorder host = host(false);
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
