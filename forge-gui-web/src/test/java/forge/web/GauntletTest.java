package forge.web;

import forge.deck.Deck;
import forge.deck.DeckGroup;
import forge.game.GameType;
import forge.gamemodes.limited.GauntletMini;
import forge.gamemodes.limited.SealedCardPoolGenerator;
import forge.gamemodes.match.HostedMatch;
import forge.model.FModel;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;

/** A limited gauntlet whose rounds a frontend other than desktop's starts. */
public class GauntletTest {
    private String pool;

    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    @AfterMethod(alwaysRun = true)
    public void cleanUp() {
        FModel.getGauntletMini().setRoundStarter(null);
        if (pool != null && FModel.getDecks().getSealed().contains(pool)) {
            FModel.getDecks().getSealed().delete(pool);
        }
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

    private static List<String> cards(final Deck deck) {
        return deck.getMain().toFlatList().stream().map(c -> c.getName()).sorted().toList();
    }
}
