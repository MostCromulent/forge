package forge.web.campaign.rogue;

import com.google.gson.JsonParser;
import forge.gamemodes.rogue.flow.RogueDecisions.CardChoice;
import forge.gamemodes.rogue.flow.RogueDecisions.CardChoiceKind;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.web.WebTestSupport;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;

public class RoguePicksTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    // Fails if picks are read as cards instead of places in the offer, so two equal copies cannot both be picked
    @Test
    public void equalCopiesCanBothBePicked() {
        final PaperCard ring = FModel.getMagicDb().getCommonCards().getCard("Sol Ring");
        final List<PaperCard> shown = List.of(ring, ring);
        final CardChoice choice = new CardChoice(CardChoiceKind.REMOVE, "", "", shown, null, 2, 2, null);
        Assert.assertEquals(WebRogueDecisions.picked(choice, shown, JsonParser.parseString("[0, 1]")), List.of(ring, ring));
        Assert.assertNull(WebRogueDecisions.picked(choice, shown, JsonParser.parseString("[0, 0]")), "one copy picked twice");
        Assert.assertNull(WebRogueDecisions.picked(choice, shown, JsonParser.parseString("[1]")), "fewer than the least");
    }
}
