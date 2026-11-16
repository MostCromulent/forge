package forge.ai;

import java.util.EnumMap;
import java.util.Map;

import org.testng.annotations.Test;

import forge.ImageKeys;
import forge.card.CardStateName;
import forge.game.Game;
import forge.game.GameSnapshot;
import forge.game.GameState;
import forge.game.card.Card;
import forge.game.card.CardState;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.IPaperCard;
import forge.item.PaperCard;
import forge.model.FModel;

import static org.testng.Assert.*;

public class CardPrintingTest extends AITest {

    private enum Key { FRONT, BACK, SPEC_W, SPEC_U, SPEC_B, SPEC_R, SPEC_G }

    private static String imageKey(IPaperCard cp, Key key) {
        return switch (key) {
            case FRONT -> cp.getImageKey(false);
            case BACK -> cp.getImageKey(true);
            case SPEC_W -> cp.getImageKey(false) + ImageKeys.SPECFACE_W;
            case SPEC_U -> cp.getImageKey(false) + ImageKeys.SPECFACE_U;
            case SPEC_B -> cp.getImageKey(false) + ImageKeys.SPECFACE_B;
            case SPEC_R -> cp.getImageKey(false) + ImageKeys.SPECFACE_R;
            case SPEC_G -> cp.getImageKey(false) + ImageKeys.SPECFACE_G;
        };
    }

    private static Map<CardStateName, Key> faces(Object... pairs) {
        Map<CardStateName, Key> map = new EnumMap<>(CardStateName.class);
        map.put(CardStateName.Original, Key.FRONT);
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((CardStateName) pairs[i], (Key) pairs[i + 1]);
        }
        return map;
    }

    private static void assertFaces(Card c, IPaperCard cp, Map<CardStateName, Key> expected) {
        assertEquals(c.getStates(), expected.keySet(), c.getName() + " states");
        for (Map.Entry<CardStateName, Key> e : expected.entrySet()) {
            CardState state = c.getState(e.getKey());
            String where = c.getName() + " " + e.getKey();
            assertEquals(state.getSetCode(), cp.getEdition(), where + " set");
            assertEquals(state.getRarity(), cp.getRarity(), where + " rarity");
            assertEquals(state.getImageKey(), imageKey(cp, e.getValue()), where + " image");
        }
    }

    private PaperCard otherPrinting(Card c) {
        IPaperCard current = c.getPaperCard();
        return FModel.getMagicDb().getCommonCards().getAllCards(current.getRules()).stream()
                .filter(pc -> !pc.getEdition().equals(current.getEdition()))
                .findFirst().orElse(null);
    }

    private void checkLayout(String name, Map<CardStateName, Key> expected) {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Card c = createCard(name, p);
        assertFaces(c, c.getPaperCard(), expected);

        PaperCard other = otherPrinting(c);
        if (other != null) {
            c.setPrinting(other);
            assertEquals(c.getCurrentStateName(), CardStateName.Original, name + " current state");
            assertFaces(c, other, expected);
        }
    }

    @Test
    public void factoryAndChangeSetEveryFaceForEachLayout() {
        checkLayout("Grizzly Bears", faces());
        checkLayout("Delver of Secrets", faces(CardStateName.Backside, Key.BACK));
        checkLayout("Valki, God of Lies", faces(CardStateName.Backside, Key.BACK));
        checkLayout("Bruna, the Fading Light", faces(CardStateName.Meld, Key.BACK));
        checkLayout("Fire // Ice", faces(CardStateName.LeftSplit, Key.FRONT, CardStateName.RightSplit, Key.FRONT));
        checkLayout("Bottomless Pool // Locker Room", faces(CardStateName.LeftSplit, Key.FRONT, CardStateName.RightSplit, Key.FRONT));
        checkLayout("Bushi Tenderfoot", faces(CardStateName.Flipped, Key.FRONT));
        checkLayout("Bonecrusher Giant", faces(CardStateName.Secondary, Key.FRONT));
        checkLayout("Bloomvine Regent", faces(CardStateName.Secondary, Key.FRONT));
        checkLayout("Hela, Mistress of Darkness", faces(CardStateName.Secondary, Key.FRONT));
        checkLayout("Adventurous Eater", faces(CardStateName.PreparedSpell, Key.FRONT));
        checkLayout("Alora, Rogue Companion", faces(CardStateName.SpecializeW, Key.SPEC_W, CardStateName.SpecializeU, Key.SPEC_U,
                CardStateName.SpecializeB, Key.SPEC_B, CardStateName.SpecializeR, Key.SPEC_R, CardStateName.SpecializeG, Key.SPEC_G));
    }

    @Test
    public void tokenGetsItsOwnImage() {
        Game game = initAndCreateGame();
        Card token = createToken("w_1_1_soldier", game.getPlayers().get(1));
        assertFaces(token, token.getPaperCard(), faces());
    }

    @Test
    public void transformedCardKeepsBackFaceAndPrintingSurvivesZoneChange() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Card c = addCard("Delver of Secrets", p);
        c.setState(CardStateName.Backside, true);
        PaperCard printing = otherPrinting(c);

        c.setPrinting(printing);

        assertEquals(c.getCurrentStateName(), CardStateName.Backside);
        assertFaces(c, printing, faces(CardStateName.Backside, Key.BACK));

        Card moved = game.getAction().moveToHand(c, null);
        assertSame(moved.getPaperCard(), printing);
        assertFaces(moved, printing, faces(CardStateName.Backside, Key.BACK));
    }

    @Test
    public void faceDownCardStaysHidden() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Card c = addCard("Grizzly Bears", p);
        PaperCard printing = otherPrinting(c);
        c.turnFaceDown(true);

        c.setPrinting(printing);

        assertTrue(c.isFaceDown());
        assertEquals(c.getState(CardStateName.FaceDown).getImageKey(), ImageKeys.getTokenKey(ImageKeys.HIDDEN_CARD));
        assertEquals(c.getState(CardStateName.Original).getImageKey(), printing.getImageKey(false));
    }

    @Test
    public void printingSurvivesSnapshotAndGameStateDump() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Card c = addCard("Grizzly Bears", p);
        PaperCard printing = otherPrinting(c);
        c.setPrinting(printing);

        Game copy = new GameSnapshot(game).makeCopy();
        Card copied = copy.getCardsIn(ZoneType.Battlefield).stream()
                .filter(cc -> cc.getName().equals("Grizzly Bears")).findFirst().orElseThrow();
        assertEquals(copied.getPaperCard(), printing);
        assertFaces(copied, printing, faces());

        GameState state = new GameState();
        state.initFromGame(game);
        assertTrue(state.toString().contains("Grizzly Bears|Set:" + printing.getEdition()), state.toString());
    }
}
