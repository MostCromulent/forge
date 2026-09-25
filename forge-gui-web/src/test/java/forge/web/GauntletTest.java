package forge.web;

import com.google.gson.JsonObject;
import forge.deck.Deck;
import forge.deck.DeckGroup;
import forge.game.GameType;
import forge.gamemodes.limited.GauntletMini;
import forge.gamemodes.limited.SealedCardPoolGenerator;
import forge.gamemodes.match.HostedMatch;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.util.storage.IStorage;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;

/** A limited gauntlet whose rounds a frontend other than desktop's starts. */
public class GauntletTest {
    private String pool;
    private IStorage<DeckGroup> storage;

    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    @AfterMethod(alwaysRun = true)
    public void cleanUp() {
        FModel.getGauntletMini().setRoundStarter(null);
        final IStorage<DeckGroup> kept = storage == null ? FModel.getDecks().getSealed() : storage;
        if (pool != null && kept.contains(pool)) {
            kept.delete(pool);
        }
        storage = null;
    }

    // Fails if any gauntlet path still starts a round through getNewGuiGame, which a web seat cannot give
    @Test
    public void aSetStarterRunsEveryRound() {
        pool = "Gauntlet test " + UUID.randomUUID().toString().substring(0, 8);
        final SealedCardPoolGenerator gen = SealedCardPoolGenerator.full(6);
        final DeckGroup group = gen.buildGroup(pool, gen.getCardPool(false));
        FModel.getDecks().getSealed().add(group);

        final List<Deck> opponents = new ArrayList<>();
        final GauntletMini gauntlet = FModel.getGauntletMini();
        gauntlet.setRoundStarter((type, players, human) -> {
            assertEquals(type, GameType.Sealed);
            assertSame(players.get(0), human);
            opponents.add(players.get(1).getDeck());
            return new HostedMatch();
        });

        gauntlet.launch(2, group.getHumanDeck(), GameType.Sealed);
        gauntlet.nextRound();
        gauntlet.restartRound();

        assertEquals(opponents.size(), 3);
        final List<Deck> ai = FModel.getDecks().getSealed().get(pool).getAiDecks();
        assertEquals(cards(opponents.get(0)), cards(ai.get(0)));
        assertEquals(cards(opponents.get(1)), cards(ai.get(1)));
        assertEquals(cards(opponents.get(2)), cards(ai.get(1)));
        assertEquals(gauntlet.getCurrentRound(), 2);
    }

    private static final class Recorder implements BrowserChannel {
        final List<JsonObject> got = new CopyOnWriteArrayList<>();

        @Override
        public void send(final JsonObject message) {
            got.add(message);
        }

        JsonObject await(final String type, final Predicate<JsonObject> wanted) throws InterruptedException {
            for (int i = 0; i < 6_000; i++) {
                for (int j = got.size() - 1; j >= 0; j--) {
                    final JsonObject m = got.get(j);
                    if (type.equals(m.get("t").getAsString()) && wanted.test(m)) {
                        return m;
                    }
                }
                Thread.sleep(10);
            }
            return null;
        }
    }

    private static JsonObject message(final String type, final Object... fields) {
        final JsonObject m = JsonCodec.message(type);
        for (int i = 0; i < fields.length; i += 2) {
            if (fields[i + 1] instanceof Number n) {
                m.addProperty((String) fields[i], n);
            } else {
                m.addProperty((String) fields[i], (String) fields[i + 1]);
            }
        }
        return m;
    }

    /** A host on the pools page of kind, with a pool called pool stored there. */
    private Recorder hostWithPool(final WebSessions sessions, final String kind) throws InterruptedException {
        pool = "Gauntlet test " + UUID.randomUUID().toString().substring(0, 8);
        storage = "draft".equals(kind) ? FModel.getDecks().getDraft() : FModel.getDecks().getSealed();
        final SealedCardPoolGenerator gen = SealedCardPoolGenerator.full(6);
        final DeckGroup group = gen.buildGroup(pool, gen.getCardPool(false));
        group.getHumanDeck().getMain().addAll(group.getAiDecks().get(0).getMain());
        storage.add(group);
        final Recorder host = new Recorder();
        sessions.connected(host, "host", true);
        sessions.onMessage(host, JsonCodec.message("claimHost"));
        sessions.onMessage(host, message("setName", "name", "Host"));
        sessions.onMessage(host, message("limitedOpen", "kind", kind));
        Assert.assertNotNull(host.await("hello", h -> h.has("inEvent") && h.get("inEvent").getAsBoolean()));
        return host;
    }

