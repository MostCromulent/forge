package forge.web;

import forge.game.GameType;
import forge.gui.GuiBase;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;

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
            final List<String> sources = catalog.refresh(format, null).stream().map(ToBrowser.DeckSummary::source).toList();
            Assert.assertFalse(sources.contains("precons") || sources.contains("quest") || sources.contains("generated"),
                    format + " offered decks from another format's lists: " + sources.stream().distinct().toList());
        }
        Assert.assertTrue(catalog.refresh(GameType.Commander, null).stream().anyMatch(d -> "precons".equals(d.source())),
                "Commander lost its precons");
    }

    /** Fails if a format the lobby should offer cannot be chosen, so the host's choice is silently ignored. */
    @Test(timeOut = 60_000)
    public void aHostCanChooseEachFormat() throws Exception {
        final LocalGame local = new LocalGame();
        final WebGuiGame gui = new WebGuiGame();
        try {
            onUi(() -> local.openHost("Host", gui, () -> { }, (from, text) -> { }));
            final Lobby lobby = new Lobby(local);
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
