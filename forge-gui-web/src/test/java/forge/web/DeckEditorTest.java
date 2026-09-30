package forge.web;

import forge.StaticData;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.deck.io.DeckStorage;
import forge.game.GameType;
import forge.item.PaperCard;
import forge.util.storage.IStorage;
import forge.util.storage.StorageImmediatelySerialized;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DeckEditorTest {
    private File dir;
    private final Map<GameType, IStorage<Deck>> made = new HashMap<>();
    private final DeckStore.Storages storages = format -> made.computeIfAbsent(DeckStore.family(format), f -> {
        final File folder = new File(dir, f.name());
        folder.mkdirs();
        return new StorageImmediatelySerialized<>(f.name(), new DeckStorage(folder, folder.getPath()));
    });

    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    @BeforeMethod
    public void freshFolders() throws IOException {
        dir = Files.createTempDirectory("forge-editor").toFile();
        made.clear();
    }

    private DeckEditor editor(final Deck deck, final GameType format) {
        return new DeckEditor(deck, false, false, new DeckEditor.Stored(storages.of(format)), Check.of(format, null), storages,
                false, (id, text, f) -> { });
    }

    private static PaperCard card(final String name) {
        return StaticData.instance().getCommonCards().getCard(name);
    }

    // Fails if editing a precon writes to the precon rather than a copy
    @Test
    public void readOnlySourceIsCopied() {
        final Deck precon = new Deck("Precon");
        precon.getMain().add(card("Forest"), 10);
        final DeckEditor e = new DeckEditor(precon, true, false, new DeckEditor.Stored(storages.of(GameType.Constructed)),
                Check.of(GameType.Constructed, null), storages, false, (id, text, f) -> { });
        Assert.assertEquals(e.copyOf(), "Precon");
        Assert.assertNull(e.add("Llanowar Elves", DeckSection.Main, 1));
        Assert.assertEquals(precon.getMain().countByName("Llanowar Elves"), 0);
        Assert.assertEquals(e.deck().getName(), "Precon (copy)");
        Assert.assertNull(e.copyOf());
        Assert.assertTrue(storages.of(GameType.Constructed).contains("Precon (copy)"));
    }

    // Fails if a new deck saves over a deck the player already has under the same name
    @Test
    public void newDeckDoesNotOverwriteAStoredNewDeck() {
        final Deck existing = new Deck(DeckEditor.NEW_DECK);
        existing.getMain().add(card("Swamp"), 30);
        storages.of(GameType.Constructed).add(existing);
        final DeckEditor e = editor(new Deck(DeckEditor.NEW_DECK), GameType.Constructed);
        Assert.assertNull(e.add("Lightning Bolt", DeckSection.Main, 1));
        Assert.assertEquals(e.deck().getName(), DeckEditor.NEW_DECK + " (2)");
        Assert.assertEquals(storages.of(GameType.Constructed).get(DeckEditor.NEW_DECK).getMain().countByName("Swamp"), 30);
    }

    // Fails if a rename onto another deck's file overwrites it
    @Test
    public void renameOntoTakenNameRefused() {
        final Deck other = new Deck("Zoo");
        other.getMain().add(card("Forest"), 1);
        storages.of(GameType.Constructed).add(other);
        final DeckEditor e = editor(new Deck("Burn"), GameType.Constructed);
        e.add("Lightning Bolt", DeckSection.Main, 1);
        Assert.assertNotNull(e.rename("zoo"));
        Assert.assertEquals(storages.of(GameType.Constructed).get("Zoo").getMain().countByName("Forest"), 1);
    }

    // Fails if a guest's change reaches a host storage rather than the guest's browser
    @Test
    public void guestReadOnlyCopyGoesToDevice() {
        final Deck host = new Deck("Host deck");
        host.getMain().add(card("Forest"), 10);
        final List<String> sent = new ArrayList<>();
        final DeckEditor e = new DeckEditor(host, true, false, new DeckEditor.Stored(storages.of(GameType.Constructed)),
                Check.of(GameType.Constructed, null), storages, true, (id, text, f) -> sent.add(text));
        Assert.assertNull(e.add("Llanowar Elves", DeckSection.Main, 1));
        Assert.assertTrue(e.target() instanceof DeckEditor.Device);
        Assert.assertEquals(sent.size(), 1);
        Assert.assertTrue(sent.get(0).contains("Llanowar Elves"));
        Assert.assertFalse(storages.of(GameType.Constructed).contains("Host deck (copy)"));
    }
}