    // Fails if several opponents are not all seated in one free-for-all, or more than a match can hold are seated
    @Test(timeOut = 240_000)
    public void severalOpponentsSeatsThem() throws Exception {
        WebTestSupport.skipUnlessStress();
        final WebSessions sessions = new WebSessions((WebGuiBase) GuiBase.getInterface(), 120_000, () -> { });
        try {
            final Recorder host = hostWithPool(sessions, "draft");
            sessions.onMessage(host, message("poolPlay", "name", pool, "mode", "several", "opponent", 0, "count", 5, "games", 1));
            Assert.assertNotNull(host.await("hello", h -> h.get("inMatch").getAsBoolean()), "the match never started");
            final JsonObject typed = host.await("state", m -> m.toString().contains("\"GameType\":\"Draft\""));
            Assert.assertNotNull(typed, "the match is not a draft match");
            final Matcher players = Pattern.compile("\"Players\":\\[([^\\]]*)]").matcher(typed.toString());
            Assert.assertTrue(players.find());
            Assert.assertEquals(players.group(1).split("\"ref\"").length - 1, 4, "not four players at the table");
        } finally {
            sessions.shutdown();
        }
    }

    // Fails if a draft's gauntlet does not start, which reads its pool from the drafts rather than the sealed pools
    @Test(timeOut = 240_000)
    public void aDraftGauntletStarts() throws Exception {
        WebTestSupport.skipUnlessStress();
        final WebSessions sessions = new WebSessions((WebGuiBase) GuiBase.getInterface(), 120_000, () -> { });
        try {
            final Recorder host = hostWithPool(sessions, "draft");
            sessions.onMessage(host, message("poolPlay", "name", pool, "mode", "gauntlet", "opponent", 0, "count", 0, "games", 1));
            Assert.assertNotNull(host.await("hello", h -> h.get("inMatch").getAsBoolean()), "the draft gauntlet never started");
            Assert.assertNotNull(host.await("state", m -> m.toString().contains("\"GameType\":\"Draft\"")), "the match is not a draft match");
        } finally {
            sessions.shutdown();
        }
    }

    // Fails if a lost gauntlet match is not recorded, offers the next round, or leaving it keeps the record
    @Test(timeOut = 240_000)
    public void aLostMatchOffersNoNextRound() throws Exception {
        WebTestSupport.skipUnlessStress();
        final WebSessions sessions = new WebSessions((WebGuiBase) GuiBase.getInterface(), 120_000, () -> { });
        try {
            final Recorder host = hostWithPool(sessions, "sealed");
            sessions.onMessage(host, message("poolPlay", "name", pool, "mode", "gauntlet", "opponent", 0, "count", 0, "games", 1));
            Assert.assertNotNull(host.await("hello", h -> h.get("inMatch").getAsBoolean()), "the gauntlet never started");
            // A concede sent before the seat has its game controller is dropped, so it is sent until the game ends
            JsonObject over = null;
            for (int i = 0; i < 60 && over == null; i++) {
                sessions.onMessage(host, JsonCodec.message("concede"));
                Thread.sleep(2_000);
                over = host.got.stream().filter(m -> "gameOver".equals(m.get("t").getAsString())).findFirst().orElse(null);
            }
            Assert.assertNotNull(over, "conceding never ended the game");
            final JsonObject result = host.await("limitedResult", m -> true);
            Assert.assertNotNull(result, "the lost game was not recorded");
            Assert.assertEquals(result.get("losses").getAsInt(), 1);
            Assert.assertTrue(result.get("matchOver").getAsBoolean());
            Assert.assertFalse(result.get("nextRound").getAsBoolean(), "a lost match offered the next round");
            sessions.onMessage(host, JsonCodec.message("leave"));
            Assert.assertNotNull(host.await("hello", h -> h.has("inEvent") && h.get("inEvent").getAsBoolean()), "leaving did not return to the pool");
            Assert.assertEquals(FModel.getGauntletMini().getCurrentRound(), 1);
            Assert.assertEquals(FModel.getGauntletMini().getLosses(), 0, "quitting kept the record");
        } finally {
            sessions.shutdown();
        }
    }

    private static List<String> cards(final Deck deck) {
        return deck.getMain().toFlatList().stream().map(c -> c.getName()).sorted().toList();
    }
}
