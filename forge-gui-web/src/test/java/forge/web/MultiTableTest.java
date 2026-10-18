package forge.web;

import forge.deck.Deck;
import forge.gamemodes.net.server.FServerManager;
import forge.gui.GuiBase;
import forge.web.match.LocalGame;
import forge.web.match.WebGuiGame;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Plays several tables at once in one process, each on a server of its own, and checks that they stay apart. */
public class MultiTableTest {
    private static final String[] CREATURES = {"Grizzly Bears", "Hill Giant", "Gray Ogre", "Craw Wurm"};

    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    private record Table(LocalGame local, TestBrowser browser, String creature, long[] times) { }

    // Fails if a table does not finish, if the tables did not play at the same time, or if a browser sees another table's cards
    @Test(timeOut = 600_000)
    public void tablesPlayAtTheSameTimeAndStayApart() throws Exception {
        final List<Table> tables = new ArrayList<>();
        try {
            for (final String creature : CREATURES) {
                final LocalGame local = LocalGame.atNewTable();
                final WebGuiGame gui = new WebGuiGame();
                final TestBrowser browser = new TestBrowser(gui, true);
                gui.attach(browser);
                final Deck mine = TestDecks.of("Islands", "Island", 40);
                final Deck theirs = TestDecks.of(creature, creature, 20, "Mountain", 10, "Forest", 10);
                final Table table = new Table(local, browser, creature, new long[2]);
                GuiBase.getInterface().invokeInEdtAndWait(() -> local.startMatch("Web Player", mine, "AI", theirs, gui));
                table.times()[0] = System.currentTimeMillis();
                tables.add(table);
            }
            for (final Table table : tables) {
                Assert.assertTrue(table.browser().gameOver.await(480, TimeUnit.SECONDS), table.creature() + " table did not finish");
                table.times()[1] = System.currentTimeMillis();
            }
            final long lastStart = tables.stream().mapToLong(t -> t.times()[0]).max().orElseThrow();
            final long firstEnd = tables.stream().mapToLong(t -> t.times()[1]).min().orElseThrow();
            System.out.printf("[MULTI] %d tables, last started %d ms before the first finished%n", tables.size(), firstEnd - lastStart);
            Assert.assertTrue(lastStart < firstEnd, "the tables did not play at the same time");
            for (final Table table : tables) {
                final String seen = table.browser().got.toString();
                Assert.assertTrue(seen.contains(table.creature()), table.creature() + " table never showed its own creature");
                for (final String other : CREATURES) {
                    if (!other.equals(table.creature())) {
                        Assert.assertFalse(seen.contains(other), table.creature() + " table showed " + other);
                    }
                }
                System.out.printf("[MULTI] %s table: %d messages, finished after %d ms%n", table.creature(), table.browser().got.size(),
                        table.times()[1] - table.times()[0]);
            }
            Assert.assertFalse(FServerManager.getInstance().isRunning(), "a table started the default server");
        } finally {
            for (final Table table : tables) {
                GuiBase.getInterface().invokeInEdtAndWait(table.local()::shutdown);
            }
        }
    }
}
