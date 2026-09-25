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

    // Fails if the copy limit ignores the sideboard or lets a fifth copy in
    @Test
    public void fifthCopyRefused() {
        final DeckEditor e = editor(new Deck("Burn"), GameType.Constructed);
        Assert.assertNull(e.add("Lightning Bolt", DeckSection.Main, 3));
        Assert.assertNull(e.add("Lightning Bolt", DeckSection.Sideboard, 1));
        Assert.assertNotNull(e.add("Lightning Bolt", DeckSection.Main, 1));
    }

    // Fails if making a new commander loses the old one instead of returning it to the main deck
    @Test
    public void replacedCommanderReturnsToMain() {
        final DeckEditor e = editor(new Deck("Golgari"), GameType.Commander);
        Assert.assertNull(e.makeCommander("Meren of Clan Nel Toth", null));
        Assert.assertNull(e.makeCommander("Savra, Queen of the Golgari", null));
        Assert.assertEquals(e.deck().getCommanders().get(0).getName(), "Savra, Queen of the Golgari");
        Assert.assertEquals(e.deck().getMain().countByName("Meren of Clan Nel Toth"), 1);
    }

    // Fails if a card that can't lead the deck is accepted as its commander
    @Test
    public void nonLegendaryCommanderRefused() {
        final DeckEditor e = editor(new Deck("Golgari"), GameType.Commander);
        Assert.assertNotNull(e.makeCommander("Llanowar Elves", null));
        Assert.assertTrue(e.deck().getCommanders().isEmpty());
    }

    // Fails if undo does not also restore the saved file
    @Test
    public void undoRestoresAndSaves() {
        final DeckEditor e = editor(new Deck("Burn"), GameType.Constructed);
        e.add("Lightning Bolt", DeckSection.Main, 4);
        e.add("Shock", DeckSection.Main, 2);
        Assert.assertNull(e.undo());
        Assert.assertEquals(storages.of(GameType.Constructed).get("Burn").getMain().countByName("Shock"), 0);
        Assert.assertEquals(e.deck().getMain().countByName("Lightning Bolt"), 4);
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

    // Fails if deleting a deck that is only being read reports success, which closes the editor as if it were gone
    @Test
    public void readOnlyDeckIsNotDeleted() {
        final Deck precon = new Deck("Precon");
        precon.getMain().add(card("Forest"), 10);
        final DeckEditor e = new DeckEditor(precon, true, false, new DeckEditor.Stored(storages.of(GameType.Constructed)),
                Check.of(GameType.Constructed, null), storages, false, (id, text, f) -> { });
        Assert.assertNotNull(e.delete());
    }

    // Fails if a new deck's automatic name overwrites a deck the player already has
    @Test
    public void newDeckTakesFreeName() {
        final Deck existing = new Deck("Meren of Clan Nel Toth deck");
        existing.getMain().add(card("Swamp"), 30);
        storages.of(GameType.Commander).add(existing);
        final DeckEditor e = editor(new Deck(DeckEditor.NEW_DECK), GameType.Commander);
        Assert.assertNull(e.makeCommander("Meren of Clan Nel Toth", null));
        Assert.assertEquals(e.deck().getName(), "Meren of Clan Nel Toth deck (2)");
        Assert.assertEquals(storages.of(GameType.Commander).get("Meren of Clan Nel Toth deck").getMain().countByName("Swamp"), 30);
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

    // Fails if undoing an automatic rename puts the deck back under a name another deck has taken since
    @Test
    public void undoDoesNotRenameOntoAnotherDeck() {
        final Deck existing = new Deck(DeckEditor.NEW_DECK);
        existing.getMain().add(card("Swamp"), 30);
        storages.of(GameType.Commander).add(existing);
        final DeckEditor e = editor(new Deck(DeckEditor.NEW_DECK), GameType.Commander);
        Assert.assertNull(e.makeCommander("Meren of Clan Nel Toth", null));
        Assert.assertNull(e.undo());
        Assert.assertEquals(storages.of(GameType.Commander).get(DeckEditor.NEW_DECK).getMain().countByName("Swamp"), 30);
        Assert.assertNotEquals(e.deck().getName(), DeckEditor.NEW_DECK);
    }

    // Fails if a deck that was never changed counts as saved, so Done would put an empty or borrowed deck on the seat
    @Test
    public void savedOnlyAfterAChange() {
        final DeckEditor e = editor(new Deck(DeckEditor.NEW_DECK), GameType.Constructed);
        Assert.assertFalse(e.saved());
        Assert.assertNull(e.add("Lightning Bolt", DeckSection.Main, 1));
        Assert.assertTrue(e.saved());
    }

    // Fails if a rename that only changes case deletes the file it just wrote (Windows file names ignore case)
    @Test
    public void caseOnlyRenameKeepsFile() {
        final DeckEditor e = editor(new Deck("burn"), GameType.Constructed);
        e.add("Lightning Bolt", DeckSection.Main, 4);
        Assert.assertNull(e.rename("Burn"));
        Assert.assertEquals(storages.of(GameType.Constructed).get("Burn").getMain().countByName("Lightning Bolt"), 4);
        Assert.assertEquals(new File(dir, "Constructed").listFiles().length, 1);
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

    // Fails if switching Constructed to Commander leaves the deck where the Commander finder can't see it
    @Test
    public void checkChangeMovesStorage() {
        final DeckEditor e = editor(new Deck("Golgari"), GameType.Constructed);
        e.add("Llanowar Elves", DeckSection.Main, 1);
        Assert.assertNull(e.setCheck(Check.of(GameType.Commander, null)));
        Assert.assertTrue(storages.of(GameType.Commander).contains("Golgari"));
        Assert.assertFalse(storages.of(GameType.Constructed).contains("Golgari"));
    }

    // Fails if a printing change can alter how many copies the deck holds
    @Test
    public void printingsKeepTheTotal() {
        final DeckEditor e = editor(new Deck("Burn"), GameType.Constructed);
        e.add("Lightning Bolt", DeckSection.Main, 4);
        final String m11 = StaticData.instance().getCommonCards().getCard("Lightning Bolt", "M11", 1).getImageKey(false);
        Assert.assertNotNull(e.setPrintings("Lightning Bolt", DeckSection.Main, Map.of(m11, 3)));
        Assert.assertNull(e.setPrintings("Lightning Bolt", DeckSection.Main, Map.of(m11, 4)));
        Assert.assertEquals(e.deck().getMain().count(StaticData.instance().getCommonCards().getCard("Lightning Bolt", "M11", 1)), 4);
        Assert.assertEquals(e.state(false).main().get(0).cards().get(0).printings(), 1);
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

    // Fails if basic lands outside the commander's colours can be added from the land row
    @Test
    public void landRowFollowsIdentity() {
        final DeckEditor e = editor(new Deck("Golgari"), GameType.Commander);
        e.makeCommander("Meren of Clan Nel Toth", null);
        Assert.assertNull(e.setLands(Map.of("Swamp", 11, "Forest", 9)));
        Assert.assertEquals(e.deck().getMain().countByName("Swamp"), 11);
        Assert.assertNotNull(e.setLands(Map.of("Plains", 1)));
        Assert.assertFalse(e.state(false).lands().stream().filter(l -> l.name().equals("Plains")).findFirst().orElseThrow().allowed());
    }
}
