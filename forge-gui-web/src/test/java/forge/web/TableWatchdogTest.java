package forge.web;

import com.google.common.eventbus.Subscribe;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;
import forge.gamemodes.match.HostedMatch;
import forge.gui.GuiBase;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.web.match.LocalGame;
import forge.web.match.WebGuiGame;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import javax.management.ObjectName;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Checks that the watchdog ends one table's game that would hold up the others, and that the other tables play on. */
public class TableWatchdogTest {
    private static final String TURN_CPU_SECONDS = "forge.web.turnCpuSeconds";
    private static final String HEAP_LIMIT_MB = "forge.web.heapLimitMb";

    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    /** Live objects of one class, after a full collection, from the JVM's class histogram. */
    private static long liveInstances(final String className) {
        try {
            final String histogram = (String) ManagementFactory.getPlatformMBeanServer().invoke(
                    new ObjectName("com.sun.management:type=DiagnosticCommand"), "gcClassHistogram",
                    new Object[] {null}, new String[] {String[].class.getName()});
            return histogram.lines().map(String::trim).map(l -> l.split(" +"))
                    .filter(f -> f.length >= 4 && f[3].equals(className)).mapToLong(f -> Long.parseLong(f[1])).sum();
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Table(LocalGame local, WebGuiGame gui, TestBrowser browser, String creature, Game game) { }

    private static Table open(final String creature) throws InterruptedException {
        final LocalGame local = LocalGame.atNewTable();
        final WebGuiGame gui = new WebGuiGame();
        final TestBrowser browser = new TestBrowser(gui, true);
        gui.attach(browser);
        final Deck mine = TestDecks.of("Islands", "Island", 40);
        final Deck theirs = TestDecks.of(creature, creature, 20, "Mountain", 10, "Forest", 10);
        GuiBase.getInterface().invokeInEdtAndWait(() -> local.startMatch("Web Player", mine, "AI", theirs, gui));
        Game game = null;
        for (int i = 0; i < 200 && game == null; i++) {
            final HostedMatch match = local.hostedMatch();
            game = match == null ? null : match.getGame();
            if (game == null) {
                Thread.sleep(50);
            }
        }
        Assert.assertNotNull(game, "the " + creature + " table's game did not start");
        return new Table(local, gui, browser, creature, game);
    }

    /** Runs the action on the game thread at the start of the table's second turn. */
    private static Game atSecondTurn(final Table table, final Consumer<Game> action) {
        final Game g = table.game();
        g.subscribeToEvents(new Object() {
            @Subscribe
            public void turn(final GameEventTurnBegan e) {
                if (e.turnNumber() == 2) {
                    action.accept(g);
                }
            }
        });
        return g;
    }

    private static void assertOthersPlayOn(final List<Table> others) throws InterruptedException {
        for (final Table t : others) {
            Assert.assertTrue(t.browser().gameOver.await(300, TimeUnit.SECONDS), t.creature() + " table did not finish");
            Assert.assertTrue(t.browser().got.toString().contains(t.creature()), t.creature() + " table never showed its creature");
            // A game the watchdog ended is a draw, so a winner shows that this table played to its own end
            Assert.assertFalse(t.game().getOutcome().isDraw(), t.creature() + " table was ended instead of the one that caused it");
        }
    }

    /** Closes each table as a browser's session does when it leaves the match. */
    private static void shutdown(final List<Table> tables) {
        for (final Table t : tables) {
            GuiBase.getInterface().invokeInEdtAndWait(t.local()::shutdown);
            t.gui().close();
            t.browser().close();
        }
    }

    // Fails if a game whose thread spins in one turn is not ended as a draw, or if the tables beside it do not finish
    @Test(timeOut = 600_000)
    public void aRunawayGameEndsAndTheOthersPlayOn() throws Exception {
        System.setProperty(TURN_CPU_SECONDS, "3");
        final List<Table> tables = new ArrayList<>();
        try {
            final Table runaway = open("Grizzly Bears");
            tables.add(runaway);
            final Game game = atSecondTurn(runaway, g -> {
                while (!g.isGameOver()) {
                    Thread.onSpinWait();
                }
            });
            tables.add(open("Hill Giant"));
            tables.add(open("Gray Ogre"));
            final long start = System.currentTimeMillis();
            Assert.assertTrue(runaway.browser().gameOver.await(120, TimeUnit.SECONDS), "the runaway game did not end");
            System.out.printf("[WATCH] runaway game ended after %d ms, outcome draw %s%n", System.currentTimeMillis() - start,
                    game.getOutcome() != null && game.getOutcome().isDraw());
            Assert.assertTrue(game.getOutcome().isDraw(), "an ended game counts as a draw");
            assertOthersPlayOn(tables.subList(1, tables.size()));
        } finally {
            System.clearProperty(TURN_CPU_SECONDS);
            shutdown(tables);
        }
    }

    /** Plays a table whose game thread does the action at its second turn, beside two that play normally, and checks that only it ends. */
    private static void endsAloneAsADraw(final String what, final Consumer<Game> action) throws InterruptedException {
        final List<Table> tables = new ArrayList<>();
        try {
            final Table failing = open("Grizzly Bears");
            tables.add(failing);
            final Game game = atSecondTurn(failing, action);
            tables.add(open("Hill Giant"));
            tables.add(open("Gray Ogre"));
            final long start = System.currentTimeMillis();
            Assert.assertTrue(failing.browser().gameOver.await(120, TimeUnit.SECONDS), "the " + what + " game did not end");
            System.out.printf("[WATCH] %s game ended after %d ms%n", what, System.currentTimeMillis() - start);
            Assert.assertTrue(game.getOutcome().isDraw(), "an ended game counts as a draw");
            assertOthersPlayOn(tables.subList(1, tables.size()));
        } finally {
            shutdown(tables);
        }
    }

    // Fails if a game whose thread died is not ended as a draw, or if the tables beside it do not finish with a winner
    @Test(timeOut = 600_000)
    public void aCrashedGameEndsAndTheOthersPlayOn() throws Exception {
        // An event subscriber's Error is the one failure the event bus passes on, so it unwinds the game thread
        endsAloneAsADraw("crashed", g -> {
            throw new Error("crash requested by a test");
        });
    }

    // Fails if a game whose thread is deadlocked is not ended as a draw, or if the tables beside it do not finish with a winner
    @Test(timeOut = 600_000)
    public void aDeadlockedGameEndsAndTheOthersPlayOn() throws Exception {
        final Object first = new Object();
        final Object second = new Object();
        endsAloneAsADraw("deadlocked", g -> {
            final Thread other = new Thread(() -> {
                synchronized (second) {
                    pause();
                    synchronized (first) {
                        Thread.onSpinWait();
                    }
                }
            });
            other.setDaemon(true);
            other.start();
            synchronized (first) {
                pause();
                synchronized (second) {
                    Thread.onSpinWait();
                }
            }
        });
    }

    private static void pause() {
        try {
            Thread.sleep(200);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Plays a table that makes tokens until its game is ended, then closes it. Nothing of that table outlives this method. */
    private static void fillTheHeap(final long before, final long limit) throws InterruptedException {
        final Table leaking = open("Grizzly Bears");
        try {
            final PaperCard bear = FModel.getMagicDb().getCommonCards().getCard("Grizzly Bears");
            final int[] made = new int[1];
            final Game game = atSecondTurn(leaking, g -> {
                final Player owner = g.getPlayers().get(0);
                while (!g.isGameOver()) {
                    g.getAction().moveToPlay(Card.fromPaperCard(bear, owner), null, null);
                    made[0]++;
                }
            });
            final long start = System.currentTimeMillis();
            // The server's game is checked, not the browser: the decoder refuses the final frame of a large enough board
            for (int i = 0; i < 6000 && !game.isGameOver(); i++) {
                Thread.sleep(100);
            }
            Assert.assertTrue(game.isGameOver(), "the game that fills the heap did not end");
            System.out.printf("[WATCH] heap game ended after %d ms with %d tokens; old generation %d MB before, limit %d MB%n",
                    System.currentTimeMillis() - start, made[0], before, limit);
            Assert.assertTrue(game.getOutcome().isDraw(), "an ended game counts as a draw");
            System.out.printf("[WATCH] while its players are still seated: %d cards, %d card views%n",
                    liveInstances("forge.game.card.Card"), liveInstances("forge.game.card.CardView"));
        } finally {
            shutdown(List.of(leaking));
        }
    }

    // Fails if a game that fills the heap is not ended, if the heap stays full after it, or if the tables beside it do not finish
    @Test(timeOut = 900_000)
    public void aGameThatFillsTheHeapEndsAndTheOthersPlayOn() throws Exception {
        final MemoryPoolMXBean oldGen = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(p -> p.isCollectionUsageThresholdSupported() && (p.getName().contains("Old") || p.getName().contains("Tenured")))
                .findFirst().orElseThrow();
        System.gc();
        final long before = oldGen.getUsage().getUsed() >> 20;
        final long limit = before + 500;
        System.setProperty(HEAP_LIMIT_MB, String.valueOf(limit));
        // Other tests in this process leave games behind, such as the deadlocked one that no thread can free, so both are counted from here
        final long cardsBefore = liveInstances("forge.game.card.Card");
        final long viewsBefore = liveInstances("forge.game.card.CardView");
        final List<Table> tables = new ArrayList<>();
        try {
            tables.add(open("Hill Giant"));
            fillTheHeap(before, limit);
            assertOthersPlayOn(tables);
            shutdown(tables);
            tables.clear();
            // After the end the game's thread still sends the final board to its seats, which takes a while for a large one
            final long closed = System.currentTimeMillis();
            long cards = liveInstances("forge.game.card.Card") - cardsBefore;
            long views = liveInstances("forge.game.card.CardView") - viewsBefore;
            while ((cards >= 200 || views >= 200) && System.currentTimeMillis() - closed < 120_000) {
                Thread.sleep(2000);
                cards = liveInstances("forge.game.card.Card") - cardsBefore;
                views = liveInstances("forge.game.card.CardView") - viewsBefore;
            }
            System.out.printf("[WATCH] %d ms after every table closed: %d more cards, %d more card views, old generation %d MB%n",
                    System.currentTimeMillis() - closed, cards, views, oldGen.getUsage().getUsed() >> 20);
            Assert.assertTrue(cards < 200 && views < 200, "the ended game was not freed: " + cards + " more cards, " + views + " more card views");
        } finally {
            System.clearProperty(HEAP_LIMIT_MB);
            shutdown(tables);
        }
    }
}
