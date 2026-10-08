package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.deck.Deck;
import forge.gamemodes.match.HostedMatch;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.testng.asserts.SoftAssert;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** What one message to a client may hold, read from every message of ten turns the computer plays against itself. */
public class BatchBoundaryTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    private static Set<Integer> onBattlefields(final BrowserModel model) {
        final Set<Integer> cards = new HashSet<>();
        for (final JsonObject object : model.objectsCopy().values()) {
            final JsonElement zone = object.get("Battlefield");
            if (zone != null && zone.isJsonArray()) {
                zone.getAsJsonArray().forEach(card -> cards.add(card.getAsJsonObject().get("ref").getAsInt()));
            }
        }
        return cards;
    }

    private static int turn(final TestBrowser browser) {
        final JsonObject last = browser.last("state");
        final JsonObject game = last == null ? null : browser.model.objectsCopy().get(last.get("root").getAsInt());
        return game == null || !game.has("Turn") ? 0 : game.get("Turn").getAsInt();
    }

    private static String zone(final JsonObject move, final String end) {
        final JsonElement place = move.get(end);
        return place == null || place.isJsonNull() ? "" : place.getAsJsonObject().get("zone").getAsString();
    }

    @Test(timeOut = 360000)
    public void aMessageHoldsWholeActionsAndNoMoreThanOne() throws Exception {
        final Deck red = TestDecks.of("Red", "Mountain", 22, "Shock", 10, "Goblin Piker", 14, "Raging Goblin", 14);
        TestMatch.play(red, red, gui -> new TestBrowser(gui, true), (local, gui, browser) -> {
            for (int i = 0; i < 300; i++) {
                final HostedMatch match = local.hostedMatch();
                if (match != null && match.getGame() != null && browser.all("state").size() > 1) {
                    break;
                }
                Thread.sleep(100);
            }
            // The computer takes the web seat, so both sides cast spells and nobody is asked anything
            local.spectate();
            // Ten turns of this deck hold every case below many times over, and a whole game takes far longer
            for (int i = 0; i < 3000 && browser.gameOver.getCount() > 0 && turn(browser) < 10; i++) {
                Thread.sleep(100);
            }

            final BrowserModel replay = new BrowserModel();
            Set<Integer> before = Set.of();
            final List<String> leftUnsaid = new ArrayList<>();
            int left = 0, castThenResolved = 0, resolvedIntoNextTurn = 0, landThenCast = 0, resolutions = 0, lands = 0, casts = 0;
            for (final JsonObject message : browser.all("state")) {
                replay.applyStateMessage(message);
                final Set<Integer> after = onBattlefields(replay);
                if (message.get("full").getAsBoolean()) {
                    before = after;
                    continue;
                }
                final Set<Integer> movedOff = new HashSet<>();
                boolean cast = false, land = false, resolved = false;
                final JsonArray events = message.getAsJsonArray("events");
                for (final JsonElement e : events) {
                    final JsonObject event = e.getAsJsonObject();
                    switch (event.get("kind").getAsString()) {
                        case "cardMoved" -> {
                            if ("Battlefield".equals(zone(event, "from"))) {
                                movedOff.add(event.getAsJsonObject("card").get("ref").getAsInt());
                            }
                            if ("Hand".equals(zone(event, "from")) && "Battlefield".equals(zone(event, "to"))) {
                                land = true;
                                lands++;
                            }
                        }
                        case "stackAdded" -> {
                            if (land) landThenCast++;
                            cast = true;
                            casts++;
                        }
                        case "stackResolved" -> {
                            if (cast) castThenResolved++;
                            resolved = true;
                            resolutions++;
                        }
                        default -> { }
                    }
                }
                for (final Integer card : before) {
                    if (!after.contains(card)) {
                        left++;
                        if (!movedOff.contains(card)) leftUnsaid.add(card + " in message " + message.get("seq"));
                    }
                }
                final JsonObject game = message.getAsJsonObject("deltas").getAsJsonObject(message.get("root").getAsString());
                if (resolved && game != null && game.has("Turn")) resolvedIntoNextTurn++;
                before = after;
            }
            // Fails if the game never did what the claims below are about, so they would hold of nothing
            Assert.assertTrue(left > 0 && resolutions > 0 && lands > 0 && casts > 0,
                    left + " cards left a battlefield, " + resolutions + " resolutions, " + lands + " lands, " + casts + " casts");
            // Each is checked even when an earlier one fails, so one run says everything that is wrong
            final SoftAssert pins = new SoftAssert();
            // Fails if a card leaves a battlefield in one message and its move is told in a later one, so a client has nothing to show it leave by
            pins.assertEquals(leftUnsaid, List.of(), "cards that left a battlefield without their move");
            // Fails if a spell is cast and resolves in one message, so a client never sees it on the stack
            pins.assertEquals(castThenResolved, 0, "messages holding a cast and then a resolution");
            // Fails if a player's land waits for the next thing they do, so others see it late
            pins.assertEquals(landThenCast, 0, "messages holding a land and then a cast");
            // Fails if a resolution arrives with the turn after it, so a client shows the new turn with the spell still on the stack
            pins.assertEquals(resolvedIntoNextTurn, 0, "messages holding a resolution and the next turn");
            pins.assertAll();
        });
    }
}
