package forge.gamemodes.net.client;

import forge.gamemodes.net.event.SeatLostEvent;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** How a netplay client reacts to what the server tells it. These need no running server. */
public class FGameClientShould {
    // Fails if a kicked client treats the closed connection as a drop and starts retrying
    @Test
    public void stopRetryingWhenKickedWhileConnected() {
        final FGameClient client = new FGameClient("Bea", null, "localhost", 1);
        client.onSeatLost(SeatLostEvent.Reason.KICKED);
        client.onDisconnected();
        assertThat(client.getReconnectState()).isEqualTo(FGameClient.ReconnectState.SEAT_LOST);
        assertThat(client.wasKicked()).isTrue();
    }
}
