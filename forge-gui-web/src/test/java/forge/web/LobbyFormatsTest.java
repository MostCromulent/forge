package forge.web;

import forge.game.GameType;
import forge.gui.GuiBase;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;

/** The formats the lobby offers beyond Constructed, and the deck lists each one draws from. No match is played. */
public class LobbyFormatsTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    private static void onUi(final Runnable r) {
        GuiBase.getInterface().invokeInEdtAndWait(r);
    }

    /** Fails if the commander family shares Commander's precons, or Constructed's quest opponents and generators. */
    @Test
    public void onlyCommanderGetsCommanderPrecons() {
        final DeckCatalog catalog = new DeckCatalog();
        for (final GameType format : List.of(GameType.Oathbreaker, GameType.Brawl, GameType.TinyLeaders)) {
            final List<String> sources = catalog.refresh(format, null, false, Map.of()).stream().map(ToBrowser.DeckSummary::source).toList();
            Assert.assertFalse(sources.contains("precons") || sources.contains("quest") || sources.contains("generated"),
                    format + " offered decks from another format's lists: " + sources.stream().distinct().toList());
        }
        Assert.assertTrue(catalog.refresh(GameType.Commander, null, false, Map.of()).stream().anyMatch(d -> "precons".equals(d.source())),
                "Commander lost its precons");
    }

    /** Fails if a deck size leaves out the command zone, which would show Commander as 99 and Oathbreaker as 58. */
    @Test
    public void deckFactsCountTheCommandZone() {
        Assert.assertEquals(Lobby.explained(GameType.Constructed).facts().get(0), "60+ cards");
        Assert.assertEquals(Lobby.explained(GameType.Commander).facts().get(0), "100 cards, one of each");
        Assert.assertEquals(Lobby.explained(GameType.Oathbreaker).facts().get(0), "60 cards, one of each");
        Assert.assertEquals(Lobby.explained(GameType.Brawl).facts().get(0), "60 cards, one of each");
        Assert.assertEquals(Lobby.explained(GameType.TinyLeaders).facts().get(0), "50 cards, one of each");
        Assert.assertEquals(Lobby.explained(GameType.MomirBasic).facts().get(0), "No deck to build: 60 basic lands");
    }

    /** Fails if a format reaches the browser unexplained, or explained by a raw localisation key. */
    @Test
    public void everyFormatIsExplained() {
        for (final GameType format : List.of(GameType.Constructed, GameType.Commander, GameType.Oathbreaker,
                GameType.Brawl, GameType.TinyLeaders, GameType.MomirBasic, GameType.MoJhoSto)) {
            final ToBrowser.Format f = Lobby.explained(format);
            for (final String text : List.of(f.desc(), f.play())) {
                Assert.assertFalse(text.isBlank() || text.startsWith("lbl"), format + " is explained as \"" + text + "\"");
            }
            Assert.assertTrue(f.facts().size() >= 2, format + " has too few facts: " + f.facts());
        }
        Assert.assertTrue(Lobby.explained(GameType.Brawl).facts().stream().anyMatch(s -> s.contains("25") && s.contains("30")),
                "Brawl's life fact does not give both totals");
    }

    /** Fails if a format the lobby should offer cannot be chosen, so the host's choice is silently ignored. */
    @Test(timeOut = 60_000)
    public void aHostCanChooseEachFormat() throws Exception {
        final LocalGame local = new LocalGame();
        final WebGuiGame gui = new WebGuiGame();
        try {
            onUi(() -> local.openHost("Host", gui, () -> { }, (from, text) -> { }));
            final Lobby lobby = new Lobby(local, () -> false, Map.of());
            for (final GameType format : List.of(GameType.Oathbreaker, GameType.Brawl, GameType.TinyLeaders,
                    GameType.Commander, GameType.Constructed)) {
                onUi(() -> lobby.setFormat(format.name()));
                Assert.assertEquals(lobby.format(), format);
            }
        } finally {
            gui.close();
            onUi(local::shutdown);
        }
    }
}
