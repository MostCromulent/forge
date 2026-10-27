package forge.gamemodes.net;

import forge.game.card.CardView;
import forge.gamemodes.net.event.GuiGameEvent;
import forge.trackable.TrackableTypes;
import forge.trackable.Tracker;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.serialization.ClassResolvers;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class CompatibleObjectEncoderShould {

    private static final int CONSUMER = 0;
    private static final int CARD_ID = 7;

    @Test
    public void leaveAHostMessagesCardsToBeResolvedWhenTheClientHandlesIt() throws Exception {
        CardView onHost = new CardView(CARD_ID, new Tracker());
        onHost.registerConsumer(CONSUMER);
        CompatibleObjectEncoder encoder = new CompatibleObjectEncoder(null);
        encoder.setTracker(onHost.getTracker());
        encoder.setConsumerId(CONSUMER);
        Tracker client = new Tracker();
        client.putObj(TrackableTypes.CardViewType, CARD_ID, new CardView(CARD_ID, client));
        CompatibleObjectDecoder decoder = new CompatibleObjectDecoder(1024 * 1024, ClassResolvers.cacheDisabled(null));
        decoder.setTracker(client);
        EmbeddedChannel channel = new EmbeddedChannel(decoder);

        channel.writeInbound(encoder.encodeToBuf(new GuiGameEvent(ProtocolMethod.setCard, onHost), ByteBufAllocator.DEFAULT));
        GuiGameEvent received = channel.readInbound();
        // The delta ahead of the message replaces the client's view of the card before the message is handled
        CardView replacement = new CardView(CARD_ID, client);
        client.putObj(TrackableTypes.CardViewType, CARD_ID, replacement);
        Object[] args = TrackableSerializer.decodeArgs(received.getEncodedObjects(), client);

        assertThat(args).containsExactly(replacement);
        channel.finishAndReleaseAll();
    }
}
