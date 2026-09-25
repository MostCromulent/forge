package forge.web;

import forge.game.GameType;
import forge.gui.GuiBase;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;

/** What a seat brings beyond its main deck, and seats that need no deck at all. No match is played. */
public class SeatExtrasTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    static void onUi(final Runnable r) {
        GuiBase.getInterface().invokeInEdtAndWait(r);
    }

    interface TableTest {
        void run(LocalGame local, Lobby lobby) throws Exception;
    }

    /** A hosted table: the browser's seat and one computer seat, neither holding a deck. */
    static void atTable(final TableTest body) throws Exception {
        final LocalGame local = new LocalGame();
        final WebGuiGame gui = new WebGuiGame();
        try {
            onUi(() -> local.openHost("Host", gui, () -> { }, (from, text) -> { }));
            body.run(local, new Lobby(local));
        } finally {
            gui.close();
            onUi(local::shutdown);
        }
    }

    /** Waits for the lobby to report no problems, and returns the last list it gave. */
    static List<String> awaitNoProblems(final Lobby lobby) throws InterruptedException {
        List<String> problems = lobby.problems();
        for (int i = 0; i < 100 && !problems.isEmpty(); i++) {
            Thread.sleep(100);
            problems = lobby.problems();
        }
        return problems;
    }

    /** Fails if Momir Basic still demands a deck, or a seat's readiness waits on a deck nobody can choose. */
    @Test(timeOut = 60_000)
    public void aMomirTableIsReadyWithoutDecks() throws Exception {
        atTable((local, lobby) -> {
            onUi(() -> lobby.setFormat(GameType.MomirBasic.name()));
            onUi(lobby::decks);
            Assert.assertEquals(awaitNoProblems(lobby), List.of(), "a Momir table could not start");
        });
    }
}
