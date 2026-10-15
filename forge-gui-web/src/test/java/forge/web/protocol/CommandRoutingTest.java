package forge.web.protocol;

import forge.web.WebSession;
import forge.web.campaign.ConquestGame;
import forge.web.campaign.QuestGame;
import forge.web.deck.DeckSession;
import forge.web.limited.OfflineLimited;
import forge.web.match.WebGuiGame;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

/** The session hands each command to whichever of these owns it, so each must be owned once. */
public class CommandRoutingTest {
    private static final List<Commands<?>> OWNERS = List.of(WebSession.COMMANDS, DeckSession.COMMANDS, OfflineLimited.COMMANDS,
            WebGuiGame.COMMANDS, ConquestGame.COMMANDS, QuestGame.COMMANDS);

    // Fails if a command the browser can send is handled nowhere, so it would be dropped without a word, or by two owners, of which only the first is asked
    @Test
    public void everyCommandHasOneOwner() {
        final List<String> wrong = new ArrayList<>();
        for (final Class<? extends Record> c : FromBrowser.COMMANDS) {
            for (final String name : Wire.commandNames(c)) {
                final long owners = OWNERS.stream().filter(o -> o.names().contains(name)).count();
                if (owners != 1) {
                    wrong.add(name + " has " + owners + " owners");
                }
            }
        }
        Assert.assertTrue(wrong.isEmpty(), String.join(", ", wrong));
    }
}
