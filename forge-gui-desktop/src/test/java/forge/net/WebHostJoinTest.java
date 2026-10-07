package forge.net;

import forge.gamemodes.net.event.UpdateLobbyPlayerEvent;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

/**
 * A desktop client joining a web host that is already running, by the invite link in -Dforge.web.invite.
 * The host is another process with a person or a script at its page, so this runs only when given a link.
 */
public class WebHostJoinTest {
    // Fails if the client is not seated, is not taken into the match, loses sync with the host, or is not told the game ended
    @Test(timeOut = 1_500_000)
    public void joinsByInviteLinkAndPlaysTheGameOut() throws Exception {
        final String link = System.getProperty("forge.web.invite");
        if (link == null) {
            throw new SkipException("No web host to join. Give its invite link as -Dforge.web.invite.");
        }
        TestUtils.ensureFModelInitialized();
        try (HeadlessNetworkClient client = new HeadlessNetworkClient(System.getProperty("forge.web.name", "Desk"), link)) {
            Assert.assertTrue(client.connect(60_000), "the web host never gave this client a seat");
            System.out.println("WEBJOIN seated in slot " + client.getAssignedSlot());
            client.getClient().send(UpdateLobbyPlayerEvent.deckUpdate(TestDeckLoader.createMinimalDeck("Forest", 60)));
            Thread.sleep(500);
            client.setReady();
            Assert.assertTrue(client.waitForGameStart(600_000), "the host never took this client into a match");
            System.out.println("WEBJOIN match started");
            Assert.assertTrue(client.waitForGameFinish(600_000), "the client was never told the game ended");
            System.out.println("WEBJOIN game ended: deltas=" + client.getDeltaPacketsReceived() + " fullSyncs=" + client.getFullStateSyncsReceived()
                    + " bytes=" + client.getTotalDeltaBytes() + " mismatches=" + client.getEventStateMismatches());
            Assert.assertTrue(client.getDeltaPacketsReceived() > 0, "the game was played without one update reaching the client");
            Assert.assertEquals(client.getEventStateMismatches(), 0, "the client's view of the game fell out of step with the host's");
        }
    }
}
