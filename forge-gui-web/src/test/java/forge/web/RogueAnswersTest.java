package forge.web;

import forge.gamemodes.rogue.effect.BazaarItem;
import forge.gamemodes.rogue.effect.SanctumContext;
import forge.gamemodes.rogue.flow.RogueDecisions;
import forge.gamemodes.rogue.flow.RogueDecisions.CardChoice;
import forge.gamemodes.rogue.flow.RogueDecisions.CardChoiceKind;
import forge.gamemodes.rogue.flow.RogueDecisions.SanctumAction;
import forge.gamemodes.rogue.flow.RogueDecisions.SanctumAnswer;
import forge.gamemodes.rogue.flow.RogueDecisions.SanctumOffer;
import forge.gamemodes.rogue.flow.RogueDecisions.ShopAnswer;
import forge.gamemodes.rogue.flow.RogueDecisions.ShopOffer;
import forge.item.PaperCard;
import forge.model.FModel;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Set;

@Test(singleThreaded = true)
public class RogueAnswersTest {
    private PaperCard ring;
    private PaperCard elves;
    private PaperCard bolt;

    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
        ring = card("Sol Ring");
        elves = card("Llanowar Elves");
        bolt = card("Lightning Bolt");
    }

    private static PaperCard card(final String name) {
        return FModel.getMagicDb().getCommonCards().getCard(name);
    }

    private static CardChoice choice(final int min, final int max, final PaperCard... candidates) {
        return new CardChoice(CardChoiceKind.ADD, "", "", List.of(candidates), null, min, max, null);
    }

    private static ShopOffer shop(final List<BazaarItem> items, final int gold, final int rerollCost, final boolean rerollEnabled) {
        return new ShopOffer(items, null, gold, "", "", rerollCost, rerollEnabled, 0);
    }

    private static ShopAnswer buy(final BazaarItem... items) {
        return new ShopAnswer(false, Set.of(items), 0);
    }

    @Test
    public void twoCopiesOfOneCandidateAreRefused() {
        Assert.assertFalse(choice(0, 2, ring, elves).accepts(List.of(ring, ring)));
        Assert.assertTrue(choice(0, 2, ring, elves, ring).accepts(List.of(ring, ring)));
        Assert.assertTrue(choice(0, 2, ring, elves).accepts(List.of(elves, ring)));
        Assert.assertFalse(choice(0, 2, ring, elves).accepts(List.of(bolt)));
    }

    @Test
    public void tooFewAndTooManyCardsAreRefused() {
        final CardChoice choice = choice(1, 2, ring, elves, bolt);
        Assert.assertFalse(choice.accepts(List.of()));
        Assert.assertTrue(choice.accepts(List.of(ring)));
        Assert.assertTrue(choice.accepts(List.of(ring, elves)));
        Assert.assertFalse(choice.accepts(List.of(ring, elves, bolt)));
    }

    @Test
    public void aShopAnswerBeyondTheGoldIsRefused() {
        final BazaarItem four = BazaarItem.forCard(ring, 4);
        final BazaarItem three = BazaarItem.forCard(elves, 3);
        final ShopOffer offer = shop(List.of(four, three), 6, 0, false);
        Assert.assertTrue(offer.accepts(buy(four)));
        Assert.assertFalse(offer.accepts(buy(four, three)));
        Assert.assertTrue(shop(List.of(four, three), 7, 0, false).accepts(buy(four, three)));
        Assert.assertFalse(offer.accepts(buy(BazaarItem.forCard(ring, 1))), "the offer has it at another price");
        Assert.assertTrue(offer.accepts(buy()));
    }

    @Test
    public void aRerollThatCannotBePaidIsRefused() {
        final ShopAnswer reroll = new ShopAnswer(true, Set.of(), 0);
        Assert.assertTrue(shop(List.of(), 2, 2, true).accepts(reroll));
        Assert.assertFalse(shop(List.of(), 1, 2, true).accepts(reroll));
        Assert.assertFalse(shop(List.of(), 2, 2, false).accepts(reroll));
    }

    @Test
    public void restingWhenItIsDisabledIsRefused() {
        final SanctumAnswer rest = new SanctumAnswer(SanctumAction.HEAL, null);
        Assert.assertTrue(new SanctumOffer("", true, null, new SanctumContext(), null).accepts(rest));
        final SanctumOffer disabled = new SanctumOffer("", false, "", new SanctumContext(), null);
        Assert.assertFalse(disabled.accepts(rest));
        Assert.assertTrue(disabled.accepts(new SanctumAnswer(SanctumAction.COOK, null)));
    }

    @Test
    public void aSanctumChoiceThatIsNotOfferedIsRefused() {
        final SanctumContext context = new SanctumContext();
        context.extraChoices.add(new SanctumContext.SanctumChoice("train", "Train", ""));
        final SanctumOffer offer = new SanctumOffer("", true, null, context, null);
        Assert.assertTrue(offer.accepts(new SanctumAnswer(SanctumAction.CUSTOM, context.extraChoices.get(0))));
        Assert.assertFalse(offer.accepts(new SanctumAnswer(SanctumAction.CUSTOM, null)));
        Assert.assertFalse(offer.accepts(new SanctumAnswer(SanctumAction.CUSTOM,
                new SanctumContext.SanctumChoice("other", "Other", ""))));
    }

    @Test
    public void commandersThatDoNotPartnerAreRefused() {
        final PaperCard tymna = card("Tymna the Weaver");
        final PaperCard thrasios = card("Thrasios, Triton Hero");
        final PaperCard aragorn = card("Aragorn, the Uniter");
        Assert.assertTrue(RogueDecisions.commanders(List.of(aragorn)));
        Assert.assertTrue(RogueDecisions.commanders(List.of(tymna, thrasios)));
        Assert.assertFalse(RogueDecisions.commanders(List.of(tymna, aragorn)));
        Assert.assertFalse(RogueDecisions.commanders(List.of(tymna, tymna)));
        Assert.assertFalse(RogueDecisions.commanders(List.of(elves)));
        Assert.assertFalse(RogueDecisions.commanders(List.of()));
    }
}
