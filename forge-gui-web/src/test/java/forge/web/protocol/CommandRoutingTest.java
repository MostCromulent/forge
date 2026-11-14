package forge.web.protocol;

import com.google.gson.JsonObject;
import forge.web.TableCommands;
import forge.web.WebSession;
import forge.web.deck.DeckWorkspace;
import forge.web.limited.OfflineLimited;
import forge.web.match.WebGuiGame;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

/** The session passes each command to the one of these that owns it, so each command must have exactly one owner. */
public class CommandRoutingTest {
    private static final List<Commands<?>> OWNERS = List.of(WebSession.COMMANDS, TableCommands.COMMANDS, DeckWorkspace.COMMANDS, OfflineLimited.COMMANDS,
            WebGuiGame.COMMANDS);

    // Fails if a browser command has no owner, so it is silently dropped, or has two owners, so only the first one handles it
    @Test
    public void everyCommandHasOneOwner() {
        final List<String> wrong = new ArrayList<>();
        for (final Class<? extends Record> c : FromBrowser.COMMANDS) {
            for (final String name : MessageJson.commandNames(c)) {
                final JsonObject message = new JsonObject();
                message.addProperty("t", name);
                final Record command = MessageJson.decode(message);
                final long owners = OWNERS.stream().filter(o -> o.handles(command)).count();
                if (owners != 1) {
                    wrong.add(name + " has " + owners + " owners");
                }
            }
        }
        Assert.assertTrue(wrong.isEmpty(), String.join(", ", wrong));
    }
}
