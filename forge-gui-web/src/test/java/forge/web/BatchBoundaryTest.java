package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.deck.Deck;
import forge.gamemodes.match.HostedMatch;
import forge.web.match.BrowserModel;
import forge.web.match.MatchProbe;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.testng.asserts.SoftAssert;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Tests what one message to a client may contain. It reads every message from ten turns of the computer playing itself. */
public class BatchBoundaryTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    private static Set<Integer> onBattlefields(final BrowserModel model) {
        final Set<Integer> cards = new HashSet<>();
        for (final JsonObject object : MatchProbe.objects(model).values()) {
            final JsonElement zone = object.get("Battlefield");
            if (zone != null && zone.isJsonArray()) {
                zone.getAsJsonArray().forEach(card -> cards.add(card.getAsJsonObject().get("ref").getAsInt()));
            }
        }
        return cards;
    }

    private static int turn(final TestBrowser browser) {
        final JsonObject last = browser.last("state");
        final JsonObject game = last == null ? null : MatchProbe.objects(browser.model).get(last.get("root").getAsInt());
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
            for (int i = 0; i < 3000; i++) {
                final HostedMatch match = local.hostedMatch();
                if (match != null && match.getGame() != null && browser.all("state").size() > 1) {
                    break;
                }
                Thread.sleep(10);
            }
            // The computer takes the web seat, so both sides cast spells and nobody is asked anything
            local.spectate();
            // Ten turns of this deck cover every case below many times, and a whole game takes much longer
            for (int i = 0; i < 30000 && browser.gameOver.getCount() > 0 && turn(browser) < 10; i++) {
                Thread.sleep(10);
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
            // Fails if the game never did what the checks below test, which would make them pass on nothing
            Assert.assertTrue(left > 0 && resolutions > 0 && lands > 0 && casts > 0,
                    left + " cards left a battlefield, " + resolutions + " resolutions, " + lands + " lands, " + casts + " casts");
            // Each check runs even when an earlier one fails, so one run reports every problem
            final SoftAssert pins = new SoftAssert();
            // Fails if a card leaves a battlefield in one message but its move comes in a later one. The client then cannot animate the move
            pins.assertEquals(leftUnsaid, List.of(), "cards that left a battlefield without their move");
            // Fails if a spell is cast and resolves in one message, so a client never sees it on the stack
            pins.assertEquals(castThenResolved, 0, "messages holding a cast and then a resolution");
            // Fails if a land play is held until the player's next action, so others see it late
            pins.assertEquals(landThenCast, 0, "messages holding a land and then a cast");
            // Fails if a resolution arrives with the next turn, so a client shows the new turn with the spell still on the stack
            pins.assertEquals(resolvedIntoNextTurn, 0, "messages holding a resolution and the next turn");
            pins.assertAll();
        });
    }
}
