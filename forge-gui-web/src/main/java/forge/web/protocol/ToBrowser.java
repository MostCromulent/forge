package forge.web.protocol;

import forge.gamemodes.net.DeltaPacket;
import forge.web.protocol.FromBrowser.EventKind;
import forge.web.protocol.Wire.Event;
import forge.web.protocol.Wire.Message;
import forge.web.protocol.Wire.Nullable;
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

    /** The page the browser shows: the start page, a seat being taken, match setup, the Limited pages, or a match. */
    public enum Screen { menu, opening, setup, event, playing }

    /**
     * eventKind is set on the event screen.
     * hostPassword says the host's seat needs the password from this browser.
     */
    @Message("hello")
    public record Hello(Screen screen, boolean spectating, boolean host,
            boolean canClaimHost, boolean hostPassword, boolean networked, @Nullable String playerName, List<Integer> avatars, List<Integer> sleeves, int avatarCount,
            int sleeveCount, List<SavedSleeveArt> sleeveArt, @Nullable String eventPool, int sealedPools,
            @Nullable EventKind eventKind, boolean drafting, int draftPools,
            @Nullable String version, ServerSettings settings, int session) {
    }

    @Message("error")
    public record ErrorMessage(String message) {
    }

    @Message("notice")
    public record Notice(@Nullable String title, @Nullable String message, boolean error) {
    }

    /** A few buttons, or a list. */
    public enum HostChoiceKind { confirm, choices }

    /** A question outside a match, answered with the indices chosen. */
    @Message("hostChoice")
    public record HostChoice(int id, HostChoiceKind kind, @Nullable String title, @Nullable String message, int min, int max, List<String> options,
            @Nullable List<String> images, @Nullable String pictured) {
    }

    static final List<Class<? extends Record>> EVENTS = Wire.marked(Event.class);

    /** Every message record. The TypeScript is generated from these. */
    static final List<Class<? extends Record>> MESSAGES = Wire.marked(Message.class);

    static final List<Class<? extends Record>> REQUESTS = Wire.marked(Request.class);

}
