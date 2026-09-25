package forge.web;

import forge.StaticData;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckGroup;
import forge.deck.DeckSection;
import forge.deck.io.DeckGroupSerializer;
import forge.deck.io.DeckStorage;
import forge.game.GameType;
import forge.item.PaperCard;
import forge.util.storage.IStorage;
import forge.util.storage.StorageImmediatelySerialized;
import forge.web.ToBrowser.CataloguePage;
import forge.web.ToBrowser.CatalogueRow;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/** The deck editor on a sealed or draft pool: the pool is the catalogue, and cards move rather than appear. */
public class LimitedEditorTest {
    private File dir;
    private IStorage<DeckGroup> groups;
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
        dir = Files.createTempDirectory("forge-limited").toFile();
        made.clear();
        final File sealed = new File(dir, "sealed");
        sealed.mkdirs();
        groups = new StorageImmediatelySerialized<>("Sealed", new DeckGroupSerializer(sealed, dir.getPath()));
    }

    private static PaperCard card(final String name) {
        return StaticData.instance().getCommonCards().getCard(name);
    }

    /** Two Llanowar Elves, a Giant Growth and three Forests, stored as a pool with seven opponents. */
    private DeckEditor pool(final PaperCard elves) {
        final Deck human = new Deck("Pool");
        final CardPool side = human.getOrCreate(DeckSection.Sideboard);
        side.add(elves, 2);
        side.add(card("Giant Growth"), 1);
        side.add(card("Forest"), 3);
        final DeckGroup group = new DeckGroup("Pool");
        group.setHumanDeck(human);
        for (int i = 0; i < 7; i++) {
            final Deck ai = new Deck("Opponent " + (i + 1));
            ai.getMain().add(card("Grizzly Bears"), 40);
            group.addAiDeck(ai);
        }
        groups.add(group);
        return new DeckEditor(human, false, true, new DeckEditor.Group(groups), Check.of(GameType.Sealed, null), storages,
                false, (id, text, format) -> { });
    }

    private DeckEditor pool() {
        return pool(card("Llanowar Elves"));
    }

    /** Fails if the copy limit or a printing lookup is reached in limited mode, or a card the pool lacks can be added. */
    @Test
    public void addingTakesFromThePool() {
        final DeckEditor e = pool();
        assertTrue(e.limited());
        assertNull(e.add("Llanowar Elves", DeckSection.Main, 1));
        assertNull(e.add("Llanowar Elves", DeckSection.Main, 1));
        assertEquals(e.deck().getMain().countByName("Llanowar Elves"), 2);
        assertEquals(e.deck().get(DeckSection.Sideboard).countByName("Llanowar Elves"), 0);
        assertNotNull(e.add("Llanowar Elves", DeckSection.Main, 1));
        assertNotNull(e.add("Grizzly Bears", DeckSection.Main, 1));
        assertEquals(e.deck().getMain().countByName("Grizzly Bears"), 0);
    }

    /** Fails if adding makes a copy of the preferred printing rather than moving the pool's own. */
    @Test
    public void addingMovesTheExactPrinting() {
        final PaperCard alpha = StaticData.instance().getCommonCards().getCard("Llanowar Elves", "LEA");
        final DeckEditor e = pool(alpha);
        assertNull(e.add("Llanowar Elves", DeckSection.Main, 1));
        assertEquals(e.deck().getMain().count(alpha), 1);
    }

    /** Fails if removing from the deck deletes the card instead of returning it to the pool. */
    @Test
    public void removingReturnsToThePool() {
        final DeckEditor e = pool();
        assertNull(e.add("Giant Growth", DeckSection.Main, 1));
        assertNull(e.remove("Giant Growth", DeckSection.Main, 1));
        assertEquals(e.deck().get(DeckSection.Sideboard).countByName("Giant Growth"), 1);
        assertEquals(e.deck().getMain().countByName("Giant Growth"), 0);
    }

    /** Fails if the land row ignores the pool's own basics, or deletes them when the count goes down. */
    @Test
    public void poolBasicsAreUsedFirstAndGoBack() {
        final DeckEditor e = pool();
        final PaperCard poolForest = card("Forest");
        assertNull(e.setLands(Map.of("Forest", 5)));
        assertEquals(e.deck().get(DeckSection.Sideboard).countByName("Forest"), 0);
        assertEquals(e.deck().getMain().countByName("Forest"), 5);
        assertTrue(e.deck().getMain().count(poolForest) >= 3);
        assertNull(e.setLands(Map.of("Forest", 1)));
        assertEquals(e.deck().getMain().countByName("Forest"), 1);
        assertEquals(e.deck().get(DeckSection.Sideboard).countByName("Forest"), 4);
    }

    /** Fails if the shared catalogue of every card leaks into a pool's editor. */
    @Test
    public void theCatalogueIsThePool() {
        final DeckEditor e = pool();
        final CataloguePage page = e.catalogue().query(1, new CardCatalog.Query("", "", "any", "any", "name", 0, false),
                c -> null, null, e.deck().getMain()::countByName);
        assertEquals(page.rows().stream().map(CatalogueRow::name).collect(Collectors.toSet()),
                Set.of("Llanowar Elves", "Giant Growth", "Forest"));
    }

    /** Fails if undo restores only some sections, leaving an Attraction both in the pool and in its own section. */
    @Test
    public void undoPutsAnAttractionBackOnce() {
        final DeckEditor e = pool();
        final PaperCard stand = card("Balloon Stand") != null ? card("Balloon Stand")
                : StaticData.instance().getVariantCards().getCard("Balloon Stand");
        assertNotNull(stand, "no Attraction card to test with");
        e.deck().get(DeckSection.Sideboard).add(stand, 1);
        assertNull(e.add("Balloon Stand", DeckSection.Main, 1));
        assertEquals(e.deck().get(DeckSection.Attractions).countByName("Balloon Stand"), 1);
        assertNull(e.undo());
        assertEquals(e.deck().get(DeckSection.Sideboard).countByName("Balloon Stand"), 1);
        final CardPool attractions = e.deck().get(DeckSection.Attractions);
        assertEquals(attractions == null ? 0 : attractions.countByName("Balloon Stand"), 0);
    }

    /** Fails if saving a pool's deck replaces its stored group with a bare deck, losing the opponents. */
    @Test
    public void aGroupSaveKeepsTheGroup() {
        final DeckEditor e = pool();
        assertNull(e.add("Giant Growth", DeckSection.Main, 1));
        final DeckGroup stored = groups.get("Pool");
        assertEquals(stored.getHumanDeck().getMain().countByName("Giant Growth"), 1);
        assertEquals(stored.getAiDecks().size(), 7);
        assertEquals(stored.getHumanDeck().getName(), "Pool");
    }
}
