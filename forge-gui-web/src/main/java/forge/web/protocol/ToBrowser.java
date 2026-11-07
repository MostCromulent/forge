package forge.web.protocol;

import forge.gamemodes.net.DeltaPacket;
import forge.web.protocol.Wire.Event;
import forge.web.protocol.Wire.Message;
import forge.web.protocol.Wire.Request;

import java.util.List;

/** Every message the server sends the browser, as records. The build generates src/main/ts/protocol.gen.ts from this file. So a changed record fails the build at each place the browser reads it. */
public final class ToBrowser {
    private ToBrowser() {
    }

    /** A pointer to an object in the game's table, by its delta key. */
    public record Ref(int ref) {
        public static Ref card(final int id) {
            return new Ref(DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_CARD_VIEW, id));
        }

        public static Ref player(final int id) {
            return new Ref(DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_PLAYER_VIEW, id));
        }
    }

    static final List<Class<? extends Record>> EVENTS = Wire.marked(Event.class);

    /** Every message record. The TypeScript is generated from these. */
    static final List<Class<? extends Record>> MESSAGES = Wire.marked(Message.class);

    static final List<Class<? extends Record>> REQUESTS = Wire.marked(Request.class);

}
