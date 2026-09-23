package forge.web;

import com.google.gson.JsonObject;
import forge.deck.Deck;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.net.server.RemoteClientGuiGame;
import forge.gui.GuiBase;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** A whole game on the web client's real path: loopback host, netplay client, dispatch executor, JSON forwarding. */
public class LoopbackGameTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    private static void onUi(final Runnable r) {
        GuiBase.getInterface().invokeInEdtAndWait(r);
    }

    private static void awaitGameStarted(final LocalGame local, final FakeBrowser browser) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            final HostedMatch match = local.hostedMatch();
            if (match != null && match.getGame() != null && browser.all("state").size() > 1) {
                return;
            }
            Thread.sleep(100);
        }
        Assert.fail("game did not start");
    }

    static void assertSameIgnoringZone(final Map<Integer, JsonObject> incremental, final Map<Integer, JsonObject> fresh) {
        // The client rewrites CardView.Zone from zone collections, and that rewrite is not in the host's packets
        incremental.values().forEach(o -> o.remove("Zone"));
        fresh.values().forEach(o -> o.remove("Zone"));
        final Set<Integer> onlyIncremental = new HashSet<>(incremental.keySet());
        onlyIncremental.removeAll(fresh.keySet());
        final Set<Integer> onlyFresh = new HashSet<>(fresh.keySet());
        onlyFresh.removeAll(incremental.keySet());
        Assert.assertTrue(onlyIncremental.isEmpty() && onlyFresh.isEmpty(),
                "keys only in incremental " + onlyIncremental + ", only in fresh " + onlyFresh);
        for (final Map.Entry<Integer, JsonObject> e : fresh.entrySet()) {
            Assert.assertEquals(incremental.get(e.getKey()), e.getValue(), "object " + e.getKey());
        }
    }

    @Test(timeOut = 360000)
    public void passiveGameCompletesAndIncrementalMatchesFull() throws Exception {
        WebTestSupport.skipUnlessStress();
        final LocalGame local = new LocalGame();
        final Deck bears = TestDecks.of("Bears", "Grizzly Bears", 20, "Forest", 20);
        final Deck islands = TestDecks.of("Islands", "Island", 40);
        try {
            final WebGuiGame gui = new WebGuiGame();
            final FakeBrowser browser = new FakeBrowser(gui, true);
            gui.attach(browser);
            onUi(() -> local.startMatch("Web Player", islands, "AI", bears, gui));
            awaitGameStarted(local, browser);

            // The host UI thread blocks on a client reply; the client must answer without that thread
            final RemoteClientGuiGame remote = WebTestSupport.remoteGui(local.hostedMatch());
            final CompletableFuture<Boolean> probe = new CompletableFuture<>();
            GuiBase.getInterface().invokeInEdtLater(() -> probe.complete(remote.showConfirmDialog("probe", "probe", "Yes", "No", true)));
            Assert.assertTrue(probe.get(20, TimeUnit.SECONDS));

            Assert.assertTrue(browser.gameOver.await(300, TimeUnit.SECONDS), "game did not finish");
            final int root = browser.last("state").get("root").getAsInt();
            final int turns = browser.model.objectsCopy().get(root).get("Turn").getAsInt();
            System.out.println("Loopback game: " + turns + " turns, " + browser.all("state").size() + " state messages");
            Assert.assertTrue(turns > 2, "the game ended on turn " + turns);
            final BrowserModel fresh = CompletableFuture.supplyAsync(gui::freshSnapshot, gui.dispatchExecutor()).get(10, TimeUnit.SECONDS);
            assertSameIgnoringZone(browser.model.objectsCopy(), fresh.objectsCopy());
            Assert.assertEquals(gui.skippedProperties(), 0, "properties with no JSON form");
            // The client builds its log from forwarded game events
            final long turnEntries = browser.all("log").stream()
                    .flatMap(m -> m.getAsJsonArray("entries").asList().stream())
                    .filter(e -> "TURN".equals(e.getAsJsonObject().get("type").getAsString())).count();
            Assert.assertTrue(turnEntries >= turns - 1, turnEntries + " turn log entries for " + turns + " turns");
            // Land plays name their card, so the log can show it
            Assert.assertTrue(browser.all("log").stream().flatMap(m -> m.getAsJsonArray("entries").asList().stream())
                    .anyMatch(e -> "LAND".equals(e.getAsJsonObject().get("type").getAsString()) && e.getAsJsonObject().has("imageKey")),
                    "no land entry carried its card");

            // A second match reuses the running loopback host
            final WebGuiGame second = new WebGuiGame();
            final FakeBrowser secondBrowser = new FakeBrowser(second, true);
            second.attach(secondBrowser);
            onUi(() -> local.startMatch("Web Player", islands, "AI", bears, second));
            awaitGameStarted(local, secondBrowser);
        } finally {
            onUi(local::shutdown);
        }
    }

    /**
     * Fails if a game left unfinished when its table closes keeps its thread. The thread waits on players who have
     * gone, so a server that hosts one table after another would hold every game it ever started.
     */
    @Test(timeOut = 120000)
    public void aGameLeftUnfinishedEndsWithItsTable() throws Exception {
        final LocalGame local = new LocalGame();
        final Deck bears = TestDecks.of("Bears", "Grizzly Bears", 20, "Forest", 20);
        final Deck islands = TestDecks.of("Islands", "Island", 40);
        final WebGuiGame gui = new WebGuiGame();
        try {
            final FakeBrowser browser = new FakeBrowser(gui, false);
            gui.attach(browser);
            onUi(() -> local.startMatch("Web Player", islands, "AI", bears, gui));
            // Nobody answers, so the game waits on the web player from its first question
            awaitGameStarted(local, browser);
            Assert.assertTrue(threadsInAGame() > 0, "the game never started a thread of its own");

            onUi(local::endMatch);
            for (int i = 0; i < 100 && threadsInAGame() > 0; i++) {
                Thread.sleep(100);
            }
            Assert.assertEquals(threadsInAGame(), 0, "the game's thread outlived its table:" + gameStacks());
        } finally {
            gui.close();
            onUi(local::shutdown);
        }
    }

    private static String gameStacks() {
        final StringBuilder out = new StringBuilder();
        Thread.getAllStackTraces().forEach((t, stack) -> {
            if (java.util.Arrays.stream(stack).anyMatch(f -> "forge.game.Match".equals(f.getClassName()))) {
                out.append("\n").append(t.getName());
                for (int i = 0; i < Math.min(stack.length, 16); i++) {
                    out.append("\n    ").append(stack[i]);
                }
            }
        });
        return out.toString();
    }

    /** Threads running a game, which a game's pool thread is not while it waits for the next one. */
    private static long threadsInAGame() {
        return Thread.getAllStackTraces().values().stream()
                .filter(stack -> java.util.Arrays.stream(stack).anyMatch(f -> "forge.game.Match".equals(f.getClassName())))
                .count();
    }
}
